@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class)

package ru.fieldengineer.app.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonPrimitive
import ru.fieldengineer.app.data.db.ActEntity
import ru.fieldengineer.app.data.db.CalendarDayEntity
import ru.fieldengineer.app.data.db.ChecklistItemEntity
import ru.fieldengineer.app.data.db.ChecklistResultEntity
import ru.fieldengineer.app.data.db.ContractEntity
import ru.fieldengineer.app.data.db.CustomerEntity
import ru.fieldengineer.app.data.db.EmployeeEntity
import ru.fieldengineer.app.data.db.EquipmentEntity
import ru.fieldengineer.app.data.db.PhotoEntity
import ru.fieldengineer.app.data.db.RequestEntity
import ru.fieldengineer.app.data.db.SiteEntity
import ru.fieldengineer.app.data.db.SlaLevelEntity
import ru.fieldengineer.app.data.db.SyncState
import ru.fieldengineer.app.data.db.WorkLogEntity
import ru.fieldengineer.app.data.db.WorkTypeEntity
import java.time.Instant
import java.time.LocalDate

// Структуры запросов и ответов API «Сервисного пульта» (проект системы, раздел 10).
// Поля названы как столбцы таблиц. Записи, полученные с сервера, сохраняются в Room
// с sync_state = 0, а updated_at у них — время сервера на момент получения.

// ---------------------------------------------------------------- вход

@Serializable
data class LoginBody(val login: String, val password: String)

@Serializable
data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: Instant? = null,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: Instant? = null,
    val employee: EmployeeDto,
    @SerialName("server_time") val serverTime: Instant,
)

/** Ответ на обновление токена: новая пара токенов и время сервера. */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: Instant? = null,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: Instant? = null,
    @SerialName("server_time") val serverTime: Instant,
)

/** Ошибка в формате ProblemDetails (RFC 9457): в title — понятное сообщение по-русски. */
@Serializable
data class ProblemDetails(val title: String? = null, val status: Int? = null, val detail: String? = null)

// ---------------------------------------------------------------- синхронизация

@Serializable
data class PullResponse(
    @SerialName("server_time") val serverTime: Instant,
    val customer: List<CustomerDto> = emptyList(),
    val contract: List<ContractDto> = emptyList(),
    @SerialName("sla_level") val slaLevel: List<SlaLevelDto> = emptyList(),
    val site: List<SiteDto> = emptyList(),
    val equipment: List<EquipmentDto> = emptyList(),
    @SerialName("work_type") val workType: List<WorkTypeDto> = emptyList(),
    @SerialName("checklist_item") val checklistItem: List<ChecklistItemDto> = emptyList(),
    val employee: List<EmployeeDto> = emptyList(),
    @SerialName("calendar_day") val calendarDay: List<CalendarDayDto> = emptyList(),
    val request: List<RequestDto> = emptyList(),
    @SerialName("checklist_result") val checklistResult: List<ChecklistResultDto> = emptyList(),
    val photo: List<PhotoDto> = emptyList(),
    @SerialName("work_log") val workLog: List<WorkLogDto> = emptyList(),
    val act: List<ActDto> = emptyList(),
    /** Все незакрытые заявки инженера. null — сервер список не прислал, тогда ничего не удаляем. */
    @SerialName("assigned_request_ids") val assignedRequestIds: List<Int>? = null,
)

@Serializable
data class PushBody(
    val request: List<RequestPushDto>,
    @SerialName("checklist_result") val checklistResult: List<ChecklistResultDto>,
    val photo: List<PhotoDto>,
    @SerialName("work_log") val workLog: List<WorkLogDto>,
    val act: List<ActDto>,
) {
    val isEmpty: Boolean
        get() = request.isEmpty() && checklistResult.isEmpty() && photo.isEmpty() && workLog.isEmpty() && act.isEmpty()
}

/**
 * Ответ на push. id заявки — число, у остальных таблиц — UUID строкой,
 * поэтому id читаются как JSON-примитив и дальше сравниваются как строки.
 */
@Serializable
data class PushResponse(
    @SerialName("server_time") val serverTime: Instant,
    val accepted: Map<String, List<JsonPrimitive>> = emptyMap(),
    val rejected: List<RejectedDto> = emptyList(),
)

@Serializable
data class RejectedDto(val table: String, val id: JsonPrimitive, val reason: String)

@Serializable
data class FileUploadResponse(val name: String, val sha256: String, val size: Long)

// ---------------------------------------------------------------- справочники

@Serializable
data class CustomerDto(val id: Int, val code: String, val name: String, val inn: String) {
    fun toEntity(at: Instant) = CustomerEntity(id, code, name, inn, at)
}

