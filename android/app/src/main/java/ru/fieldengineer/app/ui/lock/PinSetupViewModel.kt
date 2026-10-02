package ru.fieldengineer.app.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fieldengineer.app.data.repo.AuthRepository
import ru.fieldengineer.app.domain.PinSetup

data class PinSetupUiState(
    val step: PinSetup.Step = PinSetup.Step.CREATE,
    val error: String? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
)

/**
 * Шаг «Задайте PIN» сразу после входа по паролю (Э1): PIN вводится дважды. Пока он не задан,
 * к списку заявок не пускаем. Этим PIN снимается блокировка (Э0) и подтверждается подпись акта (Э7).
 */
class PinSetupViewModel(private val auth: AuthRepository) : ViewModel() {

    private val setup = PinSetup()
    private val _state = MutableStateFlow(PinSetupUiState())
    val state: StateFlow<PinSetupUiState> = _state

    fun onPin(pin: String) {
        if (_state.value.busy) return
        when (val result = setup.onPin(pin)) {
            is PinSetup.Result.Error -> _state.update { it.copy(step = setup.step, error = result.message) }
            PinSetup.Result.Confirm -> _state.update { it.copy(step = setup.step, error = null) }
            is PinSetup.Result.Done -> {
                _state.update { it.copy(busy = true, error = null) }
                viewModelScope.launch {
                    auth.createPin(result.pin)
                    _state.update { it.copy(busy = false, done = true) }
                }
            }
        }
    }
}
