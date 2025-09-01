package com.example.whispernotes // Make sure this matches your package name!

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// This sealed interface represents all the possible states of our UI.
sealed interface AppState {
    object Loading : AppState // New state for when the model is unpacking
    object Ready : AppState
    object Recording : AppState
    data class Result(val text: String) : AppState
    data class PartialResult(val text: String) : AppState
    data class Error(val message: String) : AppState
}

class MainViewModel : ViewModel() {

    // It now starts in the Loading state.
    private val _appState = MutableStateFlow<AppState>(AppState.Loading)
    val appState = _appState.asStateFlow()

    fun onResult(text: String) {
        _appState.value = AppState.Result(text)
    }

    fun onPartialResult(text: String) {
        _appState.value = AppState.PartialResult(text)
    }

    fun onError(message: String) {
        _appState.value = AppState.Error(message)
    }

    fun onRecording() {
        _appState.value = AppState.Recording
    }

    // New function to set the state to Loading.
    fun onLoading() {
        _appState.value = AppState.Loading
    }

    fun onReady() {
        _appState.value = AppState.Ready
    }
}