@Serializable
data class ContractDto(
    val id: Int, val number: String,
    @SerialName("customer_id") val customerId: Int,
    @SerialName("date_from") val dateFrom: LocalDate,
    @SerialName("date_to") val dateTo: LocalDate? = null,
    @SerialName("max_sla") val maxSla: Int,
    @SerialName("e_sign_allowed") val eSignAllowed: Boolean,
) {
    fun toEntity(at: Instant) = ContractEntity(id, number, customerId, dateFrom, dateTo, maxSla, eSignAllowed, at)
}

@Serializable
data class SlaLevelDto(
    val id: Int, val code: String, val name: String,
    @SerialName("reaction_min") val reactionMin: Int,
    @SerialName("resolution_min") val resolutionMin: Int,
    val mode: Int,
) {
    fun toEntity(at: Instant) = SlaLevelEntity(id, code, name, reactionMin, resolutionMin, mode, at)
}

@Serializable
data class SiteDto(
    val id: Int, val code: String,
    @SerialName("customer_id") val customerId: Int,
    val address: String,
    @SerialName("contact_name") val contactName: String? = null,
    @SerialName("contact_phone") val contactPhone: String? = null,
) {
    fun toEntity(at: Instant) = SiteEntity(id, code, customerId, address, contactName, contactPhone, at)
}

@Serializable
data class EquipmentDto(
    val id: Int,
    @SerialName("inv_code") val invCode: String,
    @SerialName("site_id") val siteId: Int,
    val vendor: String, val model: String,
    @SerialName("serial_no") val serialNo: String? = null,
    @SerialName("warranty_to") val warrantyTo: LocalDate? = null,
) {
    fun toEntity(at: Instant) = EquipmentEntity(id, invCode, siteId, vendor, model, serialNo, warrantyTo, at)
}

@Serializable
data class WorkTypeDto(val id: Int, val code: String, val name: String, @SerialName("norm_min") val normMin: Int) {
    fun toEntity(at: Instant) = WorkTypeEntity(id, code, name, normMin, at)
}

@Serializable
data class ChecklistItemDto(
    val id: Int,
    @SerialName("work_type_id") val workTypeId: Int,
    val pos: Int, val text: String, val required: Boolean,
    @SerialName("need_photo") val needPhoto: Boolean,
) {
    fun toEntity(at: Instant) = ChecklistItemEntity(id, workTypeId, pos, text, required, needPhoto, at)
}

/** Сотрудник без хеша пароля; логины других сотрудников сервер не передаёт. */
@Serializable
data class EmployeeDto(
    val id: Int,
    @SerialName("tab_no") val tabNo: String,
    @SerialName("full_name") val fullName: String,
    val position: String,
    val role: Int,
    val phone: String? = null,
    val login: String? = null,
    val blocked: Boolean = false,
) {
    // столбец login в телефоне обязателен и уникален: для чужих сотрудников — заглушка «#id»,
    // которая не может совпасть с настоящим логином
    fun toEntity(at: Instant) = EmployeeEntity(id, tabNo, fullName, position, role, phone, login ?: "#$id", blocked, at)
}

@Serializable
data class CalendarDayDto(
    val day: LocalDate,
    @SerialName("is_working") val isWorking: Boolean,
    val note: String? = null,
) {
    fun toEntity(at: Instant) = CalendarDayEntity(day, isWorking, note, at)
}

// ---------------------------------------------------------------- заявки и их записи

@Serializable
data class RequestDto(
    val id: Int, val number: String,
    @SerialName("contract_id") val contractId: Int,
    @SerialName("site_id") val siteId: Int,
    @SerialName("equipment_id") val equipmentId: Int? = null,
    @SerialName("work_type_id") val workTypeId: Int,
    @SerialName("sla_level_id") val slaLevelId: Int,
    @SerialName("dispatcher_id") val dispatcherId: Int,
    @SerialName("engineer_id") val engineerId: Int? = null,
    @SerialName("parent_id") val parentId: Int? = null,
    val description: String,
    @SerialName("registered_at") val registeredAt: Instant,
    @SerialName("reaction_due") val reactionDue: Instant,
    @SerialName("resolution_due") val resolutionDue: Instant,
    @SerialName("arrived_at") val arrivedAt: Instant? = null,
    @SerialName("completed_at") val completedAt: Instant? = null,
    @SerialName("closed_at") val closedAt: Instant? = null,
    val status: Int,
    @SerialName("scanned_code") val scannedCode: String? = null,
    @SerialName("result_text") val resultText: String? = null,
    @SerialName("cancel_reason") val cancelReason: String? = null,
    @SerialName("time_trusted") val timeTrusted: Boolean = true,
) {
    fun toEntity(at: Instant) = RequestEntity(
        id = id, number = number, contractId = contractId, siteId = siteId, equipmentId = equipmentId,
        workTypeId = workTypeId, slaLevelId = slaLevelId, dispatcherId = dispatcherId, engineerId = engineerId,
        parentId = parentId, description = description, registeredAt = registeredAt, reactionDue = reactionDue,
        resolutionDue = resolutionDue, arrivedAt = arrivedAt, completedAt = completedAt, closedAt = closedAt,
        status = status, scannedCode = scannedCode, resultText = resultText, cancelReason = cancelReason,
        timeTrusted = timeTrusted, updatedAt = at, syncState = SyncState.SYNCED,
    )
}

