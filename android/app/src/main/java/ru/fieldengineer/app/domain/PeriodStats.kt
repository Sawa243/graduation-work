package ru.fieldengineer.app.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/** Период [from, to) — сутки или месяц по московскому времени. */
data class Period(val from: Instant, val to: Instant)

/** Закрытая заявка для расчёта доли в срок. */
data class ClosedRequest(
    val closedAt: Instant,
    val arrivedAt: Instant?,
    val completedAt: Instant?,
    val reactionDue: Instant,
    val resolutionDue: Instant,
)

data class InTimeShare(val closed: Int, val resolvedPercent: Int?, val reactedPercent: Int?)

/**
 * Показатели за период (проект, раздел 4). Заявки периода — закрытые (статус 6)
 * с closed_at внутри периода; отменённые и приостановленные не учитываются.
 */
object PeriodStats {
    fun day(now: Instant, zone: ZoneId = MOSCOW): Period {
        val date = now.atZone(zone).toLocalDate()
        return Period(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
    }

    fun month(now: Instant, zone: ZoneId = MOSCOW): Period {
        val m = YearMonth.from(now.atZone(zone))
        return Period(m.atDay(1).atStartOfDay(zone).toInstant(), m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())
    }

    fun inTimeShare(requests: List<ClosedRequest>, period: Period): InTimeShare {
        val closed = requests.filter { it.closedAt >= period.from && it.closedAt < period.to }
        return InTimeShare(
            closed = closed.size,
            resolvedPercent = percent(closed.count { it.completedAt != null && it.completedAt <= it.resolutionDue }, closed.size),
            reactedPercent = percent(closed.count { it.arrivedAt != null && it.arrivedAt <= it.reactionDue }, closed.size),
        )
    }

    /** Процент с округлением до целого; при пустом периоде — null («нет данных»). */
    fun percent(part: Int, total: Int): Int? = if (total == 0) null else (part * 100.0 / total).roundToInt()
}
