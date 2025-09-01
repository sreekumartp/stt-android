// In your WhisperLib.kt file

package com.example.whispernotes // Ensure this matches your package name

import android.content.res.AssetManager

/**
 * This object provides the Kotlin bridge to the native C++ whisper.cpp library.
 * It uses the Java Native Interface (JNI) to call the C++ functions.
 */
object WhisperLib {
    // This 'init' block loads the C++ library when this object is first accessed.
    // The library must be named "libwhisper.so" in the jniLibs folder.
    init {
        System.loadLibrary("whisper")
    }

    /**
     * This declares a native function that is implemented in the C++ library.
     * It's responsible for loading the Whisper AI model from the assets folder.
     *
     * @param assetManager The Android AssetManager to access the model file.
     * @param modelPath The path to the model file within the assets folder.
     * @return A pointer to the loaded model context, or 0 if it fails.
     */
    @JvmStatic
    external fun initContextFromAsset(assetManager: AssetManager, modelPath: String): Long

    /**
     * This declares the native function for transcribing audio data.
     *
     * @param contextPtr The pointer to the model context (from initContextFromAsset).
     * @param audioData An array of 16-bit PCM audio samples.
     * @return The transcribed text as a String.
     */
    @JvmStatic
    external fun transcribeData(contextPtr: Long, audioData: FloatArray): String

    /**
     * This declares a native function to free the memory used by the model context.
     * It's important to call this when the model is no longer needed.
     *
     * @param contextPtr The pointer to the model context to be released.
     */
    @JvmStatic
    external fun freeContext(contextPtr: Long)
}