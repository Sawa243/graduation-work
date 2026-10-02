package ru.fieldengineer.app.data.sync

import kotlinx.serialization.Serializable
import ru.fieldengineer.app.data.db.RequestEntity
import ru.fieldengineer.app.data.db.SyncState
import ru.fieldengineer.app.data.net.PushResponse
import ru.fieldengineer.app.domain.RequestStatus
import java.time.Instant

/**
 * Правила слияния данных телефона и сервера (проект системы, разделы 1 и 10).
 * Чистые функции без Android и базы данных — поэтому проверяются модульными тестами.
 * Сами записи в Room делает SyncRepository.
 */
object SyncRules {

    // ---------------------------------------------------------------- pull: заявки

    /**
     * Заявка с сервера и её локальная копия. Если локальных изменений нет — сервер прав во всём.
     * Если инженер что-то изменил и ещё не отправил (sync_state = 1), с сервера берётся всё,
     * кроме полей, которые меняет инженер («побеждает устройство»): они уйдут следующим push.
     */
    fun mergeRequest(local: RequestEntity?, server: RequestEntity): RequestEntity =
        if (local == null || local.syncState == SyncState.SYNCED) server
        else server.copy(
            status = local.status,
            arrivedAt = local.arrivedAt,
            completedAt = local.completedAt,
            closedAt = local.closedAt,
            scannedCode = local.scannedCode,
            resultText = local.resultText,
            equipmentId = local.equipmentId,
            timeTrusted = local.timeTrusted,
            // updated_at локальный: по нему push поймёт, менялась ли заявка после отправки
            updatedAt = local.updatedAt,
            syncState = local.syncState,
        )

    /**
     * Порядок записи заявок из pull. Исходная заявка повторного выезда (parent_id) может быть
     * чужой — тогда её нет ни в телефоне, ни в ответе, и внешний ключ не даст сохранить заявку;
     * такую ссылку обнуляем. Исходная заявка создана раньше повторной (id меньше), поэтому
     * сортировка по id записывает сначала исходные.
     */
    fun requestsInSaveOrder(requests: List<RequestEntity>, localIds: Set<Int>): List<RequestEntity> {
        val known = localIds + requests.map { it.id }
        return requests
            .map { if (it.parentId != null && it.parentId !in known) it.copy(parentId = null) else it }
            .sortedBy { it.id }
    }

    /** Что известно о локальной заявке для решения «удалять ли её». */
    data class LocalRequestInfo(val id: Int, val status: Int, val parentId: Int?, val hasUnsent: Boolean)

    /**
     * Статусы, в которых заявка удаляется из телефона, если её нет в assigned_request_ids
     * (переназначена или отменена до выезда). Заявки в статусах 5–8 остаются: это результат
     * работы инженера (5 — работы выполнены, 6 — закрыта, 7 — отменена, 8 — приостановлена);
     * они нужны на вкладке «Закрытые» и в показателях Э9, а в списке «незакрытых назначенных»
     * сервер их не обязан присылать.
     */
    private val REMOVABLE = setOf(RequestStatus.NEW, RequestStatus.ASSIGNED, RequestStatus.ON_THE_WAY, RequestStatus.ON_SITE)

    /**
     * id заявок, которые надо удалить из телефона вместе с их записями. На исходную заявку
     * ссылается оставшаяся повторная — удалять её нельзя (parent_id). Исключение повторяем,
     * пока набор меняется: в цепочке A ← B ← C, где остаётся C, сохраняется B, а значит и A.
     */
    fun requestsToDelete(local: List<LocalRequestInfo>, assigned: Set<Int>): Set<Int> {
        var removable = local
            .filter { it.id !in assigned && it.status in REMOVABLE && !it.hasUnsent }
            .map { it.id }
            .toSet()
        while (true) {
            val parentsOfKept = local.filter { it.id !in removable }.mapNotNull { it.parentId }.toSet()
            val next = removable - parentsOfKept
            if (next == removable) return removable
            removable = next
        }
    }

    // ---------------------------------------------------------------- pull: записи заявок

    /** Локальная запись отметки, фото, интервала или акта: id, признак отправки и уникальный ключ. */
    data class LocalRecord(val id: String, val syncState: Int, val uniqueKey: String? = null)

    /** Какие записи сервера сохранить и какие локальные записи они заменяют (удалить до сохранения). */
    data class Picked<T>(val save: List<T>, val replaceLocalIds: List<String>)

    /**
     * Записи заявок из pull: результаты работ — «побеждает устройство». Запись сервера
     * сохраняется, если в телефоне её нет или локальная копия уже отправлена (sync_state = 0).
     * Неотправленную локальную запись не трогаем. Кроме id сравнивается уникальный ключ
     * (у отметки — заявка + пункт, у акта — заявка): одна отметка по пункту может прийти
     * с другим id, и тогда синхронизированная локальная копия заменяется серверной.
     */
    fun <T> pickServerRecords(
        server: List<T>,
        local: List<LocalRecord>,
        idOf: (T) -> String,
        keyOf: (T) -> String? = { null },
    ): Picked<T> {
        val byId = local.associateBy { it.id }
        val byKey = local.filter { it.uniqueKey != null }.associateBy { it.uniqueKey }
        val save = mutableListOf<T>()
        val replace = mutableListOf<String>()
        for (record in server) {
            val mine = byId[idOf(record)] ?: keyOf(record)?.let { byKey[it] }
            if (mine != null && mine.syncState != SyncState.SYNCED) continue
            if (mine != null && mine.id != idOf(record)) replace += mine.id
            save += record
        }
        return Picked(save, replace)
    }

