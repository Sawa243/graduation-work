package ru.fieldengineer.app

import org.junit.Assert.assertEquals
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
 * Дополнительная проверка расчётного модуля по алгоритму проекта системы (раздел 8),
 * формулам остатка и признака срока (раздел 4) и правилу рабочего дня (раздел 2.1, таблица 9).
 * Ожидаемые значения посчитаны вручную по шагам алгоритма; расчёт — в комментарии к каждому тесту.
 * SLA-3: реакция 1 080 мин (18 ч), устранение 2 700 мин (45 ч) рабочего времени пн–пт 9:00–18:00.
 */
class SlaCalculatorExtraTest {
    private val noHolidays = SlaCalculator(WorkCalendar(emptyMap()))

    /** Исключения производственного календаря 2026–2027 — раздел calendar_day файла assets/seed.json. */
    private val seedCalendar = SlaCalculator(WorkCalendar(listOf(
        "2026-01-01" to false, "2026-01-02" to false, "2026-01-05" to false, "2026-01-06" to false,
        "2026-01-07" to false, "2026-01-08" to false, "2026-01-09" to false, "2026-02-23" to false,
        "2026-03-09" to false, "2026-05-01" to false, "2026-05-11" to false, "2026-06-12" to false,
        "2026-11-04" to false, "2026-12-31" to false, "2027-01-01" to false, "2027-01-04" to false,
        "2027-01-05" to false, "2027-01-06" to false, "2027-01-07" to false, "2027-01-08" to false,
        "2027-02-20" to true, "2027-02-22" to false, "2027-02-23" to false, "2027-03-08" to false,
        "2027-05-03" to false, "2027-05-10" to false, "2027-06-14" to false, "2027-11-04" to false,
        "2027-11-05" to false, "2027-12-31" to false,
    ).associate { LocalDate.parse(it.first) to it.second }))

    private fun msk(text: String): Instant = LocalDateTime.parse(text).atZone(MOSCOW).toInstant()

    private val work = SlaCalculator.MODE_WORK_HOURS
    private val round = SlaCalculator.MODE_24X7

    private val sla3Reaction = 1080
    private val sla3Resolution = 2700

    // ------------------------------------------------ SLA-3: сроки

    @Test
    fun `SLA-3 через выходные без праздников`() {
        // Чт 01.10.2026 14:00. Реакция 1080: чт 14–18 = 240, R = 840; пт 540, R = 300;
        // сб, вс пропущены; пн 05.10 9:00 + 300 = 14:00.
        // Устранение 2700: чт 240 → 2460; пт 540 → 1920; пн 540 → 1380; вт 540 → 840;
        // ср 540 → 300; чт 08.10 9:00 + 300 = 14:00 (ровно 5 рабочих дней).
        val t0 = msk("2026-10-01T14:00")
        assertEquals(msk("2026-10-05T14:00"), noHolidays.due(t0, sla3Reaction, work))
        assertEquals(msk("2026-10-08T14:00"), noHolidays.due(t0, sla3Resolution, work))
    }

    @Test
    fun `SLA-3 срок ровно в 18-00 не переносится на следующее утро`() {
        // Пн 05.10.2026 9:00. Реакция 1080 = 540 + 540: пн 540, R = 540; вт A = 540, R ≤ A (п. 7)
        // → вт 06.10 18:00. Устранение 2700 = 5 × 540 → пт 09.10 18:00.
        val t0 = msk("2026-10-05T09:00")
        assertEquals(msk("2026-10-06T18:00"), noHolidays.due(t0, sla3Reaction, work))
        assertEquals(msk("2026-10-09T18:00"), noHolidays.due(t0, sla3Resolution, work))
    }

    @Test
    fun `SLA-3 через 1 мая 2026 по календарю seed`() {
        // Чт 30.04.2026 12:00. Реакция 1080: чт 12–18 = 360, R = 720; пт 01.05 праздник, сб, вс;
        // пн 04.05 (в исключениях нет, будний) 540, R = 180; вт 05.05 9:00 + 180 = 12:00.
        // Устранение 2700: чт 360 → 2340; пн 04.05 540 → 1800; вт 540 → 1260; ср 540 → 720;
        // чт 07.05 540 → 180; пт 08.05 9:00 + 180 = 12:00.
        val t0 = msk("2026-04-30T12:00")
        assertEquals(msk("2026-05-05T12:00"), seedCalendar.due(t0, sla3Reaction, work))
        assertEquals(msk("2026-05-08T12:00"), seedCalendar.due(t0, sla3Resolution, work))
    }

    @Test
    fun `SLA-3 через 9-11 мая 2026 по календарю seed`() {
        // Ср 06.05.2026 15:00. Реакция 1080: ср 180, R = 900; чт 540, R = 360; пт 08.05 9:00 + 360 = 15:00.
        // Устранение 2700: ср 180 → 2520; чт 540 → 1980; пт 540 → 1440; сб 09, вс 10, пн 11.05 (перенос)
        // пропущены; вт 12.05 540 → 900; ср 13.05 540 → 360; чт 14.05 9:00 + 360 = 15:00.
        val t0 = msk("2026-05-06T15:00")
        assertEquals(msk("2026-05-08T15:00"), seedCalendar.due(t0, sla3Reaction, work))
        assertEquals(msk("2026-05-14T15:00"), seedCalendar.due(t0, sla3Resolution, work))
    }

