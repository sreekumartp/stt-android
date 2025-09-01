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

    private val fullTranscript = StringBuilder()
    private lateinit var button: Button
    private lateinit var transcribedText: TextView
    private lateinit var progressBar: ProgressBar

    // Timestamp to throttle UI updates for partial results
    private var lastPartialResultTime: Long = 0

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
                viewModel.onError("Error: ${e.message}")
            }
        }
    }

    private suspend fun copyModelFromAssets(): String = withContext(Dispatchers.IO) {
        val modelDir = "model-hi" // Or "model-en-us"
        val appModelDir = File(filesDir, modelDir)
        if (appModelDir.exists() && appModelDir.list()?.isNotEmpty() == true) {
            return@withContext appModelDir.absolutePath
        }
        copyAssetFolder(assets, modelDir, appModelDir.absolutePath)
        return@withContext appModelDir.absolutePath
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String) {
        try {
            val files = assetManager.list(fromAssetPath)
            if (files.isNullOrEmpty()) {
                copyAssetFile(assetManager, fromAssetPath, toPath)
            } else {
                File(toPath).mkdirs()
                for (file in files) {
                    copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                }
            }
        } catch (e: IOException) {
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
            // Log error
        }
    }

    private fun toggleRecording() {
        if (speechService != null) {
            stopRecording()
        } else {
            checkPermissionAndStart()
        }
    }

    private fun checkPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startRecording()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startRecording() {
        val currentModel = model ?: run {
            viewModel.onError("Model is not ready.")
            return
        }
        try {
            fullTranscript.clear()
            val rec = Recognizer(currentModel, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            viewModel.onRecording()
        } catch (e: IOException) {
            viewModel.onError(e.message ?: "Unknown IO Exception")
        }
    }

    private fun stopRecording() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        viewModel.onReady()
    }

    private fun updateUiForState(state: AppState) {
        when (state) {
            is AppState.Loading -> {
                button.text = "Loading Model..."
                button.isEnabled = false
                progressBar.visibility = ProgressBar.VISIBLE
                transcribedText.text = "Please wait..."
            }
            is AppState.Ready -> {
                button.text = "Start Recording"
                button.isEnabled = true
                progressBar.visibility = ProgressBar.INVISIBLE
                if (fullTranscript.isEmpty()) {
                    transcribedText.text = "Your transcribed text will appear here..."
                } else {
                    transcribedText.text = fullTranscript.toString()
                }
            }
            is AppState.Recording -> {
                button.text = "Stop Recording"
                button.isEnabled = true
                progressBar.visibility = ProgressBar.INVISIBLE
                transcribedText.text = "Listening..."
            }
            is AppState.Error -> {
                button.text = "Error"
                button.isEnabled = false
                progressBar.visibility = ProgressBar.INVISIBLE
                transcribedText.text = state.message
            }
            is AppState.PartialResult, is AppState.Result -> {
                // No-op as text updates are handled directly in the listener
            }
        }
    }

    // --- RecognitionListener Logic ---

    override fun onResult(hypothesis: String) {
        Log.d("VoskListener", "onResult: $hypothesis")
        val resultText = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"")
        if (resultText.isNotBlank()) {
            fullTranscript.append(resultText).append(" ")
            transcribedText.text = fullTranscript.toString()
        }
    }

    override fun onPartialResult(hypothesis: String) {
        val currentTime = System.currentTimeMillis()
        // Throttle updates to every 2 seconds (2000 ms)
        if (currentTime - lastPartialResultTime < 2000) {
            return
        }
        lastPartialResultTime = currentTime

        Log.d("VoskListener", "onPartialResult (Throttled): $hypothesis")
        val partialText = hypothesis.substringAfter("\"partial\" : \"").substringBefore("\"")
        if (partialText.isNotBlank()) {
            transcribedText.text = fullTranscript.toString() + " " + partialText
        }
    }

    override fun onFinalResult(hypothesis: String) {
        Log.d("VoskListener", "onFinalResult: $hypothesis")
        val resultText = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"")
        if (resultText.isNotBlank()) {
            fullTranscript.append(resultText).append(" ")
            transcribedText.text = fullTranscript.toString()
        }
        // NOTE: We no longer call stopRecording() here to allow continuous speech.
    }

    override fun onError(e: Exception) {
        Log.e("VoskListener", "onError: ", e)
        viewModel.onError(e.message ?: "Unknown recognition error")
    }

    override fun onTimeout() {
        Log.d("VoskListener", "onTimeout")
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
    }
}

