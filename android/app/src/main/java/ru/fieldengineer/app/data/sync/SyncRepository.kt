package ru.fieldengineer.app.data.sync

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import ru.fieldengineer.app.data.db.AppDatabase
import ru.fieldengineer.app.data.db.SyncState
import ru.fieldengineer.app.data.files.FileStore
import ru.fieldengineer.app.data.net.ActDto
import ru.fieldengineer.app.data.net.ApiErrors
import ru.fieldengineer.app.data.net.ApiService
import ru.fieldengineer.app.data.net.ChecklistResultDto
import ru.fieldengineer.app.data.net.PhotoDto
import ru.fieldengineer.app.data.net.PullResponse
import ru.fieldengineer.app.data.net.PushBody
import ru.fieldengineer.app.data.net.RequestPushDto
import ru.fieldengineer.app.data.net.SessionExpiredException
import ru.fieldengineer.app.data.net.WorkLogDto
import ru.fieldengineer.app.data.sync.SyncRules.LocalRecord
import ru.fieldengineer.app.data.sync.SyncRules.LocalRequestInfo
import ru.fieldengineer.app.data.sync.SyncRules.PushAction
import ru.fieldengineer.app.data.sync.SyncRules.SentRecord
import ru.fieldengineer.app.domain.TrustedClock
import java.io.File
import java.io.IOException

/** Итог одного обмена с сервером — по нему SyncWorker решает, повторять ли. */
enum class SyncResult { OK, NETWORK_ERROR, SERVER_ERROR, SESSION_EXPIRED, ERROR }

/** Что показать на экране Э10: идёт ли обмен и чем закончился последний. */
enum class SyncPhase { IDLE, RUNNING, OK, NETWORK_ERROR, ERROR }

data class SyncStatus(val phase: SyncPhase = SyncPhase.IDLE, val message: String? = null)

/**
 * Обмен с сервером (проект системы, раздел 10). Один цикл: файлы → push → pull.
 * Что перезаписывать при слиянии, решают чистые функции SyncRules; здесь — запросы к API
 * и запись в Room одной транзакцией. Одновременно идёт не больше одного обмена (mutex).
 */
