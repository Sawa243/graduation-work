package ru.fieldengineer.app.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.fieldengineer.app.AppConfig
import ru.fieldengineer.app.data.db.ActEntity
import ru.fieldengineer.app.data.db.AppDatabase
import ru.fieldengineer.app.data.db.ChecklistResultEntity
import ru.fieldengineer.app.data.db.ChecklistRow
import ru.fieldengineer.app.data.db.PhotoEntity
import ru.fieldengineer.app.data.db.RequestCardRow
import ru.fieldengineer.app.data.db.RequestListRow
import ru.fieldengineer.app.data.db.SyncState
import ru.fieldengineer.app.data.db.WorkLogEntity
import ru.fieldengineer.app.domain.ActBuilder
import ru.fieldengineer.app.domain.ActInput
import ru.fieldengineer.app.domain.BarcodeAnalyzer
import ru.fieldengineer.app.domain.ChecklistCheck
import ru.fieldengineer.app.domain.EquipmentRef
import ru.fieldengineer.app.domain.MOSCOW
import ru.fieldengineer.app.domain.Moment
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.domain.RequestStatusMachine
import ru.fieldengineer.app.domain.ScanMatch
import ru.fieldengineer.app.domain.SlaCalculator
import ru.fieldengineer.app.domain.TransitionResult
import ru.fieldengineer.app.domain.TrustedClock
import ru.fieldengineer.app.domain.WorkCalendar
import ru.fieldengineer.app.domain.WorkInterval
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Заявки инженера и всё, что он по ним делает: статусы, чек-лист, «Что сделано», время.
 * Каждое изменение помечается sync_state = 1, и вызывается [onChanged] — он ставит в очередь
 * фоновую синхронизацию (SyncScheduler.syncSoon), которая отправит изменение на сервер.
 */