    // ---------------------------------------------------------------- push

    /** Запись, отправленная в push: таблица, id и updated_at на момент сборки пакета. */
    data class SentRecord(val table: String, val id: String, val updatedAt: Instant, val deleted: Boolean = false)

    enum class PushAction {
        /** sync_state = 0 — запись на сервере. */
        MARK_SYNCED,
        /** Удалённое фото принято сервером — строку можно стереть и в телефоне. */
        REMOVE_LOCAL,
        /** Оставить как есть: сервер не ответил по записи или её изменили после отправки. */
        KEEP,
    }

    data class PushDecision(val action: PushAction, val rejectReason: String? = null)

    /** Ответ push в удобном виде: ключи «таблица:id» принятых записей и причины отказов. */
    data class PushResult(val accepted: Set<String>, val rejected: Map<String, String>)

    fun key(table: String, id: String) = "$table:$id"

    fun pushResult(response: PushResponse) = PushResult(
        accepted = response.accepted.flatMap { (table, ids) -> ids.map { key(table, it.content) } }.toSet(),
        rejected = response.rejected.associate { key(it.table, it.id.content) to it.reason },
    )

    /**
     * Что сделать с отправленной записью после ответа сервера. [currentUpdatedAt] — updated_at
     * записи в телефоне сейчас (null — записи уже нет). Если инженер изменил запись, пока шёл
     * обмен, отметку «ждёт отправки» не снимаем: новая версия уйдёт следующим push.
     * Отклонённая запись помечается отправленной, чтобы не повторять её бесконечно, а причина
     * попадает в журнал Э10.
     */
    fun decide(sent: SentRecord, currentUpdatedAt: Instant?, result: PushResult): PushDecision {
        val k = key(sent.table, sent.id)
        val reason = result.rejected[k]
        val unchanged = currentUpdatedAt != null && currentUpdatedAt == sent.updatedAt
        return when {
            k in result.accepted && unchanged ->
                PushDecision(if (sent.deleted) PushAction.REMOVE_LOCAL else PushAction.MARK_SYNCED)
            reason != null -> PushDecision(if (unchanged) PushAction.MARK_SYNCED else PushAction.KEEP, reason)
            else -> PushDecision(PushAction.KEEP)
        }
    }

    /**
     * Нужен ли обмен-добивка: обмен прошёл успешно, а в очереди остались записи — их изменили,
     * пока шёл обмен. После ошибки не ставим (SyncWorker и так повторит с задержкой); сама
     * добивка следующую не ставит — бесконечного цикла нет, даже если запись застряла.
     */
    fun needsFollowUp(result: SyncResult, queued: Int, isFollowUp: Boolean): Boolean =
        result == SyncResult.OK && queued > 0 && !isFollowUp

    // ---------------------------------------------------------------- журнал отклонённых (Э10)

    const val JOURNAL_SIZE = 50

    /** Новые записи — сверху; храним последние [max]. */
    fun appendJournal(old: List<RejectedRecord>, added: List<RejectedRecord>, max: Int = JOURNAL_SIZE) =
        (added + old).take(max)

    /**
     * Можно ли отправить запись повторно кнопкой. Заявку — нет: после отказа pull вернул её
     * серверную версию (статус, который знает сервер), и инженер повторяет шаг в карточке.
     */
    fun canResend(record: RejectedRecord): Boolean = record.table != "request" && !record.replaced

    /** sync_state, с которым отклонённая запись снова встаёт в очередь: удаление фото — 2, остальное — 1. */
    fun requeueState(record: RejectedRecord): Int =
        if (record.table == "photo" && record.deleted) SyncState.DELETED else SyncState.CHANGED

    /**
     * Журнал после «Отправить повторно»: запись нашлась и снова в очереди — убираем её
     * (при новом отказе она вернётся); строки уже нет — её заменили данные сервера,
     * запись остаётся с пометкой.
     */
    fun afterRequeue(journal: List<RejectedRecord>, record: RejectedRecord, found: Boolean): List<RejectedRecord> {
        fun same(r: RejectedRecord) = r.table == record.table && r.recordId == record.recordId
        return if (found) journal.filterNot(::same) else journal.map { if (same(it)) it.copy(replaced = true) else it }
    }

    fun tableTitle(table: String): String = when (table) {
        "request" -> "Заявка"
        "checklist_result" -> "Отметка чек-листа"
        "photo" -> "Фото"
        "work_log" -> "Интервал работы"
        "act" -> "Акт"
        else -> table
    }
}

/**
 * Запись, которую сервер отклонил при push: показывается в журнале на экране Э10.
 * [deleted] — отклонено удаление фото; [replaced] — при повторе строки уже не было (её
 * заменили данные сервера). У журнала, сохранённого прежней версией, этих полей нет — false.
 */
@Serializable
data class RejectedRecord(
    val table: String,
    val recordId: String,
    val requestNumber: String?,
    val reason: String,
    val atMs: Long,
    val deleted: Boolean = false,
    val replaced: Boolean = false,
)
