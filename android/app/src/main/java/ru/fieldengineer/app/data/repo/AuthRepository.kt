package ru.fieldengineer.app.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import ru.fieldengineer.app.data.db.AppDatabase
import ru.fieldengineer.app.data.db.EmployeeEntity
import ru.fieldengineer.app.data.net.ApiErrors
import ru.fieldengineer.app.data.net.ApiService
import ru.fieldengineer.app.data.net.LoginBody
import ru.fieldengineer.app.data.net.LoginResponse
import ru.fieldengineer.app.data.net.TokenStore
import ru.fieldengineer.app.data.sync.SyncRepository
import ru.fieldengineer.app.data.sync.SyncResult
import ru.fieldengineer.app.data.sync.SyncScheduler
import ru.fieldengineer.app.domain.SessionRules
import ru.fieldengineer.app.domain.TrustedClock
import java.io.IOException

sealed interface LoginResult {
    data class Success(val employeeId: Int) : LoginResult
    data class Error(val message: String) : LoginResult
    /** В телефоне неотправленные записи другого сотрудника: вход — только после подтверждения. */
    data class ConfirmClear(val message: String) : LoginResult
}

/**
 * Вход и выход инженера. Пароль проверяет сервер (POST /api/auth/login), в телефоне остаются
 * только токены — зашифрованными ключом Android Keystore (TokenStore).
 * После каждого входа по паролю PIN задаётся заново (шаг после Э1); пока он не задан,
 * в приложение дальше не пускаем ([pinRequired]).
 */
