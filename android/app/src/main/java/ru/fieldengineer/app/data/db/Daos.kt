package ru.fieldengineer.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Dao
import androidx.room.Upsert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Справочники (НСИ). Их заполняет синхронизация с сервером («побеждает сервер»).
 * Upsert обновляет строку на месте, а не удаляет и вставляет заново — так не рвутся внешние ключи.
 */
@Dao
interface NsiDao {
    @Upsert suspend fun upsertCustomers(items: List<CustomerEntity>)
    @Upsert suspend fun upsertContracts(items: List<ContractEntity>)
    @Upsert suspend fun upsertSlaLevels(items: List<SlaLevelEntity>)
    @Upsert suspend fun upsertSites(items: List<SiteEntity>)
    @Upsert suspend fun upsertEquipment(items: List<EquipmentEntity>)
    @Upsert suspend fun upsertWorkTypes(items: List<WorkTypeEntity>)
    @Upsert suspend fun upsertChecklistItems(items: List<ChecklistItemEntity>)
    @Upsert suspend fun upsertEmployees(items: List<EmployeeEntity>)
    @Upsert suspend fun upsertCalendarDays(items: List<CalendarDayEntity>)

    @Query("SELECT COUNT(*) FROM employee")
    suspend fun employeeCount(): Int

    @Query("SELECT * FROM employee WHERE login = :login")
    suspend fun employeeByLogin(login: String): EmployeeEntity?

    @Query("SELECT * FROM calendar_day")
    fun observeCalendar(): Flow<List<CalendarDayEntity>>

    @Query("SELECT * FROM equipment WHERE site_id = :siteId")
    suspend fun siteEquipment(siteId: Int): List<EquipmentEntity>

    @Query("SELECT * FROM employee WHERE id = :id")
    suspend fun employeeById(id: Int): EmployeeEntity?
}

/** Строка списка «Мои заявки» (экран Э2): заявка вместе с названиями из справочников. */
data class RequestListRow(
    val id: Int,
    val number: String,
    val status: Int,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "site_address") val siteAddress: String,
    @ColumnInfo(name = "work_type_name") val workTypeName: String,
    @ColumnInfo(name = "sla_code") val slaCode: String,
    @ColumnInfo(name = "reaction_min") val reactionMin: Int,
    @ColumnInfo(name = "resolution_min") val resolutionMin: Int,
    val mode: Int,
    @ColumnInfo(name = "registered_at") val registeredAt: Instant,
    @ColumnInfo(name = "reaction_due") val reactionDue: Instant,
    @ColumnInfo(name = "resolution_due") val resolutionDue: Instant,
    @ColumnInfo(name = "arrived_at") val arrivedAt: Instant?,
    @ColumnInfo(name = "completed_at") val completedAt: Instant?,
    @ColumnInfo(name = "closed_at") val closedAt: Instant?,
    @ColumnInfo(name = "sync_state") val syncState: Int,
    /** Есть неотправленные изменения: у самой заявки или у её отметок, фото, интервалов, акта. */
    @ColumnInfo(name = "has_unsent") val hasUnsent: Boolean,
)

/** Карточка заявки (экран Э3): сама заявка и всё, что о ней нужно знать на объекте. */
data class RequestCardRow(
    @Embedded val request: RequestEntity,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "customer_inn") val customerInn: String,
    @ColumnInfo(name = "contract_number") val contractNumber: String,
    @ColumnInfo(name = "e_sign_allowed") val eSignAllowed: Boolean,
    @ColumnInfo(name = "site_code") val siteCode: String,
    @ColumnInfo(name = "site_address") val siteAddress: String,
    @ColumnInfo(name = "contact_name") val contactName: String?,
    @ColumnInfo(name = "contact_phone") val contactPhone: String?,
    @ColumnInfo(name = "work_type_code") val workTypeCode: String,
    @ColumnInfo(name = "work_type_name") val workTypeName: String,
    @ColumnInfo(name = "norm_min") val normMin: Int,
    @ColumnInfo(name = "sla_code") val slaCode: String,
    @ColumnInfo(name = "sla_name") val slaName: String,
    @ColumnInfo(name = "reaction_min") val reactionMin: Int,
    @ColumnInfo(name = "resolution_min") val resolutionMin: Int,
    val mode: Int,
    @ColumnInfo(name = "eq_inv_code") val eqInvCode: String?,
    @ColumnInfo(name = "eq_vendor") val eqVendor: String?,
    @ColumnInfo(name = "eq_model") val eqModel: String?,
    @ColumnInfo(name = "eq_serial_no") val eqSerialNo: String?,
)