    @Test
    fun `SLA-3 через майские 2027 — выходные 3 и 10 мая по календарю seed`() {
        // Пт 30.04.2027 16:00. Реакция 1080: пт 120, R = 960; сб 01, вс 02, пн 03.05 выходной;
        // вт 04.05 540, R = 420; ср 05.05 9:00 + 420 = 16:00.
        // Устранение 2700: пт 120 → 2580; вт 04 540 → 2040; ср 05 → 1500; чт 06 → 960; пт 07 → 420;
        // сб 08, вс 09, пн 10.05 выходной; вт 11.05 9:00 + 420 = 16:00.
        val t0 = msk("2027-04-30T16:00")
        assertEquals(msk("2027-05-05T16:00"), seedCalendar.due(t0, sla3Reaction, work))
        assertEquals(msk("2027-05-11T16:00"), seedCalendar.due(t0, sla3Resolution, work))
    }

    @Test
    fun `SLA-3 с рабочей субботой 20 февраля 2027 и праздниками 22-23 февраля`() {
        // Пт 19.02.2027 10:00. Реакция 1080: пт 480, R = 600; сб 20.02 рабочая 540, R = 60;
        // вс, пн 22 и вт 23 — нерабочие; ср 24.02 9:00 + 60 = 10:00.
        // Устранение 2700: пт 480 → 2220; сб 540 → 1680; ср 24 540 → 1140; чт 25 → 600; пт 26 → 60;
        // сб 27, вс 28 пропущены; пн 01.03 9:00 + 60 = 10:00.
        val t0 = msk("2027-02-19T10:00")
        assertEquals(msk("2027-02-24T10:00"), seedCalendar.due(t0, sla3Reaction, work))
        assertEquals(msk("2027-03-01T10:00"), seedCalendar.due(t0, sla3Resolution, work))
    }

    // ------------------------------------------------ SLA-2: регистрация в праздник и на границе дня

    @Test
    fun `регистрация в праздник 1 мая 2026 — отсчёт с 9-00 ближайшего рабочего дня`() {
        // Пт 01.05.2026 10:00 — праздник; п. 4: сб, вс нерабочие → пн 04.05 9:00.
        // Реакция 240: пн 9:00 + 240 = 13:00. Устранение 1440: пн 540 → 900; вт 540 → 360;
        // ср 06.05 9:00 + 360 = 15:00.
        val t0 = msk("2026-05-01T10:00")
        assertEquals(msk("2026-05-04T13:00"), seedCalendar.due(t0, 240, work))
        assertEquals(msk("2026-05-06T15:00"), seedCalendar.due(t0, 1440, work))
    }

    @Test
    fun `регистрация в 17-59 и в 18-01 пятницы`() {
        // Пт 02.10.2026 17:59: A = 1 мин, R = 239; пн 05.10 9:00 + 239 = 12:59.
        // Пт 18:01: время ≥ 18:00 (п. 4) → пн 9:00; 9:00 + 240 = 13:00.
        assertEquals(msk("2026-10-05T12:59"), noHolidays.due(msk("2026-10-02T17:59"), 240, work))
        assertEquals(msk("2026-10-05T13:00"), noHolidays.due(msk("2026-10-02T18:01"), 240, work))
    }

    @Test
    fun `SLA-1 круглосуточно не замечает праздников`() {
        // Чт 31.12.2026 22:00 (нерабочий день по seed) + 120 = пт 01.01.2027 00:00; + 480 = 06:00.
        val t0 = msk("2026-12-31T22:00")
        assertEquals(msk("2027-01-01T00:00"), seedCalendar.due(t0, 120, round))
        assertEquals(msk("2027-01-01T06:00"), seedCalendar.due(t0, 480, round))
    }

    // ------------------------------------------------ остаток

    @Test
    fun `остаток через выходные и 1 мая 2026`() {
        // Сейчас чт 30.04.2026 17:00, срок вт 05.05 12:00 (рабочее время):
        // чт 17–18 = 60; пт 01.05 праздник, сб, вс — 0; пн 04.05 540; вт 9–12 = 180. Итого 780.
        val r = seedCalendar.remaining(msk("2026-04-30T17:00"), msk("2026-05-05T12:00"), work)
        assertEquals(Duration.ofMinutes(780), r)
        // В выходной остаток не убывает. Срок пн 05.10.2026 11:30. Из сб 10:00 и из вс 20:00 рабочих минут до срока одинаково:
        // сб, вс — 0; пн 9:00–11:30 = 150.
        val due = msk("2026-10-05T11:30")
        assertEquals(Duration.ofMinutes(150), noHolidays.remaining(msk("2026-10-03T10:00"), due, work))
        assertEquals(Duration.ofMinutes(150), noHolidays.remaining(msk("2026-10-04T20:00"), due, work))
    }

