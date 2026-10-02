package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fieldengineer.app.domain.MOSCOW
import ru.fieldengineer.app.domain.SlaCalculator
import ru.fieldengineer.app.domain.SlaStage
import ru.fieldengineer.app.domain.SlaState
import ru.fieldengineer.app.domain.WorkCalendar
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Контрольный пример проекта системы (раздел 8) и граничные случаи из плана разработки (И2).
 * Уровни SLA — легенда 6.1: SLA-1 120/480 мин 24×7, SLA-2 240/1440 мин рабочего времени.
 */
class SlaCalculatorTest {
    private val noHolidays = SlaCalculator(WorkCalendar(emptyMap()))

    private fun msk(text: String): Instant = LocalDateTime.parse(text).atZone(MOSCOW).toInstant()
    private fun calc(vararg days: Pair<String, Boolean>) =
        SlaCalculator(WorkCalendar(days.associate { LocalDate.parse(it.first) to it.second }))

    private val work = SlaCalculator.MODE_WORK_HOURS
    private val round = SlaCalculator.MODE_24X7

    // ------------------------------------------------ контрольный пример

    @Test
    fun `контрольный пример SLA-2 пятница 16-30`() {
        val t0 = msk("2026-10-02T16:30")
        assertEquals(msk("2026-10-05T11:30"), noHolidays.due(t0, 240, work))   // реакция — пн 11:30
        assertEquals(msk("2026-10-07T13:30"), noHolidays.due(t0, 1440, work))  // устранение — ср 13:30
    }

    @Test
    fun `праздник в понедельник сдвигает сроки на день`() {
        val c = calc("2026-10-05" to false)
        val t0 = msk("2026-10-02T16:30")
        assertEquals(msk("2026-10-06T11:30"), c.due(t0, 240, work))    // вт 11:30
        assertEquals(msk("2026-10-08T13:30"), c.due(t0, 1440, work))   // чт 13:30
    }

    @Test
    fun `SLA-1 считается круглосуточно`() {
        val t0 = msk("2026-10-02T16:30")
        assertEquals(msk("2026-10-02T18:30"), noHolidays.due(t0, 120, round))  // пт 18:30
        assertEquals(msk("2026-10-03T00:30"), noHolidays.due(t0, 480, round))  // сб 00:30
    }

    // ------------------------------------------------ граничные случаи

    @Test
    fun `регистрация ровно в 18-00 — отсчёт с 9-00 следующего рабочего дня`() {
        assertEquals(msk("2026-10-05T13:00"), noHolidays.due(msk("2026-10-02T18:00"), 240, work))
    }

    @Test
    fun `регистрация ровно в 9-00 — отсчёт сразу`() {
        assertEquals(msk("2026-10-01T13:00"), noHolidays.due(msk("2026-10-01T09:00"), 240, work))
    }

    @Test
    fun `регистрация до начала рабочего дня`() {
        assertEquals(msk("2026-10-01T13:00"), noHolidays.due(msk("2026-10-01T07:15"), 240, work))
    }

    @Test
    fun `регистрация в выходной`() {
        assertEquals(msk("2026-10-05T13:00"), noHolidays.due(msk("2026-10-03T12:00"), 240, work))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `нулевое время SLA — ошибка входных данных`() {
        noHolidays.due(msk("2026-10-02T16:30"), 0, work)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `отрицательное время SLA — ошибка входных данных`() {
        noHolidays.due(msk("2026-10-02T16:30"), -5, round)
    }

    @Test
    fun `рабочая суббота 20 февраля 2027`() {
        val c = calc("2027-02-20" to true, "2027-02-22" to false, "2027-02-23" to false)
        // пт 16:30 → 90 мин в пятницу, остальные 150 — в рабочую субботу
        assertEquals(msk("2027-02-20T11:30"), c.due(msk("2027-02-19T16:30"), 240, work))
    }

    @Test
    fun `переход через Новый год с каникулами`() {
        val c = calc(
            "2026-12-31" to false, "2027-01-01" to false, "2027-01-04" to false, "2027-01-05" to false,
            "2027-01-06" to false, "2027-01-07" to false, "2027-01-08" to false,
        )
        // ср 30.12 16:00: 120 мин; дальше 11.01 — 540, 12.01 — 540, 13.01 — ещё 240 → 13:00
        assertEquals(msk("2027-01-13T13:00"), c.due(msk("2026-12-30T16:00"), 1440, work))
    }

    @Test
    fun `срок ровно 18-00, если время укладывается в день без остатка`() {
        assertEquals(msk("2026-10-02T18:00"), noHolidays.due(msk("2026-10-02T14:00"), 240, work))
    }

    @Test
    fun `секунды в моменте регистрации не теряются`() {
        assertEquals(msk("2026-10-05T11:30:20"), noHolidays.due(msk("2026-10-02T16:30:20"), 240, work))
    }

    // ------------------------------------------------ остаток и признак

    @Test
    fun `остаток считается в минутах режима`() {
        // пт 17:00 → пн 11:30: 60 мин в пятницу + 150 в понедельник
        val r = noHolidays.remaining(msk("2026-10-02T17:00"), msk("2026-10-05T11:30"), work)
        assertEquals(Duration.ofMinutes(210), r)
    }

    @Test
    fun `после срока остаток отрицательный`() {
        val r = noHolidays.remaining(msk("2026-10-05T12:00"), msk("2026-10-05T11:30"), work)
        assertEquals(Duration.ofMinutes(-30), r)
    }

    @Test
    fun `признак срока — граница 25 процентов`() {
        assertEquals(SlaState.IN_TIME, noHolidays.state(Duration.ofMinutes(60), 240))
        assertEquals(SlaState.AT_RISK, noHolidays.state(Duration.ofMinutes(59), 240))
        assertEquals(SlaState.OVERDUE, noHolidays.state(Duration.ZERO, 240))
        assertEquals(SlaState.OVERDUE, noHolidays.state(Duration.ofMinutes(-1), 240))
    }

    @Test
    fun `индикатор до прибытия — по реакции, после — по устранению`() {
        val reaction = msk("2026-10-05T11:30")
        val resolution = msk("2026-10-07T13:30")
        val before = noHolidays.indicator(msk("2026-10-05T11:00"), reaction, resolution, null, null, 240, 1440, work)
        assertEquals(SlaStage.REACTION, before.stage)
        assertEquals(SlaState.AT_RISK, before.state)   // 30 мин < 60 мин
        val after = noHolidays.indicator(msk("2026-10-05T12:00"), reaction, resolution,
            msk("2026-10-05T11:20"), null, 240, 1440, work)
        assertEquals(SlaStage.RESOLUTION, after.stage)
        assertEquals(SlaState.IN_TIME, after.state)
    }

    @Test
    fun `после выполнения работ признак — уложились ли в срок устранения`() {
        val due = msk("2026-10-07T13:30")
        val ok = noHolidays.indicator(msk("2026-10-09T10:00"), msk("2026-10-05T11:30"), due,
            msk("2026-10-05T11:00"), msk("2026-10-07T13:30"), 240, 1440, work)
        assertEquals(SlaState.IN_TIME, ok.state)
        assertNull(ok.remaining)
        val late = noHolidays.indicator(msk("2026-10-09T10:00"), msk("2026-10-05T11:30"), due,
            msk("2026-10-05T11:00"), msk("2026-10-07T13:31"), 240, 1440, work)
        assertEquals(SlaState.OVERDUE, late.state)
    }
}