/** Пункт чек-листа заявки вместе с отметкой (если она есть) и числом фото к нему (экран Э4). */
data class ChecklistRow(
    @ColumnInfo(name = "item_id") val itemId: Int,
    val pos: Int,
    val text: String,
    val required: Boolean,
    @ColumnInfo(name = "need_photo") val needPhoto: Boolean,
    @ColumnInfo(name = "result_id") val resultId: String?,
    val done: Boolean?,
    val comment: String?,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
)

/** Условие «у заявки r есть неотправленные изменения» (sync_state ≠ 0 у неё или её записей). */
const val HAS_UNSENT = """(r.sync_state != 0
    OR EXISTS (SELECT 1 FROM checklist_result x WHERE x.request_id = r.id AND x.sync_state != 0)
    OR EXISTS (SELECT 1 FROM photo x WHERE x.request_id = r.id AND x.sync_state != 0)
    OR EXISTS (SELECT 1 FROM work_log x WHERE x.request_id = r.id AND x.sync_state != 0)
    OR EXISTS (SELECT 1 FROM act x WHERE x.request_id = r.id AND x.sync_state != 0))"""

@Dao
interface RequestDao {
    @Upsert
    suspend fun upsertRequests(items: List<RequestEntity>)

    @Query(
        """
        SELECT r.id, r.number, r.status, c.name AS customer_name, s.address AS site_address,
               w.name AS work_type_name, l.code AS sla_code, l.reaction_min, l.resolution_min, l.mode,
               r.registered_at, r.reaction_due, r.resolution_due, r.arrived_at, r.completed_at,
               r.closed_at, r.sync_state, $HAS_UNSENT AS has_unsent
        FROM request r
        JOIN contract k ON k.id = r.contract_id
        JOIN customer c ON c.id = k.customer_id
        JOIN site s ON s.id = r.site_id
        JOIN work_type w ON w.id = r.work_type_id
        JOIN sla_level l ON l.id = r.sla_level_id
        WHERE r.engineer_id = :engineerId
        """
    )
    fun observeForEngineer(engineerId: Int): Flow<List<RequestListRow>>

    @Query(
        """
        SELECT r.*, c.name AS customer_name, c.inn AS customer_inn, k.number AS contract_number, k.e_sign_allowed,
               s.code AS site_code, s.address AS site_address, s.contact_name, s.contact_phone,
               w.code AS work_type_code, w.name AS work_type_name, w.norm_min,
               l.code AS sla_code, l.name AS sla_name, l.reaction_min, l.resolution_min, l.mode,
               e.inv_code AS eq_inv_code, e.vendor AS eq_vendor, e.model AS eq_model, e.serial_no AS eq_serial_no
        FROM request r
        JOIN contract k ON k.id = r.contract_id
        JOIN customer c ON c.id = k.customer_id
        JOIN site s ON s.id = r.site_id
        JOIN work_type w ON w.id = r.work_type_id
        JOIN sla_level l ON l.id = r.sla_level_id
        LEFT JOIN equipment e ON e.id = r.equipment_id
        WHERE r.id = :requestId
        """
    )
    fun observeCard(requestId: Int): Flow<RequestCardRow?>

    @Query("SELECT * FROM request WHERE id = :requestId")
    suspend fun request(requestId: Int): RequestEntity?

    @Query(
        """
        SELECT i.id AS item_id, i.pos, i.text, i.required, i.need_photo,
               cr.id AS result_id, cr.done, cr.comment,
               (SELECT COUNT(*) FROM photo p
                 WHERE p.request_id = r.id AND p.checklist_item_id = i.id AND p.sync_state != 2) AS photo_count
        FROM request r
        JOIN checklist_item i ON i.work_type_id = r.work_type_id
        LEFT JOIN checklist_result cr ON cr.request_id = r.id AND cr.checklist_item_id = i.id
        WHERE r.id = :requestId
        ORDER BY i.pos
        """
    )
    fun observeChecklist(requestId: Int): Flow<List<ChecklistRow>>

    @Query(
        """
        SELECT i.id AS item_id, i.pos, i.text, i.required, i.need_photo,
               cr.id AS result_id, cr.done, cr.comment,
               (SELECT COUNT(*) FROM photo p
                 WHERE p.request_id = r.id AND p.checklist_item_id = i.id AND p.sync_state != 2) AS photo_count
        FROM request r
        JOIN checklist_item i ON i.work_type_id = r.work_type_id
        LEFT JOIN checklist_result cr ON cr.request_id = r.id AND cr.checklist_item_id = i.id
        WHERE r.id = :requestId
        ORDER BY i.pos
        """
    )
    suspend fun checklist(requestId: Int): List<ChecklistRow>
}