/** Из заявки на сервер уходят только поля, которые меняет инженер (раздел 10). */
@Serializable
data class RequestPushDto(
    val id: Int,
    val status: Int,
    @SerialName("arrived_at") val arrivedAt: Instant?,
    @SerialName("completed_at") val completedAt: Instant?,
    @SerialName("closed_at") val closedAt: Instant?,
    @SerialName("scanned_code") val scannedCode: String?,
    @SerialName("result_text") val resultText: String?,
    @SerialName("equipment_id") val equipmentId: Int?,
    @SerialName("time_trusted") val timeTrusted: Boolean,
) {
    companion object {
        fun from(r: RequestEntity) = RequestPushDto(
            r.id, r.status, r.arrivedAt, r.completedAt, r.closedAt, r.scannedCode, r.resultText, r.equipmentId, r.timeTrusted,
        )
    }
}

@Serializable
data class ChecklistResultDto(
    val id: String,
    @SerialName("request_id") val requestId: Int,
    @SerialName("checklist_item_id") val checklistItemId: Int,
    val done: Boolean,
    val comment: String? = null,
    @SerialName("done_at") val doneAt: Instant? = null,
) {
    fun toEntity(at: Instant) =
        ChecklistResultEntity(id, requestId, checklistItemId, done, comment, doneAt, at, SyncState.SYNCED)

    companion object {
        fun from(e: ChecklistResultEntity) =
            ChecklistResultDto(e.id, e.requestId, e.checklistItemId, e.done, e.comment, e.doneAt)
    }
}

/** Фото. deleted = true — фото удалено на телефоне (sync_state = 2), сервер удаляет запись и файл. */
@Serializable
data class PhotoDto(
    val id: String,
    @SerialName("request_id") val requestId: Int,
    @SerialName("checklist_item_id") val checklistItemId: Int? = null,
    val kind: Int,
    @SerialName("file_name") val fileName: String,
    @SerialName("taken_at") val takenAt: Instant,
    val sha256: String,
    val deleted: Boolean = false,
) {
    fun toEntity(at: Instant) =
        PhotoEntity(id, requestId, checklistItemId, kind, fileName, takenAt, sha256, at, SyncState.SYNCED)

    companion object {
        fun from(e: PhotoEntity) = PhotoDto(
            e.id, e.requestId, e.checklistItemId, e.kind, e.fileName, e.takenAt, e.sha256,
            deleted = e.syncState == SyncState.DELETED,
        )
    }
}

@Serializable
data class WorkLogDto(
    val id: String,
    @SerialName("request_id") val requestId: Int,
    @SerialName("employee_id") val employeeId: Int,
    val kind: Int,
    @SerialName("started_at") val startedAt: Instant,
    @SerialName("ended_at") val endedAt: Instant? = null,
    @SerialName("time_trusted") val timeTrusted: Boolean = true,
) {
    fun toEntity(at: Instant) =
        WorkLogEntity(id, requestId, employeeId, kind, startedAt, endedAt, timeTrusted, at, SyncState.SYNCED)

    companion object {
        fun from(e: WorkLogEntity) =
            WorkLogDto(e.id, e.requestId, e.employeeId, e.kind, e.startedAt, e.endedAt, e.timeTrusted)
    }
}

@Serializable
data class ActDto(
    val id: String,
    @SerialName("request_id") val requestId: Int,
    val number: String,
    @SerialName("act_date") val actDate: LocalDate,
    @SerialName("engineer_id") val engineerId: Int,
    @SerialName("sign_mode") val signMode: Int,
    @SerialName("signer_name") val signerName: String? = null,
    @SerialName("signer_position") val signerPosition: String? = null,
    @SerialName("signature_file") val signatureFile: String? = null,
    @SerialName("pdf_file") val pdfFile: String,
    @SerialName("signed_at") val signedAt: Instant? = null,
    val sha256: String,
    @SerialName("work_min") val workMin: Int,
) {
    fun toEntity(at: Instant) = ActEntity(
        id, requestId, number, actDate, engineerId, signMode, signerName, signerPosition, signatureFile,
        pdfFile, signedAt, sha256, workMin, at, SyncState.SYNCED,
    )

    companion object {
        fun from(e: ActEntity) = ActDto(
            e.id, e.requestId, e.number, e.actDate, e.engineerId, e.signMode, e.signerName, e.signerPosition,
            e.signatureFile, e.pdfFile, e.signedAt, e.sha256, e.workMin,
        )
    }
}
