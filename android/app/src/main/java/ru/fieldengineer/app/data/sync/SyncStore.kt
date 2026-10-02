package ru.fieldengineer.app.data.sync

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import ru.fieldengineer.app.data.net.ApiJson
import java.time.Instant

/**
 * Настройки синхронизации в SharedPreferences: since для следующего pull, время последней
 * успешной синхронизации и журнал отклонённых сервером записей (последние 50, для Э10).
 */
class SyncStore(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val journalSerializer = ListSerializer(RejectedRecord.serializer())

    private val _lastSync = MutableStateFlow(readInstant(KEY_LAST_SYNC))
    private val _journal = MutableStateFlow(readJournal())

    /** Время сервера на момент последней успешной синхронизации. */
    val lastSync: StateFlow<Instant?> = _lastSync
    val journal: StateFlow<List<RejectedRecord>> = _journal

    /** server_time прошлого успешного pull; null — следующий pull полный. */
    var since: Instant?
        get() = readInstant(KEY_SINCE)
        set(value) = prefs.edit { if (value == null) remove(KEY_SINCE) else putLong(KEY_SINCE, value.toEpochMilli()) }

    fun setLastSync(value: Instant) {
        prefs.edit { putLong(KEY_LAST_SYNC, value.toEpochMilli()) }
        _lastSync.value = value
    }

    @Synchronized
    fun addRejected(records: List<RejectedRecord>) {
        if (records.isEmpty()) return
        val list = SyncRules.appendJournal(_journal.value, records)
        prefs.edit { putString(KEY_JOURNAL, ApiJson.encodeToString(journalSerializer, list)) }
        _journal.value = list
    }

    /** После «Отправить повторно»: убрать запись из журнала или пометить «заменена данными сервера». */
    @Synchronized
    fun afterRequeue(record: RejectedRecord, found: Boolean) {
        val list = SyncRules.afterRequeue(_journal.value, record, found)
        prefs.edit { putString(KEY_JOURNAL, ApiJson.encodeToString(journalSerializer, list)) }
        _journal.value = list
    }

    fun clear() {
        prefs.edit { clear() }
        _lastSync.value = null
        _journal.value = emptyList()
    }

    private fun readInstant(key: String): Instant? =
        if (prefs.contains(key)) Instant.ofEpochMilli(prefs.getLong(key, 0)) else null

    // у журнала прежней версии нет полей deleted и replaced — берутся значения по умолчанию
    private fun readJournal(): List<RejectedRecord> = try {
        prefs.getString(KEY_JOURNAL, null)?.let { ApiJson.decodeFromString(journalSerializer, it) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()   // повреждённый журнал не должен мешать работе
    }

    private companion object {
        const val KEY_SINCE = "since"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_JOURNAL = "rejected_journal"
    }
}
