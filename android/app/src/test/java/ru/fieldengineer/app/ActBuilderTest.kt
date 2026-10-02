package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.domain.ActBuilder
import ru.fieldengineer.app.domain.ActInput
import ru.fieldengineer.app.domain.Moment
import ru.fieldengineer.app.domain.PinRules
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.domain.RequestStatusMachine
import ru.fieldengineer.app.domain.TransitionResult
import ru.fieldengineer.app.domain.WorkInterval
import ru.fieldengineer.app.domain.WorkTimeCalculator.KIND_ROAD
import ru.fieldengineer.app.domain.WorkTimeCalculator.KIND_WORK
import java.time.Instant
import java.time.LocalDate

class ActBuilderTest {
    private fun t(s: String) = Instant.parse(s)

    private val base = ActInput(
        requestNumber = "З-2610-0137", actDate = LocalDate.parse("2026-10-07"),
        executorName = "ООО «Исполнитель»", customerName = "ООО «Заказчик»", customerInn = "7700000022",
        contractNumber = "СО-2026-021", siteAddress = "г. Москва, Проектный б-р, д. 8",
        workTypeCode = "302", workTypeName = "Замена коммутатора доступа", equipment = "Eltex MES2428",
        resultText = "Коммутатор заменён", doneItems = listOf("Проверить индикацию портов" to null),
        intervals = listOf(
            WorkInterval(KIND_ROAD, t("2026-10-07T06:00:00Z"), t("2026-10-07T06:40:00Z")),   // 40 мин дороги
            WorkInterval(KIND_WORK, t("2026-10-07T06:40:00Z"), t("2026-10-07T08:25:00Z")),   // 105 мин работы
        ),
        completedAt = t("2026-10-07T08:25:00Z"),
        engineerName = "Тестов И.П.", engineerPosition = "Инженер выездной службы",
        signMode = ActBuilder.SIGN_ON_SCREEN, signerName = "Лебедева М.У.", signerPosition = "Начальник ИТ-отдела",
        hasSignature = true,
    )

    @Test
    fun `номер акта производный от номера заявки`() {
        assertEquals("А-2610-0137", ActBuilder.actNumber("З-2610-0137"))
    }

    @Test
    fun `в акт идут только минуты работы, дорога не входит`() {
        assertEquals(105, ActBuilder.workMinutes(base.intervals, base.completedAt))
        assertEquals(105, ActBuilder.build(base).workMin)
    }

    @Test
    fun `полный акт на экране проходит проверку реквизитов`() {
        assertTrue(ActBuilder.missingRequisites(base).isEmpty())
    }

    @Test
    fun `без подписи и данных представителя заказчика акт на экране не подписать`() {
        val r = ActBuilder.missingRequisites(base.copy(signerName = null, signerPosition = " ", hasSignature = false))
        assertEquals(listOf("ФИО представителя заказчика", "должность представителя заказчика",
            "подпись представителя заказчика"), r)
    }

    @Test
    fun `бумажному акту подпись на экране не нужна`() {
        val paper = base.copy(signMode = ActBuilder.SIGN_ON_PAPER, signerName = null, signerPosition = null, hasSignature = false)
        assertTrue(ActBuilder.missingRequisites(paper).isEmpty())
    }

    @Test
    fun `нет описания работ и нулевые трудозатраты — реквизиты не заполнены`() {
        val r = ActBuilder.missingRequisites(base.copy(resultText = "", intervals = emptyList()))
        assertEquals(listOf("содержание факта — что сделано", "величина — трудозатраты на объекте"), r)
    }

    @Test
    fun `работа короче минуты — в акте 1 минута, начатая минута считается целой`() {
        val end = base.completedAt!!
        val start = end.minusSeconds(34)
        val short = listOf(WorkInterval(KIND_WORK, start, end))
        assertEquals(1, ActBuilder.workMinutes(short, end))
        assertTrue(ActBuilder.missingRequisites(base.copy(intervals = short)).isEmpty())
        // ровно 2 минуты — 2, 2 минуты и 1 секунда — 3
        val two = listOf(WorkInterval(KIND_WORK, end.minusSeconds(120), end))
        assertEquals(2, ActBuilder.workMinutes(two, end))
        val twoPlus = listOf(WorkInterval(KIND_WORK, end.minusSeconds(121), end))
        assertEquals(3, ActBuilder.workMinutes(twoPlus, end))
    }

    @Test
    fun `замечание к пункту чек-листа попадает в акт`() {
        val c = ActBuilder.build(base.copy(doneItems = listOf("Сохранить конфигурацию" to "на USB")))
        assertEquals(listOf("Сохранить конфигурацию (на USB)"), c.doneItems)
    }

    @Test
    fun `закрыть заявку можно только из «Работы выполнены» и с готовым актом`() {
        val now = Moment(t("2026-10-07T08:40:00Z"), true)
        assertTrue(RequestStatusMachine.close(RequestStatus.ON_SITE, true, now) is TransitionResult.Denied)
        assertTrue(RequestStatusMachine.close(RequestStatus.WORK_DONE, false, now) is TransitionResult.Denied)
        val ok = RequestStatusMachine.close(RequestStatus.WORK_DONE, true, now) as TransitionResult.Allowed
        assertEquals(RequestStatus.CLOSED, ok.changes.newStatus)
        assertTrue(ok.changes.setClosedAt)
    }

    @Test
    fun `простые PIN не принимаются`() {
        assertEquals(null, PinRules.validate("4071"))
        assertTrue(PinRules.validate("123") != null)
        assertTrue(PinRules.validate("1111") != null)
        assertTrue(PinRules.validate("1234") != null)
        assertTrue(PinRules.validate("12a4") != null)
    }

    @Test
    fun `акт готов к закрытию только при полной подписи`() {
        val at = t("2026-10-07T08:30:00Z")
        assertTrue(ActBuilder.isReady(ActBuilder.SIGN_ON_SCREEN, true, at, 0))
        assertTrue(!ActBuilder.isReady(ActBuilder.SIGN_ON_SCREEN, false, at, 0))     // нет изображения подписи
        assertTrue(!ActBuilder.isReady(ActBuilder.SIGN_ON_SCREEN, true, null, 0))    // нет времени подписи
        assertTrue(ActBuilder.isReady(ActBuilder.SIGN_ON_PAPER, false, null, 1))
        assertTrue(!ActBuilder.isReady(ActBuilder.SIGN_ON_PAPER, false, null, 0))    // нет фото бумажного акта
    }
}
