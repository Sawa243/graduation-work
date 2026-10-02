package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fieldengineer.app.domain.WorkInterval
import ru.fieldengineer.app.domain.WorkTimeCalculator
import ru.fieldengineer.app.domain.WorkTimeCalculator.KIND_ROAD
import ru.fieldengineer.app.domain.WorkTimeCalculator.KIND_WORK
import java.time.Duration
import java.time.Instant

class WorkTimeCalculatorTest {
    private fun t(s: String) = Instant.parse(s)
    private fun min(d: Duration) = WorkTimeCalculator.minutes(d)

    private val intervals = listOf(
        WorkInterval(KIND_ROAD, t("2026-10-05T06:10:00Z"), t("2026-10-05T06:55:00Z")),   // 45 мин
        WorkInterval(KIND_WORK, t("2026-10-05T06:55:00Z"), t("2026-10-05T09:05:00Z")),   // 130 мин
    )

    @Test
    fun `дорога и работа считаются раздельно`() {
        val now = t("2026-10-05T12:00:00Z")
        assertEquals(45, min(WorkTimeCalculator.total(intervals, now, KIND_ROAD)))
        assertEquals(130, min(WorkTimeCalculator.total(intervals, now, KIND_WORK)))
        assertEquals(175, min(WorkTimeCalculator.total(intervals, now)))
    }

    @Test
    fun `незакрытый интервал считается до текущего момента`() {
        val open = intervals + WorkInterval(KIND_WORK, t("2026-10-05T10:00:00Z"), null)
        assertEquals(130 + 25, min(WorkTimeCalculator.total(open, t("2026-10-05T10:25:00Z"), KIND_WORK)))
    }

    @Test
    fun `переход через полночь обрезается границами суток`() {
        // работа 23:30–01:15 UTC: в первые сутки 30 мин, во вторые 75 мин
        val night = listOf(WorkInterval(KIND_WORK, t("2026-10-05T23:30:00Z"), t("2026-10-06T01:15:00Z")))
        val now = t("2026-10-07T00:00:00Z")
        assertEquals(30, min(WorkTimeCalculator.inPeriod(night, t("2026-10-05T00:00:00Z"), t("2026-10-06T00:00:00Z"), now)))
        assertEquals(75, min(WorkTimeCalculator.inPeriod(night, t("2026-10-06T00:00:00Z"), t("2026-10-07T00:00:00Z"), now)))
    }

    @Test
    fun `переход через месяц обрезается границами месяца`() {
        val iv = listOf(WorkInterval(KIND_WORK, t("2026-09-30T22:00:00Z"), t("2026-10-01T02:00:00Z")))
        val now = t("2026-10-02T00:00:00Z")
        assertEquals(120, min(WorkTimeCalculator.inPeriod(iv, t("2026-09-01T00:00:00Z"), t("2026-10-01T00:00:00Z"), now)))
        assertEquals(120, min(WorkTimeCalculator.inPeriod(iv, t("2026-10-01T00:00:00Z"), t("2026-11-01T00:00:00Z"), now)))
    }

    @Test
    fun `интервал вне периода не учитывается`() {
        assertEquals(0, min(WorkTimeCalculator.inPeriod(intervals, t("2026-10-06T00:00:00Z"),
            t("2026-10-07T00:00:00Z"), t("2026-10-08T00:00:00Z"))))
    }

    @Test
    fun `незакрытый интервал в периоде считается до текущего момента`() {
        val open = listOf(WorkInterval(KIND_ROAD, t("2026-10-05T08:00:00Z"), null))
        assertEquals(40, min(WorkTimeCalculator.inPeriod(open, t("2026-10-05T00:00:00Z"),
            t("2026-10-06T00:00:00Z"), t("2026-10-05T08:40:00Z"))))
    }
}
