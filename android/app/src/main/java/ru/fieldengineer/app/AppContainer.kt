package ru.fieldengineer.app

import android.content.Context
import ru.fieldengineer.app.data.db.AppDatabase
import ru.fieldengineer.app.data.files.ActPdfWriter
import ru.fieldengineer.app.data.files.FileStore
import ru.fieldengineer.app.data.net.ApiClient
import ru.fieldengineer.app.data.net.TokenStore
import ru.fieldengineer.app.data.repo.AndroidTimeSource
import ru.fieldengineer.app.data.repo.AuthRepository
import ru.fieldengineer.app.data.repo.KeystorePinPepper
import ru.fieldengineer.app.data.repo.PinStore
import ru.fieldengineer.app.data.repo.PrefsPinStorage
import ru.fieldengineer.app.data.repo.PrefsTimeReferenceStore
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.data.repo.SessionStore
import ru.fieldengineer.app.data.sync.SyncRepository
import ru.fieldengineer.app.data.sync.SyncScheduler
import ru.fieldengineer.app.data.sync.SyncStore
import ru.fieldengineer.app.domain.TrustedClock
import ru.fieldengineer.app.ui.lock.AppLockManager

/**
 * Ручной контейнер зависимостей: все общие объекты создаются здесь один раз
 * и передаются экранам через конструкторы ViewModel (без Hilt).
 */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.create(context)
    val sessionStore = SessionStore(context)
    val trustedClock = TrustedClock(AndroidTimeSource(context), PrefsTimeReferenceStore(context))
    val fileStore = FileStore(context)
    // хеш PIN дополнительно защищён ключом Android Keystore («перец»)
    val pinStore = PinStore(PrefsPinStorage(context), KeystorePinPepper())
    val actPdfWriter = ActPdfWriter(fileStore)

    // сеть: адрес сервера зависит от сборки (debug — компьютер разработчика, release — HTTPS)
    val tokenStore = TokenStore(context)
    val apiClient = ApiClient(BuildConfig.API_BASE_URL, tokenStore, onServerTime = trustedClock::onServerTime)

    // синхронизация: каждое изменение заявки ставит в очередь фоновый обмен
    val syncStore = SyncStore(context)
    val syncScheduler = SyncScheduler(context)
    val syncRepository = SyncRepository(database, apiClient.api, fileStore, trustedClock, syncStore)
    val requestRepository = RequestRepository(database, trustedClock, onChanged = syncScheduler::syncSoon)

    val authRepository = AuthRepository(
        apiClient.api, tokenStore, sessionStore, pinStore, database, trustedClock, syncRepository, syncScheduler,
    )

    // блокировка после 5 минут бездействия (Э0)
    val appLockManager = AppLockManager(context, AndroidTimeSource(context), isLoggedIn = { authRepository.isReady })
}
