package ru.fieldengineer.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import ru.fieldengineer.app.App
import ru.fieldengineer.app.data.repo.AuthRepository
import java.util.concurrent.TimeUnit

/**
 * Фоновая синхронизация через WorkManager: система запускает её, когда есть сеть, и повторяет
 * с растущей задержкой при ошибке, даже если приложение закрыто.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        val auth = container.authRepository
        if (!auth.isLoggedIn) return Result.success()   // вышли из приложения — обмениваться нечем

        val result = container.syncRepository.sync()

        // изменения, сделанные во время обмена, в пакет не попали, а syncSoon, пока этот обмен
        // идёт, второй не ставит (KEEP) — ставим один обмен-добивку, чтобы они не ждали 15 минут
        val queued = container.database.syncDao().observeQueue().first().total
        if (SyncRules.needsFollowUp(result, queued, isFollowUp = inputData.getBoolean(KEY_FOLLOW_UP, false))) {
            container.syncScheduler.syncFollowUp()
        }

        return when (result) {
            SyncResult.OK -> Result.success()
            SyncResult.NETWORK_ERROR, SyncResult.SERVER_ERROR ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            SyncResult.SESSION_EXPIRED -> {
                // сервер отказал в обновлении токена (срок входа истёк, сотрудника заблокировали) —
                // блокировка сеанса: токенов нет, но неотправленные записи остаются до входа по паролю.
                // Полная очистка — только кнопкой «Выйти» на Э10 или при входе другого сотрудника.
                if (auth.isLoggedIn) auth.lockOut(AuthRepository.SESSION_ENDED)
                Result.failure()
            }
            SyncResult.ERROR -> Result.failure()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        const val KEY_FOLLOW_UP = "follow_up"
    }
}

/** Постановка SyncWorker в очередь WorkManager: периодически, после изменения и по кнопке. */
class SyncScheduler(private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Раз в 15 минут (меньше WorkManager не позволяет) — забрать новые заявки. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * После изменения в заявке. Задержка 5 с собирает несколько быстрых изменений (отметки
     * чек-листа, автосохранение «Что сделано») в один обмен; KEEP — если обмен уже ждёт
     * в очереди, второй не ставим. Изменения, сделанные во время самого обмена, заберёт
     * обмен-добивка (SyncWorker → [syncFollowUp]).
     */
    fun syncSoon() = enqueueOnce(AFTER_CHANGE, delaySeconds = 5)

    /** Кнопка «Синхронизировать сейчас» на Э10. */
    fun syncNow() = enqueueOnce(MANUAL, delaySeconds = 0)

    /**
     * Обмен-добивка после успешного обмена, если за время обмена в очереди появились записи.
     * Отдельное имя: обмен, из которого её ставят, ещё идёт, и под его именем KEEP ничего не поставил бы.
     */
    fun syncFollowUp() = enqueueOnce(FOLLOW_UP, delaySeconds = 0, followUp = true)

    /** При выходе — отменить всё. */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    private fun enqueueOnce(name: String, delaySeconds: Long, followUp: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(needsNetwork)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_FOLLOW_UP to followUp))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val TAG = "sync"
        const val PERIODIC = "sync_periodic"
        const val AFTER_CHANGE = "sync_after_change"
        const val MANUAL = "sync_manual"
        const val FOLLOW_UP = "sync_follow_up"
    }
}
