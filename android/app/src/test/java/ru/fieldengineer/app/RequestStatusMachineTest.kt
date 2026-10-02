package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.domain.ChecklistCheck
import ru.fieldengineer.app.domain.Moment
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.domain.RequestStatusMachine
import ru.fieldengineer.app.domain.TransitionResult
import ru.fieldengineer.app.domain.WorkTimeCalculator
import java.time.Instant

class RequestStatusMachineTest {
    private val now = Moment(Instant.parse("2026-10-05T08:20:00Z"), trusted = true)

    private fun item(id: Int, required: Boolean, needPhoto: Boolean, done: Boolean, photos: Int = 0) =
        ChecklistCheck(id, "Пункт $id", required, needPhoto, done, photos)

    private fun go(from: Int, to: Int, checklist: List<ChecklistCheck> = emptyList(), text: String? = "Сделано") =
        RequestStatusMachine.transition(from, to, checklist, text, now)

    @Test
    fun `основной путь 2-3-4-5 открывает и закрывает интервалы`() {
        val road = go(RequestStatus.ASSIGNED, RequestStatus.ON_THE_WAY) as TransitionResult.Allowed
        assertEquals(WorkTimeCalculator.KIND_ROAD, road.changes.openIntervalKind)

        val site = go(RequestStatus.ON_THE_WAY, RequestStatus.ON_SITE) as TransitionResult.Allowed
        assertTrue(site.changes.setArrivedAt)
        assertTrue(site.changes.closeOpenIntervals)
        assertEquals(WorkTimeCalculator.KIND_WORK, site.changes.openIntervalKind)

        val done = go(RequestStatus.ON_SITE, RequestStatus.WORK_DONE, listOf(item(1, true, false, true)))
            as TransitionResult.Allowed
        assertTrue(done.changes.setCompletedAt)
        assertTrue(done.changes.closeOpenIntervals)
        assertEquals(null, done.changes.openIntervalKind)
    }

    @Test
    fun `нельзя перескочить статус или вернуться назад`() {
        assertTrue(go(RequestStatus.ASSIGNED, RequestStatus.ON_SITE) is TransitionResult.Denied)
        assertTrue(go(RequestStatus.ON_SITE, RequestStatus.ON_THE_WAY) is TransitionResult.Denied)
        assertTrue(go(RequestStatus.NEW, RequestStatus.ON_THE_WAY) is TransitionResult.Denied)
    }

    @Test
    fun `после закрытия и отмены переходов нет и заявка заблокирована`() {
        assertTrue(RequestStatusMachine.targetsFor(RequestStatus.CLOSED).isEmpty())
        assertTrue(RequestStatusMachine.targetsFor(RequestStatus.CANCELLED).isEmpty())
        assertTrue(RequestStatusMachine.isLocked(RequestStatus.CLOSED))
        assertTrue(RequestStatusMachine.isLocked(RequestStatus.CANCELLED))
        assertFalse(RequestStatusMachine.isLocked(RequestStatus.WORK_DONE))
    }

    @Test
    fun `работы выполнены — не отмечен обязательный пункт`() {
        val r = go(RequestStatus.ON_SITE, RequestStatus.WORK_DONE, listOf(item(1, true, false, false)))
        assertEquals(listOf("Не отмечен обязательный пункт: Пункт 1"), (r as TransitionResult.Denied).reasons)
    }

    @Test
    fun `работы выполнены — нет фото к отмеченному пункту с need_photo`() {
        val r = go(RequestStatus.ON_SITE, RequestStatus.WORK_DONE, listOf(item(2, true, true, true, photos = 0)))
        assertEquals(listOf("Нужно фото к пункту: Пункт 2"), (r as TransitionResult.Denied).reasons)
        val ok = go(RequestStatus.ON_SITE, RequestStatus.WORK_DONE, listOf(item(2, true, true, true, photos = 1)))
        assertTrue(ok is TransitionResult.Allowed)
    }

    @Test
    fun `пропущенный необязательный пункт с need_photo фото не требует`() {
        val r = go(RequestStatus.ON_SITE, RequestStatus.WORK_DONE, listOf(item(3, false, true, false)))
        assertTrue(r is TransitionResult.Allowed)
    }

    @Test
    fun `работы выполнены — пустое «Что сделано» и все причины сразу`() {
        val r = go(RequestStatus.ON_SITE, RequestStatus.WORK_DONE,
            listOf(item(1, true, false, false), item(2, false, true, true)), text = "  ")
        assertEquals(3, (r as TransitionResult.Denied).reasons.size)
    }

    @Test
    fun `приостановка требует описания и закрывает интервалы`() {
        assertTrue(go(RequestStatus.ON_SITE, RequestStatus.SUSPENDED, text = null) is TransitionResult.Denied)
        val r = go(RequestStatus.ON_SITE, RequestStatus.SUSPENDED, text = "Ждём блок питания") as TransitionResult.Allowed
        assertTrue(r.changes.closeOpenIntervals)
        assertFalse(r.changes.setCompletedAt)
    }

    @Test
    fun `чек-лист меняется только на объекте`() {
        assertTrue(RequestStatusMachine.canEditWork(RequestStatus.ON_SITE))
        assertFalse(RequestStatusMachine.canEditWork(RequestStatus.ON_THE_WAY))
        assertFalse(RequestStatusMachine.canEditWork(RequestStatus.WORK_DONE))
    }

    @Test
    fun `пустой чек-лист с заполненным описанием не мешает завершить работы`() {
        assertTrue(go(RequestStatus.ON_SITE, RequestStatus.WORK_DONE, emptyList()) is TransitionResult.Allowed)
    }

    @Test
    fun `неотмеченный обязательный пункт с фото даёт одну причину, а не две`() {
        val r = go(RequestStatus.ON_SITE, RequestStatus.WORK_DONE, listOf(item(5, true, true, false)))
        assertEquals(listOf("Не отмечен обязательный пункт: Пункт 5"), (r as TransitionResult.Denied).reasons)
    }

    @Test
    fun `признак доверенного времени доходит до изменений`() {
        val untrusted = Moment(Instant.parse("2026-10-05T08:20:00Z"), trusted = false)
        val r = RequestStatusMachine.transition(RequestStatus.ON_THE_WAY, RequestStatus.ON_SITE, emptyList(), null, untrusted)
        assertFalse((r as TransitionResult.Allowed).changes.at.trusted)
    }
}