class SyncRepository(
    private val db: AppDatabase,
    private val api: ApiService,
    private val files: FileStore,
    private val clock: TrustedClock,
    private val store: SyncStore,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    /** Полный цикл обмена (его запускает SyncWorker). */
    suspend fun sync(): SyncResult = exchange {
        uploadFiles()
        val anyRejected = push()
        // после отказа берём у сервера всё заново: вернётся серверная версия отклонённых записей
        pull(full = anyRejected)
    }

    /**
     * «Отправить повторно» на Э10: отклонённая запись снова ставится в очередь. Нужна, когда
     * причину отказа устранили (например, исправили сервер) — сама по себе отклонённая запись
     * повторно не отправляется, иначе обмен повторял бы её бесконечно. Заявку так не повторяют
     * (SyncRules.canResend). Возвращает true, если запись нашлась и снова ждёт отправки;
     * false — строки уже нет (её заменили данные сервера), запись остаётся в журнале с пометкой.
     */
    suspend fun requeue(record: RejectedRecord): Boolean = withContext(Dispatchers.IO) {
        val dao = db.syncDao()
        val id = record.recordId
        val updated = when (record.table) {
            "checklist_result" -> dao.requeueChecklistResult(id)
            "photo" -> dao.requeuePhoto(id, SyncRules.requeueState(record))
            "work_log" -> dao.requeueWorkLog(id)
            "act" -> dao.requeueAct(id)
            else -> 0
        }
        val found = updated > 0
        store.afterRequeue(record, found)
        found
    }

    /** Сразу после входа: полный pull, чтобы в телефоне появились заявки и справочники. */
    suspend fun pullAfterLogin(): SyncResult = exchange { pull(full = true) }

    /** Очистить всё, что пришло с сервера и создано в телефоне, — при выходе. */
    suspend fun clearLocalData() = mutex.withLock {
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            files.clearAll()
        }
        store.clear()
        clock.reset()
        _status.value = SyncStatus()
    }

    private suspend fun exchange(block: suspend () -> Unit): SyncResult = mutex.withLock {
        _status.value = SyncStatus(SyncPhase.RUNNING)
        val (result, status) = try {
            withContext(Dispatchers.IO) { block() }
            SyncResult.OK to SyncStatus(SyncPhase.OK)
        } catch (e: CancellationException) {
            _status.value = SyncStatus()
            throw e                          // отмену работы не глотаем
        } catch (_: SessionExpiredException) {
            SyncResult.SESSION_EXPIRED to SyncStatus(SyncPhase.ERROR, "Сессия завершена, войдите заново")
        } catch (_: IOException) {
            SyncResult.NETWORK_ERROR to SyncStatus(SyncPhase.NETWORK_ERROR, ApiErrors.NO_CONNECTION)
        } catch (e: HttpException) {
            val text = ApiErrors.problemTitle(e) ?: "Ошибка сервера (код ${e.code()})"
            if (e.code() >= 500 || e.code() == 401) SyncResult.SERVER_ERROR to SyncStatus(SyncPhase.ERROR, text)
            else SyncResult.ERROR to SyncStatus(SyncPhase.ERROR, text)
        } catch (_: SerializationException) {
            SyncResult.ERROR to SyncStatus(SyncPhase.ERROR, "Сервер прислал данные в неожиданном формате")
        } catch (e: Exception) {
            SyncResult.ERROR to SyncStatus(SyncPhase.ERROR, "Ошибка синхронизации: ${e.message}")
        }
        _status.value = status
        result
    }

    // ---------------------------------------------------------------- 1. файлы

    /**
     * Сначала файлы: сервер принимает фото и акт, только если файл уже загружен и его SHA-256
     * совпадает. Загружаются файлы записей, которые ещё не отправлены; повтор — не ошибка.
     */
    private suspend fun uploadFiles() {
        val dao = db.syncDao()
        dao.unsentPhotos().filter { it.syncState == SyncState.CHANGED }
            .forEach { upload("photo", files.photo(it.fileName)) }
        dao.unsentActs().forEach { act ->
            act.signatureFile?.let { upload("signature", files.signature(it)) }
            upload("act", files.act(act.pdfFile))
        }
    }

    private suspend fun upload(kind: String, file: File) {
        if (!file.exists()) return   // файла нет — сервер отклонит запись при push и объяснит почему
        val type = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
        val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody(type.toMediaType()))
        try {
            api.uploadFile(kind, file.name, part)
        } catch (e: HttpException) {
            // файл не принят (4xx) — не останавливаем обмен: отказ по записи придёт в ответе push
            if (e.code() >= 500 || e.code() == 401) throw e
        }
    }

    // ---------------------------------------------------------------- 2. push

    /** Отправить всё, что ждёт отправки. Возвращает true, если сервер что-то отклонил. */
    private suspend fun push(): Boolean {
        val dao = db.syncDao()
        val requests = dao.unsentRequests()
        val checks = dao.unsentChecklistResults()
        val photos = dao.unsentPhotos()
        val logs = dao.unsentWorkLogs()
        val acts = dao.unsentActs()
        val body = PushBody(
            request = requests.map(RequestPushDto::from),
            checklistResult = checks.map(ChecklistResultDto::from),
            photo = photos.map(PhotoDto::from),
            workLog = logs.map(WorkLogDto::from),
            act = acts.map(ActDto::from),
        )
        if (body.isEmpty) return false

        // запоминаем updated_at на момент сборки пакета — по нему поймём, менялась ли запись
        val sent = requests.map { SentRecord("request", it.id.toString(), it.updatedAt) } +
            checks.map { SentRecord("checklist_result", it.id, it.updatedAt) } +
            photos.map { SentRecord("photo", it.id, it.updatedAt, deleted = it.syncState == SyncState.DELETED) } +
            logs.map { SentRecord("work_log", it.id, it.updatedAt) } +
            acts.map { SentRecord("act", it.id, it.updatedAt) }
        // к какой заявке относится запись — для журнала отказов
        val requestOf = requests.associate { it.id.toString() to it.id } + checks.associate { it.id to it.requestId } +
            photos.associate { it.id to it.requestId } + logs.associate { it.id to it.requestId } +
            acts.associate { it.id to it.requestId }
        val photoFiles = photos.associate { it.id to it.fileName }

        val response = api.push(body)
        clock.onServerTime(response.serverTime)
        val result = SyncRules.pushResult(response)

        val rejected = mutableListOf<RejectedRecord>()
        val removedFiles = mutableListOf<String>()
        db.withTransaction {
            val numbers = dao.allRequests().associate { it.id to it.number }
            val now = clock.now().time.toEpochMilli()
            for (s in sent) {
                val decision = SyncRules.decide(s, currentUpdatedAt(s), result)
                when (decision.action) {
                    PushAction.MARK_SYNCED -> markSynced(s)
                    PushAction.REMOVE_LOCAL -> {
                        dao.deletePhotos(listOf(s.id))
                        photoFiles[s.id]?.let(removedFiles::add)
                    }
                    PushAction.KEEP -> Unit
                }
                decision.rejectReason?.let { reason ->
                    rejected += RejectedRecord(s.table, s.id, requestOf[s.id]?.let(numbers::get), reason, now,
                        deleted = s.deleted)
                }
            }
        }
        files.deleteFiles(removedFiles, emptyList(), emptyList())
        store.addRejected(rejected)
        return rejected.isNotEmpty()
    }

    private suspend fun currentUpdatedAt(s: SentRecord) = with(db.syncDao()) {
        when (s.table) {
            "request" -> requestUpdatedAt(s.id.toInt())
            "checklist_result" -> checklistResultUpdatedAt(s.id)
            "photo" -> photoUpdatedAt(s.id)
            "work_log" -> workLogUpdatedAt(s.id)
            else -> actUpdatedAt(s.id)
        }
    }

    private suspend fun markSynced(s: SentRecord) = with(db.syncDao()) {
        when (s.table) {
            "request" -> markRequestSynced(s.id.toInt())
            "checklist_result" -> markChecklistResultSynced(s.id)
            "photo" -> markPhotoSynced(s.id)
            "work_log" -> markWorkLogSynced(s.id)
            else -> markActSynced(s.id)
        }
    }

    // ---------------------------------------------------------------- 3. pull

    private suspend fun pull(full: Boolean) {
        val since = if (full) null else store.since
        // since передаём в UTC («…T13:30:00Z»): знак «+» смещения в адресе запроса
        // сервер прочитал бы как пробел
        var response = api.pull(since?.toString())
        clock.onServerTime(response.serverTime)
        try {
            applyPull(response)
        } catch (e: SQLiteConstraintException) {
            // в ответе «с момента since» заявка сослалась на справочник, которого в телефоне нет
            // (внешний ключ) — транзакция откатилась; берём у сервера всё целиком
            if (since == null) throw e
            response = api.pull(null)
            clock.onServerTime(response.serverTime)
            applyPull(response)
        }
        store.since = response.serverTime
        store.setLastSync(response.serverTime)
    }

    /** Записать ответ pull одной транзакцией; после неё удалить файлы снятых заявок. */
    private suspend fun applyPull(p: PullResponse) {
        val at = p.serverTime
        val nsi = db.nsiDao()
        val dao = db.syncDao()
        val work = db.workDao()
        val removed = db.withTransaction {
            // справочники — «побеждает сервер»; порядок: сначала таблицы, на которые ссылаются
            nsi.upsertCustomers(p.customer.map { it.toEntity(at) })
            nsi.upsertContracts(p.contract.map { it.toEntity(at) })
            nsi.upsertSlaLevels(p.slaLevel.map { it.toEntity(at) })
            nsi.upsertSites(p.site.map { it.toEntity(at) })
            nsi.upsertEquipment(p.equipment.map { it.toEntity(at) })
            nsi.upsertWorkTypes(p.workType.map { it.toEntity(at) })
            nsi.upsertChecklistItems(p.checklistItem.map { it.toEntity(at) })
            nsi.upsertEmployees(p.employee.map { it.toEntity(at) })
            nsi.upsertCalendarDays(p.calendarDay.map { it.toEntity(at) })

            // заявки: неотправленные поля инженера не затираем
            val local = dao.allRequests().associateBy { it.id }
            val merged = p.request.map { SyncRules.mergeRequest(local[it.id], it.toEntity(at)) }
            db.requestDao().upsertRequests(SyncRules.requestsInSaveOrder(merged, local.keys))
            val known = local.keys + p.request.map { it.id }

            // записи заявок: неотправленные локальные не перезаписываем
            val checks = SyncRules.pickServerRecords(
                p.checklistResult.filter { it.requestId in known },
                dao.allChecklistResults().map { LocalRecord(it.id, it.syncState, "${it.requestId}:${it.checklistItemId}") },
                idOf = { it.id }, keyOf = { "${it.requestId}:${it.checklistItemId}" },
            )
            if (checks.replaceLocalIds.isNotEmpty()) dao.deleteChecklistResults(checks.replaceLocalIds)
            work.upsertChecklistResults(checks.save.map { it.toEntity(at) })

            val photos = SyncRules.pickServerRecords(
                p.photo.filter { it.requestId in known && !it.deleted },
                dao.allPhotos().map { LocalRecord(it.id, it.syncState) },
                idOf = { it.id },
            )
            work.upsertPhotos(photos.save.map { it.toEntity(at) })

            val logs = SyncRules.pickServerRecords(
                p.workLog.filter { it.requestId in known },
                dao.allWorkLogs().map { LocalRecord(it.id, it.syncState) },
                idOf = { it.id },
            )
            work.upsertWorkLogs(logs.save.map { it.toEntity(at) })

            val acts = SyncRules.pickServerRecords(
                p.act.filter { it.requestId in known },
                dao.allActs().map { LocalRecord(it.id, it.syncState, it.requestId.toString()) },
                idOf = { it.id }, keyOf = { it.requestId.toString() },
            )
            if (acts.replaceLocalIds.isNotEmpty()) dao.deleteActs(acts.replaceLocalIds)
            work.upsertActs(acts.save.map { it.toEntity(at) })

            p.assignedRequestIds?.let { deleteUnassigned(it.toSet()) }
        }
        removed?.let { files.deleteFiles(it.photos, it.signatures, it.acts) }
    }

    private class RemovedFiles(val photos: List<String>, val signatures: List<String>, val acts: List<String>)

    /**
     * Удалить заявки, которых нет в assigned_request_ids (правило — SyncRules.requestsToDelete),
     * вместе с записями: сначала дочерние таблицы, потом сами заявки — из-за внешних ключей.
     */
    private suspend fun deleteUnassigned(assigned: Set<Int>): RemovedFiles? {
        val dao = db.syncDao()
        val info = dao.requestSyncInfo().map { LocalRequestInfo(it.id, it.status, it.parentId, it.hasUnsent) }
        val ids = SyncRules.requestsToDelete(info, assigned).toList()
        if (ids.isEmpty()) return null
        val photos = dao.photosOf(ids)
        val acts = dao.actsOf(ids)
        dao.deleteChecklistResultsOf(ids)
        dao.deletePhotosOf(ids)
        dao.deleteWorkLogsOf(ids)
        dao.deleteActsOf(ids)
        dao.deleteRequests(ids)
        return RemovedFiles(photos.map { it.fileName }, acts.mapNotNull { it.signatureFile }, acts.map { it.pdfFile })
    }
}
