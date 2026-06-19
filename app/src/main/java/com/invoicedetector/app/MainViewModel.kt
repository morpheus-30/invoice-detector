package com.invoicedetector.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.invoicedetector.sdk.InvoiceDetector
import com.invoicedetector.sdk.model.InvoiceResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    /** UI state surfaced to the Activity. */
    sealed interface UiState {
        data object Idle : UiState
        data object Processing : UiState
        data class Done(val result: InvoiceResult) : UiState
        data class Info(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val detector: InvoiceDetector = InvoiceDetector.create(getApplication())

    fun process(uri: Uri) {
        _state.value = UiState.Processing
        viewModelScope.launch {
            val result = detector.process(uri)
            _state.value = UiState.Done(result)
        }
    }

    fun clearIndex() {
        viewModelScope.launch {
            detector.clearIndex()
            _state.value = UiState.Info(getApplication<Application>().getString(R.string.index_cleared))
        }
    }

    override fun onCleared() {
        detector.close()
        super.onCleared()
    }
}
