package com.example.whispernotes // Make sure this matches your package name!

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity(), RecognitionListener {

    private val viewModel: MainViewModel by viewModels()
    private var model: Model? = null
    private var speechService: SpeechService? = null

    private lateinit var button: Button
    private lateinit var transcribedText: TextView
    private lateinit var progressBar: ProgressBar

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startRecording()
            } else {
                Toast.makeText(this, "Permission denied! Cannot record audio.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button = findViewById(R.id.button)
        transcribedText = findViewById(R.id.transcribedText)
        progressBar = findViewById(R.id.progressBar)

        button.setOnClickListener {
            toggleRecording()
        }

        lifecycleScope.launch {
            viewModel.appState.collect { state ->
                updateUiForState(state)
            }
        }

        initVoskModel()
    }

    private fun initVoskModel() {
        viewModel.onLoading()
        lifecycleScope.launch {
            try {
                val modelPath = copyModelFromAssets()
                model = Model(modelPath)
                viewModel.onReady()
            } catch (e: IOException) {
                Log.e("VoskInit", "Failed to initialize Vosk model", e)
                viewModel.onError("Error: ${e.message}")
            }
        }
    }

    /**
     * The new, correct, recursive function to copy a nested asset directory.
     */
    private suspend fun copyModelFromAssets(): String = withContext(Dispatchers.IO) {
        val modelDir = "model-hi"
        val appModelDir = File(filesDir, modelDir)
        if (appModelDir.exists() && appModelDir.list()?.isNotEmpty() == true) {
            Log.d("VoskInit", "Model already exists in internal storage.")
            return@withContext appModelDir.absolutePath
        }

        Log.d("VoskInit", "Model not found, copying from assets...")
        // This is our new recursive copy function call
        copyAssetFolder(assets, modelDir, appModelDir.absolutePath)

        Log.d("VoskInit", "Model copied successfully.")
        return@withContext appModelDir.absolutePath
    }

    /**
     * Recursively copies an asset folder and its contents to a destination directory.
     */
    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String) {
        try {
            val files = assetManager.list(fromAssetPath)
            if (files.isNullOrEmpty()) {
                // It's a file, not a folder.
                copyAssetFile(assetManager, fromAssetPath, toPath)
            } else {
                // It's a folder.
                File(toPath).mkdirs()
                for (file in files) {
                    copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                }
            }
        } catch (e: IOException) {
            // This is thrown when list() is called on a file, so we copy the file.
            copyAssetFile(assetManager, fromAssetPath, toPath)
        }
    }

    private fun copyAssetFile(assetManager: AssetManager, fromAssetPath: String, toPath: String) {
        try {
            assetManager.open(fromAssetPath).use { inputStream ->
                File(toPath).outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: IOException) {
            Log.e("VoskCopy", "Failed to copy asset file: $fromAssetPath", e)
        }
    }


    // --- The rest of the MainActivity is unchanged ---

    private fun toggleRecording() {
        // ... same as before
        if (speechService != null) {
            stopRecording()
        } else {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startRecording()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    private fun startRecording() {
        // ... same as before
        val currentModel = model
        if (currentModel == null) {
            viewModel.onError("Model is not ready yet, please wait.")
            return
        }
        try {
            val rec = Recognizer(currentModel, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            viewModel.onRecording()
        } catch (e: IOException) {
            viewModel.onError(e.message ?: "Unknown IO Exception")
        }
    }

    private fun stopRecording() {
        // ... same as before
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        viewModel.onReady()
    }

    private fun updateUiForState(state: AppState) {
        // ... same as before
        when (state) {
            is AppState.Loading -> {
                button.text = "Loading Model..."
                button.isEnabled = false
                progressBar.visibility = ProgressBar.VISIBLE
                transcribedText.text = "Please wait while the model is being prepared..."
            }
            is AppState.Ready -> {
                button.text = "Start Recording"
                button.isEnabled = true
                progressBar.visibility = ProgressBar.INVISIBLE
                transcribedText.text = "Your transcribed text will appear here..."
            }
            is AppState.Recording -> {
                button.text = "Stop Recording"
                button.isEnabled = true
                progressBar.visibility = ProgressBar.INVISIBLE
                transcribedText.text = "Listening..."
            }
            is AppState.PartialResult -> {
                transcribedText.text = state.text
            }
            is AppState.Result -> {
                transcribedText.text = state.text
            }
            is AppState.Error -> {
                button.text = "Error"
                button.isEnabled = false
                progressBar.visibility = ProgressBar.INVISIBLE
                transcribedText.text = state.message
            }
        }
    }

    // --- RecognitionListener methods are unchanged ---
    override fun onResult(hypothesis: String) {
        val resultText = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"")
        if (resultText.isNotBlank()) {
            viewModel.onResult(resultText)
        }
    }
    override fun onPartialResult(hypothesis: String) { /* ... */ }
    override fun onError(e: Exception) { viewModel.onError(e.message ?: "Unknown recognition error") }
    override fun onFinalResult(hypothesis: String) {
        val resultText = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"")
        if (resultText.isNotBlank()) {
            viewModel.onResult(resultText)
        }
        stopRecording()
    }
    override fun onTimeout() { stopRecording() }
    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
    }
}

