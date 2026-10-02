package ru.fieldengineer.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

// 14 таблиц информационной модели (проект системы, раздел 2.1).
// Имена таблиц и столбцов совпадают с проектом и с базой сервера.
// В каждой таблице есть служебные updated_at и sync_state:
// 0 — синхронизирована, 1 — изменена и ждёт отправки, 2 — удалена и ждёт отправки.

object SyncState {
    const val SYNCED = 0
    const val CHANGED = 1
    const val DELETED = 2
}

// ---------------------------------------------------------------- НСИ

@Entity(tableName = "customer", indices = [Index(value = ["code"], unique = true)])
data class CustomerEntity(
    @PrimaryKey val id: Int,
    val code: String,
    val name: String,
    val inn: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

@Entity(
    tableName = "contract",
    foreignKeys = [ForeignKey(entity = CustomerEntity::class, parentColumns = ["id"], childColumns = ["customer_id"])],
    indices = [Index(value = ["number"], unique = true), Index("customer_id")],
)
data class ContractEntity(
    @PrimaryKey val id: Int,
    val number: String,
    @ColumnInfo(name = "customer_id") val customerId: Int,
    @ColumnInfo(name = "date_from") val dateFrom: LocalDate,
    @ColumnInfo(name = "date_to") val dateTo: LocalDate?,
    /** Самый высокий уровень SLA по договору: 1 — все уровни, 2 — SLA-2 и SLA-3, 3 — только SLA-3. */
    @ColumnInfo(name = "max_sla") val maxSla: Int,
    /** Есть соглашение о простой ЭП — акт можно подписывать на экране. */
    @ColumnInfo(name = "e_sign_allowed") val eSignAllowed: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

@Entity(tableName = "sla_level", indices = [Index(value = ["code"], unique = true)])
data class SlaLevelEntity(
    @PrimaryKey val id: Int,
    val code: String,
    val name: String,
    @ColumnInfo(name = "reaction_min") val reactionMin: Int,
    @ColumnInfo(name = "resolution_min") val resolutionMin: Int,
    /** Режим отсчёта: 1 — круглосуточно (24×7), 2 — рабочее время. */
    val mode: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

@Entity(
    tableName = "site",
    foreignKeys = [ForeignKey(entity = CustomerEntity::class, parentColumns = ["id"], childColumns = ["customer_id"])],
    indices = [Index(value = ["code"], unique = true), Index("customer_id")],
)
data class SiteEntity(
    @PrimaryKey val id: Int,
    val code: String,
    @ColumnInfo(name = "customer_id") val customerId: Int,
    val address: String,
    @ColumnInfo(name = "contact_name") val contactName: String?,
    @ColumnInfo(name = "contact_phone") val contactPhone: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

@Entity(
    tableName = "equipment",
    foreignKeys = [ForeignKey(entity = SiteEntity::class, parentColumns = ["id"], childColumns = ["site_id"])],
    indices = [Index(value = ["inv_code"], unique = true), Index("site_id"), Index("serial_no")],
)
data class EquipmentEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "inv_code") val invCode: String,
    @ColumnInfo(name = "site_id") val siteId: Int,
    val vendor: String,
    val model: String,
    @ColumnInfo(name = "serial_no") val serialNo: String?,
    @ColumnInfo(name = "warranty_to") val warrantyTo: LocalDate?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

@Entity(tableName = "work_type", indices = [Index(value = ["code"], unique = true)])
data class WorkTypeEntity(
    @PrimaryKey val id: Int,
    val code: String,
    val name: String,
    @ColumnInfo(name = "norm_min") val normMin: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

@Entity(
    tableName = "checklist_item",
    foreignKeys = [ForeignKey(entity = WorkTypeEntity::class, parentColumns = ["id"], childColumns = ["work_type_id"])],
    indices = [Index("work_type_id")],
)
data class ChecklistItemEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "work_type_id") val workTypeId: Int,
    val pos: Int,
    val text: String,
    val required: Boolean,
    @ColumnInfo(name = "need_photo") val needPhoto: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

/** Сотрудник. Хеша пароля в телефоне нет — он хранится только на сервере. */
@Entity(
    tableName = "employee",
    indices = [Index(value = ["tab_no"], unique = true), Index(value = ["login"], unique = true)],
)
data class EmployeeEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "tab_no") val tabNo: String,
    @ColumnInfo(name = "full_name") val fullName: String,
    val position: String,
    /** 1 — инженер, 2 — диспетчер, 3 — руководитель службы. */
    val role: Int,
    val phone: String?,
    val login: String,
    val blocked: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

/** День-исключение производственного календаря; связей с другими таблицами нет. */
@Entity(tableName = "calendar_day")
data class CalendarDayEntity(
    @PrimaryKey val day: LocalDate,
    @ColumnInfo(name = "is_working") val isWorking: Boolean,
    val note: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

// ---------------------------------------------------------------- оперативные

@Entity(
    tableName = "request",
    foreignKeys = [
        ForeignKey(entity = ContractEntity::class, parentColumns = ["id"], childColumns = ["contract_id"]),
        ForeignKey(entity = SiteEntity::class, parentColumns = ["id"], childColumns = ["site_id"]),
        ForeignKey(entity = EquipmentEntity::class, parentColumns = ["id"], childColumns = ["equipment_id"]),
        ForeignKey(entity = WorkTypeEntity::class, parentColumns = ["id"], childColumns = ["work_type_id"]),
        ForeignKey(entity = SlaLevelEntity::class, parentColumns = ["id"], childColumns = ["sla_level_id"]),
        ForeignKey(entity = EmployeeEntity::class, parentColumns = ["id"], childColumns = ["dispatcher_id"]),
        ForeignKey(entity = EmployeeEntity::class, parentColumns = ["id"], childColumns = ["engineer_id"]),
        ForeignKey(entity = RequestEntity::class, parentColumns = ["id"], childColumns = ["parent_id"]),
    ],
    indices = [
        Index(value = ["number"], unique = true), Index("contract_id"), Index("site_id"), Index("equipment_id"),
        Index("work_type_id"), Index("sla_level_id"), Index("dispatcher_id"), Index("engineer_id"), Index("parent_id"),
    ],
)
data class RequestEntity(
    @PrimaryKey val id: Int,
    val number: String,
    @ColumnInfo(name = "contract_id") val contractId: Int,
    @ColumnInfo(name = "site_id") val siteId: Int,
    @ColumnInfo(name = "equipment_id") val equipmentId: Int?,
    @ColumnInfo(name = "work_type_id") val workTypeId: Int,
    @ColumnInfo(name = "sla_level_id") val slaLevelId: Int,
    @ColumnInfo(name = "dispatcher_id") val dispatcherId: Int,
    @ColumnInfo(name = "engineer_id") val engineerId: Int?,
    @ColumnInfo(name = "parent_id") val parentId: Int?,
    val description: String,
    @ColumnInfo(name = "registered_at") val registeredAt: Instant,
    @ColumnInfo(name = "reaction_due") val reactionDue: Instant,
    @ColumnInfo(name = "resolution_due") val resolutionDue: Instant,
    @ColumnInfo(name = "arrived_at") val arrivedAt: Instant?,
    @ColumnInfo(name = "completed_at") val completedAt: Instant?,
    @ColumnInfo(name = "closed_at") val closedAt: Instant?,
    val status: Int,
    @ColumnInfo(name = "scanned_code") val scannedCode: String?,
    @ColumnInfo(name = "result_text") val resultText: String?,
    @ColumnInfo(name = "cancel_reason") val cancelReason: String?,
    @ColumnInfo(name = "time_trusted") val timeTrusted: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.SYNCED,
)

// Записи ниже создаются на телефоне без связи, поэтому ключ — UUID (строкой).

@Entity(
    tableName = "checklist_result",
    foreignKeys = [
        ForeignKey(entity = RequestEntity::class, parentColumns = ["id"], childColumns = ["request_id"]),
        ForeignKey(entity = ChecklistItemEntity::class, parentColumns = ["id"], childColumns = ["checklist_item_id"]),
    ],
    indices = [Index(value = ["request_id", "checklist_item_id"], unique = true), Index("checklist_item_id")],
)
data class ChecklistResultEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "request_id") val requestId: Int,
    @ColumnInfo(name = "checklist_item_id") val checklistItemId: Int,
    val done: Boolean,
    val comment: String?,
    @ColumnInfo(name = "done_at") val doneAt: Instant?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.CHANGED,
)

@Entity(
    tableName = "photo",
    foreignKeys = [
        ForeignKey(entity = RequestEntity::class, parentColumns = ["id"], childColumns = ["request_id"]),
        ForeignKey(entity = ChecklistItemEntity::class, parentColumns = ["id"], childColumns = ["checklist_item_id"]),
    ],
    indices = [Index("request_id"), Index("checklist_item_id")],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "request_id") val requestId: Int,
    @ColumnInfo(name = "checklist_item_id") val checklistItemId: Int?,
    /** 1 — фото работ, 2 — скан бумажного акта с подписью. */
    val kind: Int,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "taken_at") val takenAt: Instant,
    val sha256: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.CHANGED,
)