/** Оперативные записи, которые создаёт инженер: отметки чек-листа и интервалы времени. */
@Dao
interface WorkDao {
    @Upsert suspend fun upsertChecklistResults(items: List<ChecklistResultEntity>)
    @Upsert suspend fun upsertWorkLogs(items: List<WorkLogEntity>)
    @Upsert suspend fun upsertPhotos(items: List<PhotoEntity>)
    @Upsert suspend fun upsertActs(items: List<ActEntity>)

    /** Фото заявки без помеченных на удаление (sync_state = 2). */
    @Query("SELECT * FROM photo WHERE request_id = :requestId AND sync_state != 2 ORDER BY taken_at")
    fun observePhotos(requestId: Int): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photo WHERE id = :id")
    suspend fun photo(id: String): PhotoEntity?

    @Query("SELECT COUNT(*) FROM photo WHERE request_id = :requestId AND kind = :kind AND sync_state != 2")
    suspend fun photoCount(requestId: Int, kind: Int): Int

    @Query("SELECT * FROM act WHERE request_id = :requestId")
    fun observeAct(requestId: Int): Flow<ActEntity?>

    @Query("SELECT * FROM act WHERE request_id = :requestId")
    suspend fun act(requestId: Int): ActEntity?

    @Query("SELECT * FROM work_log WHERE request_id = :requestId")
    suspend fun workLog(requestId: Int): List<WorkLogEntity>

    @Query("SELECT * FROM checklist_result WHERE request_id = :requestId AND checklist_item_id = :itemId")
    suspend fun checklistResult(requestId: Int, itemId: Int): ChecklistResultEntity?

    @Query("SELECT * FROM work_log WHERE request_id = :requestId ORDER BY started_at")
    fun observeWorkLog(requestId: Int): Flow<List<WorkLogEntity>>

    @Query("SELECT * FROM work_log WHERE request_id = :requestId AND ended_at IS NULL")
    suspend fun openIntervals(requestId: Int): List<WorkLogEntity>

    /** Интервалы сотрудника, пересекающиеся с периодом [from, to). */
    @Query(
        """
        SELECT * FROM work_log
        WHERE employee_id = :employeeId AND started_at < :to AND (ended_at IS NULL OR ended_at > :from)
        """
    )
    fun observeEmployeeLog(employeeId: Int, from: Instant, to: Instant): Flow<List<WorkLogEntity>>
}

/** Очередь на отправку по таблицам (экран Э10). */
data class QueueCounts(
    val request: Int,
    @ColumnInfo(name = "checklist_result") val checklistResult: Int,
    val photo: Int,
    @ColumnInfo(name = "work_log") val workLog: Int,
    val act: Int,
) {
    val total: Int get() = request + checklistResult + photo + workLog + act
}

/** Заявка для решения «удалять ли её после pull». */
data class RequestSyncInfo(
    val id: Int,
    val status: Int,
    @ColumnInfo(name = "parent_id") val parentId: Int?,
    @ColumnInfo(name = "has_unsent") val hasUnsent: Boolean,
)

/**
 * Запросы синхронизации: что отправить (sync_state ≠ 0), снятие отметки после ответа сервера,
 * удаление заявок, которые сняли с инженера.
 */
