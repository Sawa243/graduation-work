package ru.fieldengineer.app

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.data.db.PhotoEntity
import ru.fieldengineer.app.data.db.SyncState
import ru.fieldengineer.app.data.net.ApiErrors
import ru.fieldengineer.app.data.net.ApiJson
import ru.fieldengineer.app.data.net.LoginResponse
import ru.fieldengineer.app.data.net.PhotoDto
import ru.fieldengineer.app.data.net.PullResponse
import ru.fieldengineer.app.data.net.PushBody
import ru.fieldengineer.app.data.net.PushResponse
import ru.fieldengineer.app.data.net.RequestPushDto
import ru.fieldengineer.app.data.sync.SyncRules
import java.time.Instant
import java.time.LocalDate

/** Разбор и сборка JSON по контракту API (проект системы, раздел 10). */
class ApiJsonTest {

    private val pullExample = """
        {
          "server_time": "2026-09-28T11:30:15+03:00",
          "customer": [{"id": 1, "code": "0001", "name": "АО «Энергосеть-Север»", "inn": "7700000011",
                        "updated_at": "2026-09-01T10:00:00+03:00"}],
          "contract": [{"id": 1, "number": "СО-2026-014", "customer_id": 1, "date_from": "2026-01-15",
                        "date_to": null, "max_sla": 1, "e_sign_allowed": true}],
          "calendar_day": [{"day": "2026-11-04", "is_working": false, "note": "День народного единства"}],
          "employee": [{"id": 2, "tab_no": "00007", "full_name": "Диспетчер Тестовый", "position": "Диспетчер",
                        "role": 2, "phone": null, "blocked": false}],
          "request": [{"id": 137, "number": "З-2609-0137", "contract_id": 1, "site_id": 1, "equipment_id": null,
                       "work_type_id": 3, "sla_level_id": 2, "dispatcher_id": 2, "engineer_id": 1, "parent_id": null,
                       "description": "Не работает коммутатор", "registered_at": "2026-09-25T16:30:00+03:00",
                       "reaction_due": "2026-09-28T11:30:00+03:00", "resolution_due": "2026-09-30T13:30:00+03:00",
                       "arrived_at": null, "completed_at": null, "closed_at": null, "status": 2,
                       "scanned_code": null, "result_text": null, "cancel_reason": null, "time_trusted": true}],
          "photo": [{"id": "0b9f0c4e-2f7a-4a55-9d5c-2c1e8e0f0a01", "request_id": 137, "checklist_item_id": null,
                     "kind": 1, "file_name": "a.jpg", "taken_at": "2026-09-28T10:00:00Z", "sha256": "ab"}],
          "assigned_request_ids": [137, 138]
        }
    """.trimIndent()

    @Test
    fun `pull — время со смещением +03-00 превращается в верный момент UTC`() {
        val pull = ApiJson.decodeFromString<PullResponse>(pullExample)
        assertEquals(Instant.parse("2026-09-28T08:30:15Z"), pull.serverTime)
        val r = pull.request.single()
        assertEquals(Instant.parse("2026-09-25T13:30:00Z"), r.registeredAt)
        assertEquals(Instant.parse("2026-09-28T08:30:00Z"), r.reactionDue)
        assertNull(r.arrivedAt)
        // смещение Z тоже понимаем
        assertEquals(Instant.parse("2026-09-28T10:00:00Z"), pull.photo.single().takenAt)
    }

    @Test
    fun `pull — даты, пустые поля, отсутствующие таблицы и лишние поля`() {
        val pull = ApiJson.decodeFromString<PullResponse>(pullExample)
        assertEquals(LocalDate.of(2026, 1, 15), pull.contract.single().dateFrom)
        assertNull(pull.contract.single().dateTo)
        assertEquals(LocalDate.of(2026, 11, 4), pull.calendarDay.single().day)
        assertTrue(pull.site.isEmpty())                 // таблицы нет в ответе — пустой список
        assertEquals(listOf(137, 138), pull.assignedRequestIds)
        // логин чужого сотрудника сервер не передаёт — в телефоне уникальная заглушка
        val employee = pull.employee.single().toEntity(pull.serverTime)
        assertEquals("#2", employee.login)
        val request = pull.request.single().toEntity(pull.serverTime)
        assertEquals(SyncState.SYNCED, request.syncState)
        assertEquals(pull.serverTime, request.updatedAt)
    }