@Entity(
    tableName = "work_log",
    foreignKeys = [
        ForeignKey(entity = RequestEntity::class, parentColumns = ["id"], childColumns = ["request_id"]),
        ForeignKey(entity = EmployeeEntity::class, parentColumns = ["id"], childColumns = ["employee_id"]),
    ],
    indices = [Index("request_id"), Index("employee_id")],
)
data class WorkLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "request_id") val requestId: Int,
    @ColumnInfo(name = "employee_id") val employeeId: Int,
    /** 1 — дорога, 2 — работа на объекте. */
    val kind: Int,
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    @ColumnInfo(name = "ended_at") val endedAt: Instant?,
    @ColumnInfo(name = "time_trusted") val timeTrusted: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.CHANGED,
)

// ---------------------------------------------------------------- результатная

@Entity(
    tableName = "act",
    foreignKeys = [
        ForeignKey(entity = RequestEntity::class, parentColumns = ["id"], childColumns = ["request_id"]),
        ForeignKey(entity = EmployeeEntity::class, parentColumns = ["id"], childColumns = ["engineer_id"]),
    ],
    indices = [Index(value = ["request_id"], unique = true), Index(value = ["number"], unique = true), Index("engineer_id")],
)
data class ActEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "request_id") val requestId: Int,
    val number: String,
    @ColumnInfo(name = "act_date") val actDate: LocalDate,
    @ColumnInfo(name = "engineer_id") val engineerId: Int,
    /** 1 — подписан на экране (простая ЭП), 2 — на бумаге. */
    @ColumnInfo(name = "sign_mode") val signMode: Int,
    @ColumnInfo(name = "signer_name") val signerName: String?,
    @ColumnInfo(name = "signer_position") val signerPosition: String?,
    @ColumnInfo(name = "signature_file") val signatureFile: String?,
    @ColumnInfo(name = "pdf_file") val pdfFile: String,
    @ColumnInfo(name = "signed_at") val signedAt: Instant?,
    val sha256: String,
    @ColumnInfo(name = "work_min") val workMin: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.CHANGED,
)