@Dao
interface SyncDao {
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM request WHERE sync_state != 0) AS request,
               (SELECT COUNT(*) FROM checklist_result WHERE sync_state != 0) AS checklist_result,
               (SELECT COUNT(*) FROM photo WHERE sync_state != 0) AS photo,
               (SELECT COUNT(*) FROM work_log WHERE sync_state != 0) AS work_log,
               (SELECT COUNT(*) FROM act WHERE sync_state != 0) AS act
        """
    )
    fun observeQueue(): Flow<QueueCounts>

    // ---- что отправить
    @Query("SELECT * FROM request WHERE sync_state != 0") suspend fun unsentRequests(): List<RequestEntity>
    @Query("SELECT * FROM checklist_result WHERE sync_state != 0") suspend fun unsentChecklistResults(): List<ChecklistResultEntity>
    @Query("SELECT * FROM photo WHERE sync_state != 0") suspend fun unsentPhotos(): List<PhotoEntity>
    @Query("SELECT * FROM work_log WHERE sync_state != 0") suspend fun unsentWorkLogs(): List<WorkLogEntity>
    @Query("SELECT * FROM act WHERE sync_state != 0") suspend fun unsentActs(): List<ActEntity>

    // ---- updated_at записи сейчас (null — записи нет)
    @Query("SELECT updated_at FROM request WHERE id = :id") suspend fun requestUpdatedAt(id: Int): Instant?
    @Query("SELECT updated_at FROM checklist_result WHERE id = :id") suspend fun checklistResultUpdatedAt(id: String): Instant?
    @Query("SELECT updated_at FROM photo WHERE id = :id") suspend fun photoUpdatedAt(id: String): Instant?
    @Query("SELECT updated_at FROM work_log WHERE id = :id") suspend fun workLogUpdatedAt(id: String): Instant?
    @Query("SELECT updated_at FROM act WHERE id = :id") suspend fun actUpdatedAt(id: String): Instant?

    // ---- снять отметку «ждёт отправки»
    @Query("UPDATE request SET sync_state = 0 WHERE id = :id") suspend fun markRequestSynced(id: Int)
    @Query("UPDATE checklist_result SET sync_state = 0 WHERE id = :id") suspend fun markChecklistResultSynced(id: String)
    @Query("UPDATE photo SET sync_state = 0 WHERE id = :id") suspend fun markPhotoSynced(id: String)
    @Query("UPDATE work_log SET sync_state = 0 WHERE id = :id") suspend fun markWorkLogSynced(id: String)
    @Query("UPDATE act SET sync_state = 0 WHERE id = :id") suspend fun markActSynced(id: String)

    // повторная отправка отклонённой записи с Э10 — запись снова ждёт отправки;
    // результат — сколько строк изменено (0 — записи уже нет в телефоне)
    @Query("UPDATE checklist_result SET sync_state = 1 WHERE id = :id") suspend fun requeueChecklistResult(id: String): Int
    @Query("UPDATE work_log SET sync_state = 1 WHERE id = :id") suspend fun requeueWorkLog(id: String): Int
    @Query("UPDATE act SET sync_state = 1 WHERE id = :id") suspend fun requeueAct(id: String): Int

    /**
     * Фото: [state] 1 — отправить снова, 2 — повторить удаление. MAX не даёт повтору
     * «вернуть» фото, которое инженер удалил уже после отказа (2 остаётся 2).
     */
    @Query("UPDATE photo SET sync_state = MAX(sync_state, :state) WHERE id = :id")
    suspend fun requeuePhoto(id: String, state: Int): Int

    // ---- всё локальное — для слияния с ответом pull
    @Query("SELECT * FROM request") suspend fun allRequests(): List<RequestEntity>
    @Query("SELECT * FROM checklist_result") suspend fun allChecklistResults(): List<ChecklistResultEntity>
    @Query("SELECT * FROM photo") suspend fun allPhotos(): List<PhotoEntity>
    @Query("SELECT * FROM work_log") suspend fun allWorkLogs(): List<WorkLogEntity>
    @Query("SELECT * FROM act") suspend fun allActs(): List<ActEntity>

    @Query("SELECT r.id, r.status, r.parent_id, $HAS_UNSENT AS has_unsent FROM request r")
    suspend fun requestSyncInfo(): List<RequestSyncInfo>

    // ---- удаление (сначала дочерние таблицы — внешние ключи)
    @Query("DELETE FROM checklist_result WHERE id IN (:ids)") suspend fun deleteChecklistResults(ids: List<String>)
    @Query("DELETE FROM photo WHERE id IN (:ids)") suspend fun deletePhotos(ids: List<String>)
    @Query("DELETE FROM act WHERE id IN (:ids)") suspend fun deleteActs(ids: List<String>)

    @Query("SELECT * FROM photo WHERE request_id IN (:requestIds)") suspend fun photosOf(requestIds: List<Int>): List<PhotoEntity>
    @Query("SELECT * FROM act WHERE request_id IN (:requestIds)") suspend fun actsOf(requestIds: List<Int>): List<ActEntity>
    @Query("DELETE FROM checklist_result WHERE request_id IN (:requestIds)") suspend fun deleteChecklistResultsOf(requestIds: List<Int>)
    @Query("DELETE FROM photo WHERE request_id IN (:requestIds)") suspend fun deletePhotosOf(requestIds: List<Int>)
    @Query("DELETE FROM work_log WHERE request_id IN (:requestIds)") suspend fun deleteWorkLogsOf(requestIds: List<Int>)
    @Query("DELETE FROM act WHERE request_id IN (:requestIds)") suspend fun deleteActsOf(requestIds: List<Int>)
    @Query("DELETE FROM request WHERE id IN (:requestIds)") suspend fun deleteRequests(requestIds: List<Int>)
}
