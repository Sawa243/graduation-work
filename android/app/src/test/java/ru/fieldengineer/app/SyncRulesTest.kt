package ru.fieldengineer.app

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.data.db.RequestEntity
import ru.fieldengineer.app.data.db.SyncState
import ru.fieldengineer.app.data.net.ApiJson
import ru.fieldengineer.app.data.sync.RejectedRecord
import ru.fieldengineer.app.data.sync.SyncResult
import ru.fieldengineer.app.data.sync.SyncRules
import ru.fieldengineer.app.data.sync.SyncRules.LocalRecord
import ru.fieldengineer.app.data.sync.SyncRules.LocalRequestInfo
import ru.fieldengineer.app.data.sync.SyncRules.PushAction
import ru.fieldengineer.app.data.sync.SyncRules.PushResult
import ru.fieldengineer.app.data.sync.SyncRules.SentRecord
import java.time.Instant

class SyncRulesTest {
    private val t0 = Instant.parse("2026-09-28T08:00:00Z")
    private val t1 = Instant.parse("2026-09-28T09:00:00Z")

    private fun request(
        id: Int = 137, status: Int = 2, syncState: Int = SyncState.SYNCED, parentId: Int? = null,
        description: String = "Не работает коммутатор", resultText: String? = null,
        arrivedAt: Instant? = null, updatedAt: Instant = t0,
    ) = RequestEntity(
        id = id, number = "З-2609-%04d".format(id), contractId = 1, siteId = 1, equipmentId = null, workTypeId = 3,
        slaLevelId = 2, dispatcherId = 2, engineerId = 1, parentId = parentId, description = description,
        registeredAt = t0, reactionDue = t0.plusSeconds(14_400), resolutionDue = t0.plusSeconds(86_400),
        arrivedAt = arrivedAt, completedAt = null, closedAt = null, status = status, scannedCode = null,
        resultText = resultText, cancelReason = null, timeTrusted = true, updatedAt = updatedAt, syncState = syncState,
    )

    // ---------------------------------------------------------------- слияние заявки

    @Test
    fun `новая заявка с сервера сохраняется как есть`() {
        val server = request(description = "С сервера")
        assertEquals(server, SyncRules.mergeRequest(null, server))
    }

    @Test
    fun `синхронизированную заявку сервер перезаписывает целиком`() {
        val local = request(status = 3, resultText = "старое")
        val server = request(status = 7, description = "Отменена диспетчером", updatedAt = t1)
        assertEquals(server, SyncRules.mergeRequest(local, server))
    }

    @Test
    fun `у изменённой заявки поля инженера остаются локальными, остальное — с сервера`() {
        val local = request(status = 4, syncState = SyncState.CHANGED, resultText = "Заменил блок питания",
            arrivedAt = t1, updatedAt = t1)
        val server = request(status = 3, description = "Уточнено диспетчером", updatedAt = t0.plusSeconds(60))
        val merged = SyncRules.mergeRequest(local, server)
        assertEquals("Уточнено диспетчером", merged.description)   // с сервера
        assertEquals(4, merged.status)                               // инженерское — с телефона
        assertEquals("Заменил блок питания", merged.resultText)
        assertEquals(t1, merged.arrivedAt)
        assertEquals(t1, merged.updatedAt)                           // для сверки при push
        assertEquals(SyncState.CHANGED, merged.syncState)
    }

    @Test
    fun `чужая исходная заявка обнуляется, порядок — сначала исходные`() {
        val repeat = request(id = 150, parentId = 140)
        val foreign = request(id = 151, parentId = 99)          // 99 нет ни в телефоне, ни в ответе
        val original = request(id = 140)
        val saved = SyncRules.requestsInSaveOrder(listOf(repeat, foreign, original), localIds = setOf(1))
        assertEquals(listOf(140, 150, 151), saved.map { it.id })
        assertEquals(140, saved[1].parentId)
        assertNull(saved[2].parentId)
    }

    // ---------------------------------------------------------------- удаление по assigned_request_ids

    @Test
    fun `снятая с инженера заявка удаляется, назначенная остаётся`() {
        val local = listOf(
            LocalRequestInfo(1, status = 2, parentId = null, hasUnsent = false),   // переназначена
            LocalRequestInfo(2, status = 2, parentId = null, hasUnsent = false),   // есть в списке
            LocalRequestInfo(3, status = 4, parentId = null, hasUnsent = false),   // отменена с подтверждением
        )
        assertEquals(setOf(1, 3), SyncRules.requestsToDelete(local, assigned = setOf(2)))
    }

    @Test
    fun `заявки с результатом работы и неотправленные не удаляются`() {
        val local = listOf(
            LocalRequestInfo(5, status = 5, parentId = null, hasUnsent = false),
            LocalRequestInfo(6, status = 6, parentId = null, hasUnsent = false),
            LocalRequestInfo(7, status = 7, parentId = null, hasUnsent = false),
            LocalRequestInfo(8, status = 8, parentId = null, hasUnsent = false),
            LocalRequestInfo(9, status = 3, parentId = null, hasUnsent = true),
        )
        assertTrue(SyncRules.requestsToDelete(local, assigned = emptySet()).isEmpty())
    }

