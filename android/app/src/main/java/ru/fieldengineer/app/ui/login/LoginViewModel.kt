package ru.fieldengineer.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fieldengineer.app.data.repo.AuthRepository
import ru.fieldengineer.app.data.repo.LoginResult

data class LoginUiState(
    val login: String = "",
    val password: String = "",
    val busy: Boolean = false,     // идёт вход и первая загрузка заявок — кнопка недоступна
    val error: String? = null,
    val loggedIn: Boolean = false,
    val notice: String? = null,    // почему вернули на вход (блокировка сеанса)
    /** Текст диалога «удалить неотправленные записи прежнего сотрудника?»; null — диалога нет. */
    val confirmClear: String? = null,
)

/** Э1: вход через сервер; после успешного входа сразу загружаются заявки и справочники. */
class LoginViewModel(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(notice = auth.loginNotice))
    val state: StateFlow<LoginUiState> = _state

    fun onLoginChange(value: String) = _state.update { it.copy(login = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { show(auth.login(s.login, s.password)) }
    }

    /** «Удалить и войти» — записи прежнего сотрудника удаляются, вход продолжается. */
    fun confirmClear() {
        _state.update { it.copy(confirmClear = null, busy = true) }
        viewModelScope.launch { show(auth.confirmClearAndLogin()) }
    }

    /** «Отмена» — ничего не удаляем, остаёмся на Э1. */
    fun cancelClear() {
        auth.cancelLogin()
        _state.update { it.copy(confirmClear = null) }
    }

    private fun show(result: LoginResult) = when (result) {
        is LoginResult.Success -> _state.update { it.copy(busy = false, loggedIn = true, password = "") }
        is LoginResult.Error -> _state.update { it.copy(busy = false, error = result.message) }
        is LoginResult.ConfirmClear -> _state.update { it.copy(busy = false, confirmClear = result.message) }
    }
}