class RequestRepository(
    private val db: AppDatabase,
    private val clock: TrustedClock,
    private val onChanged: () -> Unit = {},
) {

    fun observeMyRequests(engineerId: Int): Flow<List<RequestListRow>> =
        db.requestDao().observeForEngineer(engineerId)

    fun observeCard(requestId: Int): Flow<RequestCardRow?> = db.requestDao().observeCard(requestId)

    fun observeChecklist(requestId: Int): Flow<List<ChecklistRow>> = db.requestDao().observeChecklist(requestId)

    fun observeIntervals(requestId: Int): Flow<List<WorkInterval>> =
        db.workDao().observeWorkLog(requestId).map { list -> list.map { WorkInterval(it.kind, it.startedAt, it.endedAt) } }

    fun observeEmployeeIntervals(employeeId: Int, from: Instant, to: Instant): Flow<List<WorkInterval>> =
        db.workDao().observeEmployeeLog(employeeId, from, to)
            .map { list -> list.map { WorkInterval(it.kind, it.startedAt, it.endedAt) } }

    /** Калькулятор сроков по актуальному производственному календарю из базы. */
    fun observeSlaCalculator(): Flow<SlaCalculator> =
        db.nsiDao().observeCalendar().map { days -> SlaCalculator(WorkCalendar(days.associate { it.day to it.isWorking })) }

    /**
     * Смена статуса кнопкой в карточке. Проверки делает RequestStatusMachine, а здесь
     * изменения записываются одной транзакцией: статус, отметки времени, интервалы.
     * Возвращает список причин отказа (пустой — переход выполнен).
     */
    suspend fun changeStatus(requestId: Int, to: Int, engineerId: Int): List<String> = db.withTransaction {
        val request = db.requestDao().request(requestId) ?: return@withTransaction listOf("Заявка не найдена")
        if (request.engineerId != engineerId) return@withTransaction listOf("Заявка назначена другому инженеру")
        val checklist = db.requestDao().checklist(requestId).map {
            ChecklistCheck(it.itemId, it.text, it.required, it.needPhoto, it.done == true, it.photoCount)
        }
        val now = clock.now()
        when (val result = RequestStatusMachine.transition(request.status, to, checklist, request.resultText, now)) {
            is TransitionResult.Denied -> result.reasons
            is TransitionResult.Allowed -> {
                val c = result.changes
                if (c.closeOpenIntervals) {
                    db.workDao().upsertWorkLogs(db.workDao().openIntervals(requestId).map {
                        it.copy(endedAt = c.at.time, timeTrusted = it.timeTrusted && c.at.trusted,
                            updatedAt = c.at.time, syncState = SyncState.CHANGED)
                    })
                }
                c.openIntervalKind?.let { kind ->
                    db.workDao().upsertWorkLogs(listOf(WorkLogEntity(
                        id = UUID.randomUUID().toString(), requestId = requestId, employeeId = engineerId,
                        kind = kind, startedAt = c.at.time, endedAt = null, timeTrusted = c.at.trusted,
                        updatedAt = c.at.time,
                    )))
                }
                db.requestDao().upsertRequests(listOf(request.copy(
                    status = c.newStatus,
                    arrivedAt = if (c.setArrivedAt) c.at.time else request.arrivedAt,
                    completedAt = if (c.setCompletedAt) c.at.time else request.completedAt,
                    timeTrusted = request.timeTrusted && c.at.trusted,
                    updatedAt = c.at.time,
                    syncState = SyncState.CHANGED,
                )))
                onChanged()
                emptyList()
            }
        }
    }

    /** «Что сделано» — только на объекте. */
    suspend fun saveResultText(requestId: Int, text: String) = db.withTransaction {
        val request = db.requestDao().request(requestId) ?: return@withTransaction
        if (!RequestStatusMachine.canEditWork(request.status)) return@withTransaction
        val value = text.trim().ifEmpty { null }
        if (value == request.resultText) return@withTransaction
        db.requestDao().upsertRequests(listOf(request.copy(
            resultText = value, updatedAt = clock.now().time, syncState = SyncState.CHANGED,
        )))
        onChanged()
    }

    /** Отметка пункта чек-листа и комментарий к нему — только на объекте. */
    suspend fun setChecklistMark(requestId: Int, itemId: Int, done: Boolean, comment: String?) = db.withTransaction {
        val request = db.requestDao().request(requestId) ?: return@withTransaction
        if (!RequestStatusMachine.canEditWork(request.status)) return@withTransaction
        val now = clock.now()
        val old = db.workDao().checklistResult(requestId, itemId)
        val doneAt = when {
            !done -> null
            old?.done == true -> old.doneAt      // время первой отметки не переписываем
            else -> now.time
        }
        db.workDao().upsertChecklistResults(listOf(ChecklistResultEntity(
            id = old?.id ?: UUID.randomUUID().toString(),
            requestId = requestId, checklistItemId = itemId, done = done,
            comment = comment?.trim()?.ifEmpty { null }, doneAt = doneAt,
            updatedAt = now.time, syncState = SyncState.CHANGED,
        )))
        if (done && !now.trusted && request.timeTrusted) {
            db.requestDao().upsertRequests(listOf(request.copy(
                timeTrusted = false, updatedAt = now.time, syncState = SyncState.CHANGED,
            )))
        }
        onChanged()
    }

    // ---------------------------------------------------------------- И4: сканер и фото

    fun observePhotos(requestId: Int): Flow<List<PhotoEntity>> = db.workDao().observePhotos(requestId)

    /**
     * Разбор кода и поиск среди оборудования объекта заявки (без записи в базу) —
     * чтобы инженер увидел результат перед сохранением.
     */
    suspend fun checkCode(requestId: Int, raw: String): ScanMatch? {
        val request = db.requestDao().request(requestId) ?: return null
        val code = BarcodeAnalyzer.parse(raw) ?: return null
        val equipment = db.nsiDao().siteEquipment(request.siteId).map {
            EquipmentRef(it.id, it.invCode, it.serialNo, "${it.vendor} ${it.model}")
        }
        return BarcodeAnalyzer.match(code, equipment, request.equipmentId)
    }

    /**
     * Сохранить результат сканирования: код — в scanned_code всегда; если в заявке оборудование
     * не было указано, а на объекте нашлось — привязать его к заявке.
     */
    suspend fun saveScan(requestId: Int, match: ScanMatch): String? = db.withTransaction {
        val request = db.requestDao().request(requestId) ?: return@withTransaction "Заявка не найдена"
        if (!RequestStatusMachine.canEditWork(request.status)) {
            return@withTransaction "Сканировать можно только на объекте"
        }
        val found = (match as? ScanMatch.Found)?.equipment
        db.requestDao().upsertRequests(listOf(request.copy(
            scannedCode = match.code.value,
            equipmentId = request.equipmentId ?: found?.id,
            updatedAt = clock.now().time, syncState = SyncState.CHANGED,
        )))
        onChanged()
        null
    }

    /** Фото работ (kind 1) — на объекте; фото бумажного акта (kind 2) — после «Работы выполнены». */
    suspend fun addPhoto(requestId: Int, checklistItemId: Int?, kind: Int, file: File, sha256: String): String? =
        db.withTransaction {
            val request = db.requestDao().request(requestId) ?: return@withTransaction "Заявка не найдена"
            if (!canEditPhotos(request.status, kind, db.workDao().act(requestId) != null)) {
                return@withTransaction "Сейчас добавлять фото нельзя"
            }
            if (checklistItemId != null && db.requestDao().checklist(requestId).none { it.itemId == checklistItemId }) {
                return@withTransaction "Пункт не из чек-листа этой заявки"
            }
            val now = clock.now()
            db.workDao().upsertPhotos(listOf(PhotoEntity(
                id = UUID.randomUUID().toString(), requestId = requestId, checklistItemId = checklistItemId,
                kind = kind, fileName = file.name, takenAt = now.time, sha256 = sha256, updatedAt = now.time,
            )))
            if (!now.trusted && request.timeTrusted) {
                db.requestDao().upsertRequests(listOf(request.copy(
                    timeTrusted = false, updatedAt = now.time, syncState = SyncState.CHANGED,
                )))
            }
            onChanged()
            null
        }

    /**
     * Удаление фото — всегда мягкое: запись помечается sync_state = 2, экраны и проверки
     * (фото к пункту, фото бумажного акта) её уже не видят, а стирается она после того, как
     * сервер примет удаление. Так и с «неотправленным» фото: оно могло уже дойти до сервера
     * (ответ на push потерялся или отправка шла в этот момент) — удали мы запись сразу, pull
     * вернул бы её «призраком» без файла. На удаление несуществующей записи сервер отвечает
     * accepted. Файл тоже удаляется после ответа сервера: если сервер откажет (заявка уже
     * закрыта), фото вернётся на экран вместе с файлом.
     */
    suspend fun deletePhoto(photoId: String) = db.withTransaction {
        val photo = db.workDao().photo(photoId) ?: return@withTransaction
        val request = db.requestDao().request(photo.requestId) ?: return@withTransaction
        if (!canEditPhotos(request.status, photo.kind, db.workDao().act(photo.requestId) != null)) {
            return@withTransaction
        }
        db.workDao().upsertPhotos(listOf(photo.copy(syncState = SyncState.DELETED, updatedAt = clock.now().time)))
        onChanged()
    }

    /** Фото бумажного акта меняются в статусе 5 и только пока акт не оформлен. */
    fun canEditPhotos(status: Int, kind: Int, actExists: Boolean = false): Boolean =
        if (kind == PHOTO_PAPER_ACT) status == RequestStatus.WORK_DONE && !actExists
        else RequestStatusMachine.canEditWork(status)

    // ---------------------------------------------------------------- И5: акт

    fun observeAct(requestId: Int): Flow<ActEntity?> = db.workDao().observeAct(requestId)

    /** Данные для акта из базы: заявка, выполненные пункты чек-листа, интервалы, инженер. */
    suspend fun actInput(requestId: Int, engineerId: Int, signMode: Int): ActInput? {
        val card = db.requestDao().observeCard(requestId).first() ?: return null
        val r = card.request
        val engineer = db.nsiDao().employeeById(engineerId) ?: return null
        return ActInput(
            requestNumber = r.number,
            actDate = (r.completedAt ?: clock.now().time).atZone(MOSCOW).toLocalDate(),
            executorName = AppConfig.EXECUTOR_NAME,
            customerName = card.customerName, customerInn = card.customerInn,
            contractNumber = card.contractNumber, siteAddress = card.siteAddress,
            workTypeCode = card.workTypeCode, workTypeName = card.workTypeName,
            equipment = card.eqInvCode?.let { "${card.eqVendor} ${card.eqModel}, метка $it, зав. № ${card.eqSerialNo ?: "—"}" },
            resultText = r.resultText,
            doneItems = db.requestDao().checklist(requestId).filter { it.done == true }.map { it.text to it.comment },
            intervals = db.workDao().workLog(requestId).map { WorkInterval(it.kind, it.startedAt, it.endedAt) },
            completedAt = r.completedAt,
            engineerName = engineer.fullName, engineerPosition = engineer.position,
            signMode = signMode,
        )
    }

    suspend fun hasPaperActPhoto(requestId: Int): Boolean = db.workDao().photoCount(requestId, PHOTO_PAPER_ACT) > 0

    /** Сохранить готовый акт (запись act). Заявка остаётся в статусе 5 до «Закрыть заявку». */
    suspend fun saveAct(act: ActEntity): String? = db.withTransaction {
        val card = db.requestDao().observeCard(act.requestId).first() ?: return@withTransaction "Заявка не найдена"
        val request = card.request
        if (request.engineerId != act.engineerId) return@withTransaction "Заявка назначена другому инженеру"
        if (request.status != RequestStatus.WORK_DONE) return@withTransaction "Акт оформляется после «Работы выполнены»"
        if (db.workDao().act(act.requestId) != null) return@withTransaction "Акт по заявке уже оформлен"
        // подпись на экране — только если в договоре есть соглашение о простой ЭП
        if (act.signMode == ActBuilder.SIGN_ON_SCREEN && !card.eSignAllowed) {
            return@withTransaction "По договору акт подписывается только на бумаге"
        }
        db.workDao().upsertActs(listOf(act))
        onChanged()
        null
    }

    suspend fun actExists(requestId: Int): Boolean = db.workDao().act(requestId) != null

    /** «Закрыть заявку»: 5 → 6, closed_at; после этого заявка и все её записи не меняются. */
    suspend fun closeRequest(requestId: Int, engineerId: Int): List<String> = db.withTransaction {
        val request = db.requestDao().request(requestId) ?: return@withTransaction listOf("Заявка не найдена")
        if (request.engineerId != engineerId) return@withTransaction listOf("Заявка назначена другому инженеру")
        val act = db.workDao().act(requestId)
        val actReady = act != null && ActBuilder.isReady(act.signMode, act.signatureFile != null, act.signedAt,
            db.workDao().photoCount(requestId, PHOTO_PAPER_ACT))
        when (val r = RequestStatusMachine.close(request.status, actReady, clock.now())) {
            is TransitionResult.Denied -> r.reasons
            is TransitionResult.Allowed -> {
                db.requestDao().upsertRequests(listOf(request.copy(
                    status = r.changes.newStatus, closedAt = r.changes.at.time,
                    timeTrusted = request.timeTrusted && r.changes.at.trusted,
                    updatedAt = r.changes.at.time, syncState = SyncState.CHANGED,
                )))
                onChanged()
                emptyList()
            }
        }
    }

    /** Доверенное текущее время — для отметок, которые делает экран (подпись, акт). */
    fun now(): Moment = clock.now()

    companion object {
        const val PHOTO_WORK = 1
        const val PHOTO_PAPER_ACT = 2
    }
}