    @Test
    fun `исходная заявка оставшейся повторной не удаляется`() {
        val local = listOf(
            LocalRequestInfo(10, status = 2, parentId = null, hasUnsent = false),
            LocalRequestInfo(11, status = 2, parentId = 10, hasUnsent = false),
        )
        assertEquals(emptySet<Int>(), SyncRules.requestsToDelete(local, assigned = setOf(11)))
        // если снята и повторная — удаляются обе
        assertEquals(setOf(10, 11), SyncRules.requestsToDelete(local, assigned = emptySet()))
    }

    @Test
    fun `цепочка повторных выездов A ← B ← C — если C остаётся, остаются и B, и A`() {
        val local = listOf(
            LocalRequestInfo(20, status = 2, parentId = null, hasUnsent = false),   // A
            LocalRequestInfo(21, status = 2, parentId = 20, hasUnsent = false),     // B
            LocalRequestInfo(22, status = 5, parentId = 21, hasUnsent = false),     // C — работы выполнены
        )
        // A и B — кандидаты на удаление, но удаление A оставило бы B со ссылкой в никуда
        assertEquals(emptySet<Int>(), SyncRules.requestsToDelete(local, assigned = emptySet()))
        // C в назначенных, B — нет: всё равно цепочка не рвётся
        assertEquals(emptySet<Int>(), SyncRules.requestsToDelete(local.take(2) + local[2].copy(status = 2),
            assigned = setOf(22)))
    }

    @Test
    fun `в цепочке удаляется только то, на что никто из оставшихся не ссылается`() {
        val local = listOf(
            LocalRequestInfo(30, status = 2, parentId = null, hasUnsent = false),
            LocalRequestInfo(31, status = 2, parentId = 30, hasUnsent = false),
            LocalRequestInfo(32, status = 2, parentId = 31, hasUnsent = true),      // есть неотправленное
            LocalRequestInfo(40, status = 2, parentId = null, hasUnsent = false),   // отдельная, снята
        )
        assertEquals(setOf(40), SyncRules.requestsToDelete(local, assigned = emptySet()))
    }

    // ---------------------------------------------------------------- записи заявок из pull

    private data class Mark(val id: String, val requestId: Int, val itemId: Int)

    @Test
    fun `записи сервера — новые и синхронизированные сохраняются, неотправленные не трогаются`() {
        val server = listOf(Mark("a", 1, 1), Mark("b", 1, 2), Mark("c", 1, 3))
        val local = listOf(LocalRecord("b", SyncState.SYNCED), LocalRecord("c", SyncState.CHANGED))
        val picked = SyncRules.pickServerRecords(server, local, idOf = { it.id })
        assertEquals(listOf("a", "b"), picked.save.map { it.id })
        assertTrue(picked.replaceLocalIds.isEmpty())
    }

    @Test
    fun `отметка по тому же пункту с другим id заменяет только синхронизированную`() {
        val server = listOf(Mark("srv-1", 1, 1), Mark("srv-2", 1, 2))
        val local = listOf(
            LocalRecord("loc-1", SyncState.SYNCED, "1:1"),
            LocalRecord("loc-2", SyncState.CHANGED, "1:2"),
        )
        val picked = SyncRules.pickServerRecords(server, local, idOf = { it.id }, keyOf = { "${it.requestId}:${it.itemId}" })
        assertEquals(listOf("srv-1"), picked.save.map { it.id })
        assertEquals(listOf("loc-1"), picked.replaceLocalIds)
    }

    // ---------------------------------------------------------------- ответ push

    private val result = PushResult(
        accepted = setOf("request:137", "photo:p1", "photo:p2", "work_log:w1"),
        rejected = mapOf("act:a1" to "Заявка закрыта на сервере", "checklist_result:c1" to "Заявка переназначена"),
    )

    @Test
    fun `принятая и не изменённая запись помечается отправленной`() {
        val d = SyncRules.decide(SentRecord("request", "137", t0), currentUpdatedAt = t0, result = result)
        assertEquals(PushAction.MARK_SYNCED, d.action)
        assertNull(d.rejectReason)
    }

    @Test
    fun `принятая, но изменённая во время обмена запись остаётся в очереди`() {
        val d = SyncRules.decide(SentRecord("request", "137", t0), currentUpdatedAt = t1, result = result)
        assertEquals(PushAction.KEEP, d.action)
    }

    @Test
    fun `принятое удаление фото стирает запись в телефоне`() {
        val d = SyncRules.decide(SentRecord("photo", "p2", t0, deleted = true), currentUpdatedAt = t0, result = result)
        assertEquals(PushAction.REMOVE_LOCAL, d.action)
    }