    @Test
    fun `просрочка через праздники 9-11 мая 2026`() {
        // Срок пт 08.05.2026 17:00, сейчас вт 12.05 10:00: пт 17–18 = 60; сб, вс, пн 11.05 — 0;
        // вт 9–10 = 60. Просрочено на 120 мин рабочего времени → остаток −120.
        val r = seedCalendar.remaining(msk("2026-05-12T10:00"), msk("2026-05-08T17:00"), work)
        assertEquals(Duration.ofMinutes(-120), r)
    }

    @Test
    fun `остаток SLA-1 считается круглосуточно`() {
        // Пт 02.10.2026 17:00 → пн 05.10 11:30: 2 сут 18 ч 30 мин = 3990 мин (выходные входят).
        // После срока: сб 03.10 01:00 при сроке сб 00:30 → −30.
        assertEquals(Duration.ofMinutes(3990),
            noHolidays.remaining(msk("2026-10-02T17:00"), msk("2026-10-05T11:30"), round))
        assertEquals(Duration.ofMinutes(-30),
            noHolidays.remaining(msk("2026-10-03T01:00"), msk("2026-10-03T00:30"), round))
    }

    @Test
    fun `в момент срока остаток ровно 0 и заявка просрочена`() {
        // Раздел 4: «просрочена», если остаток ≤ 0. В момент T остаток = 0 в обоих режимах.
        val due = msk("2026-10-05T11:30")
        val rWork = noHolidays.remaining(due, due, work)
        val rRound = noHolidays.remaining(due, due, round)
        assertEquals(Duration.ZERO, rWork)
        assertEquals(Duration.ZERO, rRound)
        assertEquals(SlaState.OVERDUE, noHolidays.state(rWork, 240))
        // Срок пт 18:00, сейчас сб 12:00: рабочих минут между ними 0 → остаток 0, просрочена.
        val rWeekend = noHolidays.remaining(msk("2026-10-03T12:00"), msk("2026-10-02T18:00"), work)
        assertEquals(Duration.ZERO, rWeekend)
        assertEquals(SlaState.OVERDUE, noHolidays.state(rWeekend, 240))
    }

    // ------------------------------------------------ признак срока SLA-3

    @Test
    fun `признак SLA-3 — граница 25 процентов`() {
        // Реакция 1080: 0,25 × 1080 = 270. «Под угрозой» — строго остаток < 270.
        assertEquals(SlaState.IN_TIME, noHolidays.state(Duration.ofMinutes(270), sla3Reaction))
        assertEquals(SlaState.AT_RISK, noHolidays.state(Duration.ofMinutes(269), sla3Reaction))
        assertEquals(SlaState.AT_RISK, noHolidays.state(Duration.ofMinutes(270).minusSeconds(1), sla3Reaction))
        // Устранение 2700: 0,25 × 2700 = 675.
        assertEquals(SlaState.IN_TIME, noHolidays.state(Duration.ofMinutes(675), sla3Resolution))
        assertEquals(SlaState.AT_RISK, noHolidays.state(Duration.ofMinutes(674), sla3Resolution))
    }

    @Test
    fun `индикатор SLA-3 через 1 мая 2026`() {
        // Регистрация чт 30.04.2026 12:00: реакция вт 05.05 12:00, устранение пт 08.05 12:00 (см. выше).
        val reaction = msk("2026-05-05T12:00")
        val resolution = msk("2026-05-08T12:00")
        // Пн 04.05 12:00, до прибытия: пн 360 + вт 180 = 540 ≥ 270 → в срок.
        val a = seedCalendar.indicator(msk("2026-05-04T12:00"), reaction, resolution, null, null,
            sla3Reaction, sla3Resolution, work)
        assertEquals(SlaStage.REACTION, a.stage)
        assertEquals(Duration.ofMinutes(540), a.remaining)
        assertEquals(SlaState.IN_TIME, a.state)
        // Пн 04.05 17:00: 60 + 180 = 240 < 270 → под угрозой.
        val b = seedCalendar.indicator(msk("2026-05-04T17:00"), reaction, resolution, null, null,
            sla3Reaction, sla3Resolution, work)
        assertEquals(Duration.ofMinutes(240), b.remaining)
        assertEquals(SlaState.AT_RISK, b.state)
        // Прибыл вт 05.05 11:00; сейчас чт 07.05 10:00 → до устранения: чт 480 + пт 180 = 660 < 675
        // → под угрозой по этапу устранения.
        val c = seedCalendar.indicator(msk("2026-05-07T10:00"), reaction, resolution,
            msk("2026-05-05T11:00"), null, sla3Reaction, sla3Resolution, work)
        assertEquals(SlaStage.RESOLUTION, c.stage)
        assertEquals(Duration.ofMinutes(660), c.remaining)
        assertEquals(SlaState.AT_RISK, c.state)
    }
}
