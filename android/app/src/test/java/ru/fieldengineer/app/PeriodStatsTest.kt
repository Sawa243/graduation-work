package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.fieldengineer.app.domain.ClosedRequest
import ru.fieldengineer.app.domain.PeriodStats
import ru.fieldengineer.app.domain.WorkInterval
import ru.fieldengineer.app.domain.WorkTimeCalculator
import java.time.Instant

class PeriodStatsTest {
    private fun t(s: String) = Instant.parse(s)

    @Test
    fun `сутки и месяц считаются по Москве, а не по часовому поясу устройства`() {
        // 31.10.2026 21:30 UTC = 01.11.2026 00:30 МСК — уже ноябрь
        val now = t("2026-10-31T21:30:00Z")
        val day = PeriodStats.day(now)
        assertEquals(t("2026-10-31T21:00:00Z"), day.from)
        assertEquals(t("2026-11-01T21:00:00Z"), day.to)
        val month = PeriodStats.month(now)
        assertEquals(t("2026-10-31T21:00:00Z"), month.from)
        assertEquals(t("2026-11-30T21:00:00Z"), month.to)
    }

    @Test
    fun `интервал через полночь 31 октября делится между месяцами`() {
        // 31.10 23:30 – 01.11 00:30 по Москве
        val iv = listOf(WorkInterval(WorkTimeCalculator.KIND_WORK, t("2026-10-31T20:30:00Z"), t("2026-10-31T21:30:00Z")))
        val now = t("2026-11-02T00:00:00Z")
        val oct = PeriodStats.month(t("2026-10-15T09:00:00Z"))
        val nov = PeriodStats.month(now)
        assertEquals(30, WorkTimeCalculator.minutes(WorkTimeCalculator.inPeriod(iv, oct.from, oct.to, now)))
        assertEquals(30, WorkTimeCalculator.minutes(WorkTimeCalculator.inPeriod(iv, nov.from, nov.to, now)))
    }

    @Test
    fun `доля в срок — округление и заявки только своего периода`() {
        val due = t("2026-10-07T10:30:00Z")
        fun closed(at: String, completed: String) =
            ClosedRequest(t(at), t("2026-10-05T07:00:00Z"), t(completed), t("2026-10-05T08:30:00Z"), due)
        val list = listOf(
            closed("2026-10-06T10:00:00Z", "2026-10-06T09:00:00Z"),   // в срок
            closed("2026-10-07T10:00:00Z", "2026-10-07T09:00:00Z"),   // в срок
            closed("2026-10-08T10:00:00Z", "2026-10-08T09:00:00Z"),   // просрочено
            closed("2026-09-30T10:00:00Z", "2026-09-30T09:00:00Z"),   // сентябрь — не входит
        )
        val share = PeriodStats.inTimeShare(list, PeriodStats.month(t("2026-10-15T09:00:00Z")))
        assertEquals(3, share.closed)
        assertEquals(67, share.resolvedPercent)    // 2 из 3 = 66,7 → 67
        assertEquals(100, share.reactedPercent)
    }

    @Test
    fun `нет закрытых заявок — нет процента`() {
        assertNull(PeriodStats.percent(0, 0))
        assertEquals(50, PeriodStats.percent(1, 2))
    }
}