    @Test
    fun `отклонённая запись снимается с очереди и попадает в журнал`() {
        val d = SyncRules.decide(SentRecord("act", "a1", t0), currentUpdatedAt = t0, result = result)
        assertEquals(PushAction.MARK_SYNCED, d.action)
        assertEquals("Заявка закрыта на сервере", d.rejectReason)
    }

    @Test
    fun `отклонённая, но уже изменённая запись остаётся в очереди, причина в журнале`() {
        val d = SyncRules.decide(SentRecord("checklist_result", "c1", t0), currentUpdatedAt = t1, result = result)
        assertEquals(PushAction.KEEP, d.action)
        assertEquals("Заявка переназначена", d.rejectReason)
    }

    @Test
    fun `запись без ответа сервера или уже удалённая остаётся как есть`() {
        assertEquals(PushAction.KEEP, SyncRules.decide(SentRecord("work_log", "w2", t0), t0, result).action)
        assertEquals(PushAction.KEEP, SyncRules.decide(SentRecord("work_log", "w1", t0), null, result).action)
    }

    // ---------------------------------------------------------------- обмен-добивка

    @Test
    fun `после успешного обмена с непустой очередью ставится одна добивка`() {
        assertTrue(SyncRules.needsFollowUp(SyncResult.OK, queued = 2, isFollowUp = false))
        // сама добивка следующую не ставит — цикла нет, даже если запись застряла в очереди
        assertFalse(SyncRules.needsFollowUp(SyncResult.OK, queued = 2, isFollowUp = true))
        // очередь пуста — добивка не нужна
        assertFalse(SyncRules.needsFollowUp(SyncResult.OK, queued = 0, isFollowUp = false))
    }

    @Test
    fun `после ошибки обмена добивка не ставится`() {
        for (r in listOf(SyncResult.NETWORK_ERROR, SyncResult.SERVER_ERROR, SyncResult.SESSION_EXPIRED, SyncResult.ERROR)) {
            assertFalse(r.name, SyncRules.needsFollowUp(r, queued = 5, isFollowUp = false))
        }
    }

    // ---------------------------------------------------------------- журнал отказов

    @Test
    fun `отклонённую заявку повторяют в карточке, остальные записи — кнопкой`() {
        assertFalse(SyncRules.canResend(RejectedRecord("request", "137", "З-1", "Заявка отменена", 1)))
        assertTrue(SyncRules.canResend(RejectedRecord("act", "a1", "З-1", "Нет фото", 1)))
        assertFalse(SyncRules.canResend(RejectedRecord("act", "a1", "З-1", "Нет фото", 1, replaced = true)))
    }

    @Test
    fun `повтор удаления фото снова ставит sync_state = 2, остальное — 1`() {
        assertEquals(SyncState.DELETED, SyncRules.requeueState(RejectedRecord("photo", "p1", null, "…", 1, deleted = true)))
        assertEquals(SyncState.CHANGED, SyncRules.requeueState(RejectedRecord("photo", "p1", null, "…", 1)))
        assertEquals(SyncState.CHANGED, SyncRules.requeueState(RejectedRecord("work_log", "w1", null, "…", 1)))
    }

    @Test
    fun `после повтора найденная запись уходит из журнала, пропавшая остаётся с пометкой`() {
        val a = RejectedRecord("photo", "p1", "З-1", "Заявка закрыта", 1)
        val b = RejectedRecord("checklist_result", "c1", "З-1", "Заявка закрыта", 2)
        val journal = listOf(b, a)
        assertEquals(listOf(b), SyncRules.afterRequeue(journal, a, found = true))
        val kept = SyncRules.afterRequeue(journal, a, found = false)
        assertEquals(2, kept.size)
        assertTrue(kept[1].replaced)
        assertFalse(kept[0].replaced)
    }

    @Test
    fun `журнал прежней версии читается, новые поля берутся по умолчанию`() {
        val old = """[{"table":"photo","recordId":"p1","requestNumber":"З-1","reason":"Заявка закрыта","atMs":5}]"""
        val list = ApiJson.decodeFromString(ListSerializer(RejectedRecord.serializer()), old)
        assertEquals(RejectedRecord("photo", "p1", "З-1", "Заявка закрыта", 5), list.single())
        assertFalse(list.single().deleted)
        assertFalse(list.single().replaced)
    }

    @Test
    fun `журнал отказов хранит последние 50, новые сверху`() {
        fun rec(n: Int) = RejectedRecord("photo", "id$n", null, "причина $n", n.toLong())
        val old = (1..49).map(::rec).reversed()
        val journal = SyncRules.appendJournal(old, listOf(rec(50), rec(51)))
        assertEquals(50, journal.size)
        assertEquals("id50", journal[0].recordId)
        assertEquals("id2", journal.last().recordId)   // самая старая (id1) вытеснена
    }
}
