package ru.fieldengineer.app.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.domain.ScanMatch

data class ScannerUiState(
    val manualCode: String = "",
    val match: ScanMatch? = null,
    val error: String? = null,
    val saved: Boolean = false,
)

class ScannerViewModel(private val requestId: Int, private val repository: RequestRepository) : ViewModel() {

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state

    // камера присылает один и тот же код много раз в секунду — обрабатываем первый
    private var checking = false

    /** Пока результат на экране, камера новые коды не обрабатывает. */
    val paused: Boolean get() = checking || _state.value.match != null

    fun onManualChange(text: String) = _state.update { it.copy(manualCode = text, error = null) }

    /** Код пришёл с камеры (ML Kit) или из поля ручного ввода. */
    fun onCode(raw: String) {
        if (paused) return
        checking = true
        viewModelScope.launch {
            val match = repository.checkCode(requestId, raw)
            _state.update {
                if (match == null) it.copy(error = "Пустой код") else it.copy(match = match, error = null)
            }
            checking = false
        }
    }

    fun rescan() = _state.update { it.copy(match = null, manualCode = "") }

    fun save() {
        val match = _state.value.match ?: return
        viewModelScope.launch {
            val error = repository.saveScan(requestId, match)
            _state.update { if (error == null) it.copy(saved = true) else it.copy(error = error) }
        }
    }
}
