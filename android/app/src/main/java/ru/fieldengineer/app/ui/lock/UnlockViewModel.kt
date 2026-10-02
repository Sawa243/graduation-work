package ru.fieldengineer.app.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.fieldengineer.app.data.repo.AuthRepository
import ru.fieldengineer.app.data.repo.PinStore

data class UnlockUiState(
    val error: String? = null,
    /** Идёт проверка — кнопки недоступны (сначала проверяем, задан ли PIN). */
    val busy: Boolean = true,
    /** Один раз после появления Э0 сразу предложить отпечаток. */
    val offerBiometric: Boolean = false,
)

/**
 * Э0 «Разблокировка»: PIN инженера (тот же, что для подписи акта) или отпечаток.
 * 5 неверных PIN — блокировка сеанса: вход заново по паролю, неотправленные данные сохраняются.
 * Новый PIN здесь не задаётся: иначе блокировку снял бы любой, у кого в руках телефон.
 * PIN задаётся только после входа по паролю.
 */
class UnlockViewModel(
    private val auth: AuthRepository,
    private val pins: PinStore,
    private val lock: AppLockManager,
) : ViewModel() {

    private val _state = MutableStateFlow(UnlockUiState())
    val state: StateFlow<UnlockUiState> = _state

    /** Э0 появился: PIN заблокирован или его нет — сразу на вход по паролю. */
    fun start() {
        val id = auth.currentEmployeeId ?: return
        _state.value = UnlockUiState(busy = true)
        viewModelScope.launch {
            // hasPin может обратиться к Android Keystore — не на главном потоке
            val hasPin = withContext(Dispatchers.Default) { pins.hasPin(id) }
            when {
                pins.isLocked(id) -> lockOut(PIN_LOCKED)
                // PIN пропал (например, система удалила ключ Keystore) — только вход по паролю
                !hasPin -> lockOut(NO_PIN)
                else -> _state.value = UnlockUiState(busy = false, offerBiometric = true)
            }
        }
    }

    fun biometricOffered() = _state.update { it.copy(offerBiometric = false) }

    /** Отпечаток подтверждён системой (BiometricPrompt). */
    fun onBiometricSuccess() = unlock()

    fun onPin(pin: String) {
        val id = auth.currentEmployeeId ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { pins.verify(id, pin) }
            when {
                ok -> unlock()
                pins.isLocked(id) -> lockOut(PIN_LOCKED)
                else -> _state.update {
                    it.copy(busy = false, error = "Неверный PIN. Осталось попыток: ${pins.attemptsLeft(id)}")
                }
            }
        }
    }

    /** «Войти по паролю» — например, инженер забыл PIN: после входа он задаст новый. */
    fun loginWithPassword() {
        viewModelScope.launch { lockOut(BY_PASSWORD) }
    }

    private fun unlock() {
        _state.value = UnlockUiState()
        lock.unlock()
    }

    /** После блокировки сеанса навигация сама откроет Э1 (AuthRepository.loggedIn = false). */
    private suspend fun lockOut(notice: String) {
        _state.update { it.copy(busy = true) }
        auth.lockOut(notice)
        _state.value = UnlockUiState()
    }

    private companion object {
        const val KEPT = "${AuthRepository.KEPT} Вход другим сотрудником их удалит."
        const val PIN_LOCKED = "PIN введён неверно ${PinStore.MAX_FAILS} раз. Войдите по паролю и задайте новый PIN. $KEPT"
        const val NO_PIN = "PIN в телефоне не найден. Войдите по паролю и задайте новый PIN. $KEPT"
        const val BY_PASSWORD = "Войдите по паролю, после входа задайте новый PIN. $KEPT"
    }
}