    @Test
    fun `pull без assigned_request_ids — список null, удалять нечего`() {
        val pull = ApiJson.decodeFromString<PullResponse>("""{"server_time": "2026-09-28T11:30:00+03:00"}""")
        assertNull(pull.assignedRequestIds)
    }

    @Test
    fun `push — имена полей snake_case, время со смещением, удалённое фото с deleted true`() {
        val photo = PhotoEntity("p1", 137, 5, 1, "p1.jpg", Instant.parse("2026-09-28T08:15:00Z"), "ff",
            Instant.parse("2026-09-28T08:15:00Z"), SyncState.DELETED)
        val body = PushBody(
            request = listOf(RequestPushDto(137, 4, Instant.parse("2026-09-28T07:05:00Z"), null, null,
                "BK-EQ-000123", "Заменён блок питания", 3, true)),
            checklistResult = emptyList(), photo = listOf(PhotoDto.from(photo)), workLog = emptyList(), act = emptyList(),
        )
        val json = ApiJson.parseToJsonElement(ApiJson.encodeToString(PushBody.serializer(), body)).jsonObject
        val r = json["request"]!!.jsonArray.single().jsonObject
        assertEquals("2026-09-28T10:05:00+03:00", r["arrived_at"]!!.jsonPrimitive.content)
        assertEquals("null", r["completed_at"].toString())        // пустое поле уходит как null
        assertEquals("BK-EQ-000123", r["scanned_code"]!!.jsonPrimitive.content)
        assertFalse(r.containsKey("description"))                  // поля диспетчера не отправляем
        val p = json["photo"]!!.jsonArray.single().jsonObject
        assertEquals("true", p["deleted"]!!.jsonPrimitive.content)
        assertEquals("5", p["checklist_item_id"]!!.jsonPrimitive.content)
        assertTrue(json["work_log"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `ответ push — id заявок числом и UUID строкой читаются одинаково`() {
        val response = ApiJson.decodeFromString<PushResponse>("""
            {"server_time": "2026-09-28T11:31:00+03:00",
             "accepted": {"request": [137], "photo": ["0b9f0c4e-2f7a-4a55-9d5c-2c1e8e0f0a01"]},
             "rejected": [{"table": "act", "id": "a1", "reason": "Заявка закрыта на сервере"}]}
        """.trimIndent())
        val result = SyncRules.pushResult(response)
        assertTrue("request:137" in result.accepted)
        assertTrue("photo:0b9f0c4e-2f7a-4a55-9d5c-2c1e8e0f0a01" in result.accepted)
        assertEquals("Заявка закрыта на сервере", result.rejected["act:a1"])
    }

    @Test
    fun `ответ на вход`() {
        val login = ApiJson.decodeFromString<LoginResponse>("""
            {"access_token": "aaa", "access_expires_at": "2026-09-28T12:00:00+03:00", "refresh_token": "rrr",
             "refresh_expires_at": "2026-10-28T11:30:00+03:00", "server_time": "2026-09-28T11:30:00+03:00",
             "employee": {"id": 1, "tab_no": "00042", "full_name": "Инженер Тестовый",
                          "position": "Инженер выездной службы", "role": 1, "phone": "+7 000 000-00-01",
                          "login": "engineer", "blocked": false}}
        """.trimIndent())
        assertEquals("engineer", login.employee.toEntity(login.serverTime).login)
        assertEquals(Instant.parse("2026-09-28T08:30:00Z"), login.serverTime)
    }

    @Test
    fun `сообщения об ошибке входа`() {
        assertEquals(ApiErrors.WRONG_CREDENTIALS, ApiErrors.loginMessage(401, "Неверный логин"))
        assertEquals(ApiErrors.NOT_ENGINEER, ApiErrors.loginMessage(403, "Доступ запрещён"))
        assertEquals(ApiErrors.BLOCKED, ApiErrors.loginMessage(403, "Учётная запись заблокирована"))
        assertEquals("Вход закрыт на 15 минут", ApiErrors.loginMessage(429, "Вход закрыт на 15 минут"))
        assertEquals(ApiErrors.SERVER_FAILURE, ApiErrors.loginMessage(503, null))
    }
}