class AuthRepository(
    private val api: ApiService,
    private val tokens: TokenStore,
    private val session: SessionStore,
    private val pins: PinStore,
    private val db: AppDatabase,
    private val clock: TrustedClock,
    private val sync: SyncRepository,
    private val scheduler: SyncScheduler,
) {
    // вошёл — если есть расшифровываемые токены и известен сотрудник
    private val _loggedIn = MutableStateFlow(tokens.hasTokens && session.employeeId != null)
    private val _pinRequired = MutableStateFlow(session.pinRequired)

    /** Экран навигации следит за этим признаком: стал false — переход на Э1. */
    val loggedIn: StateFlow<Boolean> = _loggedIn
    val isLoggedIn: Boolean get() = _loggedIn.value
    val currentEmployeeId: Int? get() = if (_loggedIn.value) session.employeeId else null

    /** true — вход по паролю выполнен, но PIN ещё не задан. */
    val pinRequired: StateFlow<Boolean> = _pinRequired
    val isPinRequired: Boolean get() = _pinRequired.value

    /** Вошёл и задал PIN — можно работать; только тогда действует блокировка Э0. */
    val isReady: Boolean get() = _loggedIn.value && !_pinRequired.value

    /** Почему инженера вернули на Э1 (null — обычный вход). Хранится до успешного входа. */
    val loginNotice: String? get() = session.notice

    /** Ответ сервера на вход, который ждёт подтверждения удаления чужих записей. */
    @Volatile private var pending: LoginResponse? = null

    suspend fun currentEmployee(): EmployeeEntity? = currentEmployeeId?.let { db.nsiDao().employeeById(it) }

    suspend fun login(login: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        pending = null
        val name = login.trim().lowercase()
        if (name.isEmpty() || password.isEmpty()) return@withContext LoginResult.Error("Введите логин и пароль")

        val answer = try {
            api.login(LoginBody(name, password))
        } catch (e: HttpException) {
            return@withContext LoginResult.Error(ApiErrors.loginMessage(e.code(), ApiErrors.problemTitle(e)))
        } catch (_: IOException) {
            return@withContext LoginResult.Error(ApiErrors.NO_CONNECTION)
        } catch (_: SerializationException) {
            return@withContext LoginResult.Error("Сервер прислал ответ в неожиданном формате")
        }
        val employee = answer.employee
        // сервер и сам не пускает в мобильный API не инженеров (403); проверка на всякий случай
        if (employee.blocked) return@withContext LoginResult.Error(ApiErrors.BLOCKED)
        if (employee.role != ROLE_ENGINEER) return@withContext LoginResult.Error(ApiErrors.NOT_ENGINEER)

        val previous = session.employeeId
        val unsent = db.syncDao().observeQueue().first().total
        when (SessionRules.beforeLogin(previous, employee.id, unsent)) {
            SessionRules.LocalData.KEEP -> finishLogin(answer, clear = false, freshAnswer = true)
            SessionRules.LocalData.CLEAR -> finishLogin(answer, clear = true, freshAnswer = true)
            SessionRules.LocalData.ASK -> {
                pending = answer
                val owner = previous?.let { db.nsiDao().employeeById(it)?.fullName } ?: "другого сотрудника"
                LoginResult.ConfirmClear(
                    "В телефоне не отправлены на сервер записи сотрудника $owner: $unsent. " +
                        "При входе под другим пользователем они будут удалены без возможности восстановления.",
                )
            }
        }
    }

    /** «Удалить и войти» в диалоге Э1: очистить данные прежнего сотрудника и закончить вход. */
    suspend fun confirmClearAndLogin(): LoginResult = withContext(Dispatchers.IO) {
        val answer = pending ?: return@withContext LoginResult.Error("Войдите заново")
        pending = null
        finishLogin(answer, clear = true, freshAnswer = false)
    }

    /** «Отмена» в диалоге Э1: ответ сервера забываем, данные прежнего сотрудника остаются. */
    fun cancelLogin() {
        pending = null
    }

    private suspend fun finishLogin(answer: LoginResponse, clear: Boolean, freshAnswer: Boolean): LoginResult {
        val id = answer.employee.id
        // в телефоне данные другого (или неизвестного) сотрудника — очищаем, чтобы не смешать
        if (clear) sync.clearLocalData()

        // старый PIN удаляется, новый инженер задаст на следующем шаге — до списка заявок;
        // признак ставится раньше токенов: прерванный вход не пустит в приложение без PIN
        session.pinRequired = true
        _pinRequired.value = true
        pins.removePin(id)

        tokens.save(answer.accessToken, answer.refreshToken)
        session.employeeId = id
        session.notice = null
        // время из ответа, пролежавшего в памяти, пока инженер читал диалог, устарело —
        // опорную пару тогда задаст pull ниже
        if (freshAnswer) clock.onServerTime(answer.serverTime)

        // сразу полный pull: заявки и справочники; без связи список догрузит фоновая синхронизация
        val pulled = sync.pullAfterLogin()
        _loggedIn.value = true
        scheduler.schedulePeriodic()
        if (pulled != SyncResult.OK) scheduler.syncNow()
        return LoginResult.Success(id)
    }

    /** Шаг «Задайте PIN»: PIN сохранён — можно к списку заявок. */
    suspend fun createPin(pin: String) = withContext(Dispatchers.Default) {
        val id = session.employeeId ?: return@withContext
        pins.setPin(id, pin)   // HMAC ключом Keystore — не на главном потоке
        session.pinRequired = false
        _pinRequired.value = false
    }

    /**
     * Выход кнопкой на Э10: отменить фоновые обмены, очистить базу и файлы, забыть опорную
     * пару времени и since, удалить токены. Сначала данные, потом сессия: если выход прервётся
     * (приложение закрыли), сессия ещё указывает на владельца данных — они не станут «ничьими».
     * Выполняется до конца, даже если экран закрыли (NonCancellable).
     */
    suspend fun logout() = withContext(NonCancellable + Dispatchers.IO) {
        pending = null
        _loggedIn.value = false   // навигация сразу уходит на Э1, SyncWorker не начнёт новый обмен
        scheduler.cancelAll()
        sync.clearLocalData()
        tokens.clear()
        session.employeeId = null
        session.pinRequired = false
        _pinRequired.value = false
    }

    /**
     * Блокировка сеанса (5 неверных PIN, «Войти по паролю» на Э0, сервер отказал в обновлении
     * токена): токены удаляются, фоновый обмен останавливается, но база и файлы остаются.
     * Сотрудник запоминается — после его входа по паролю неотправленные записи уйдут на сервер
     * (перед входом другого сотрудника Э1 спросит, удалять ли их). Пока пароль не введён,
     * данные с сервера не читаются и не пишутся.
     */
    suspend fun lockOut(notice: String) = withContext(NonCancellable + Dispatchers.IO) {
        session.notice = notice
        // признак снимаем первым: SyncWorker, получивший отказ без токена, ничего не станет делать
        _loggedIn.value = false
        scheduler.cancelAll()
        tokens.clear()
    }

    companion object {
        private const val ROLE_ENGINEER = 1

        const val KEPT = "Неотправленные данные сохранены в телефоне и уйдут на сервер после вашего входа."

        /** Сервер отказал в обновлении токена: срок входа истёк или сотрудника заблокировали. */
        const val SESSION_ENDED = "Сеанс завершён сервером: срок входа истёк или доступ закрыт. " +
            "Войдите по паролю. $KEPT"
    }
}
