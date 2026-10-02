package ru.fieldengineer.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.fieldengineer.app.data.db.QueueCounts
import ru.fieldengineer.app.data.repo.AuthRepository
import ru.fieldengineer.app.data.sync.RejectedRecord
import ru.fieldengineer.app.data.sync.SyncRepository
import ru.fieldengineer.app.data.sync.SyncScheduler
import ru.fieldengineer.app.data.sync.SyncStatus
import ru.fieldengineer.app.data.sync.SyncStore
import java.time.Instant

data class SyncUiState(
    val lastSync: Instant? = null,
    val queue: QueueCounts = QueueCounts(0, 0, 0, 0, 0),
    val status: SyncStatus = SyncStatus(),
    val journal: List<RejectedRecord> = emptyList(),
)

/**
 * Э10 «Синхронизация и настройки»: когда последний раз обменивались с сервером, что ждёт
 * отправки, чем закончился обмен, какие записи сервер отклонил; «Синхронизировать» и «Выйти».
 */
class SyncViewModel(
    private val auth: AuthRepository,
    private val scheduler: SyncScheduler,
    private val sync: SyncRepository,
    store: SyncStore,
    private val queue: Flow<QueueCounts>,
) : ViewModel() {

    val state: StateFlow<SyncUiState> =
        combine(store.lastSync, queue, sync.status, store.journal) { last, q, status, journal ->
            SyncUiState(last, q, status, journal)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

    /** Показать предупреждение перед выходом: есть неотправленные записи. */
    val confirmLogout = MutableStateFlow(false)

    /** Обмен запускает WorkManager — он дождётся сети, если её сейчас нет. */
    fun syncNow() = scheduler.syncNow()

    /**
     * Отклонённая запись снова ставится в очередь, и сразу запускается обмен. Если строки
     * уже нет (её заменили данные сервера), запись остаётся в журнале с пометкой.
     */
    fun resend(record: RejectedRecord) {
        viewModelScope.launch {
            if (sync.requeue(record)) scheduler.syncNow()
        }
    }

    fun onLogoutClick() {
        // очередь читаем из базы в момент нажатия, а не из экрана, — так точнее
        viewModelScope.launch { if (queue.first().total > 0) confirmLogout.value = true else logout() }
    }

    fun dismissLogout() {
        confirmLogout.value = false
    }

    /** После выхода AuthRepository.loggedIn станет false — навигация сама откроет Э1. */
    fun logout() {
        confirmLogout.value = false
        viewModelScope.launch { auth.logout() }
    }
}
