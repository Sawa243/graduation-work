package ru.fieldengineer.app.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Сроки SLA считаются по московскому времени (проект системы, раздел 1). */
val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

/**
 * Производственный календарь, заданный исключениями (таблица calendar_day):
 * день рабочий, если он есть в исключениях с is_working = true,
 * или если его там нет и это понедельник–пятница.
 */
class WorkCalendar(private val exceptions: Map<LocalDate, Boolean>) {
    fun isWorkingDay(day: LocalDate): Boolean =
        exceptions[day] ?: (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY)
}

/** Признак срока (проект, раздел 4). */
enum class SlaState { IN_TIME, AT_RISK, OVERDUE }

/** Какой срок сейчас действует у заявки. */
enum class SlaStage { REACTION, RESOLUTION }

/**
 * Состояние срока заявки для экрана: этап, срок, остаток в минутах режима и признак.
 * Для заявки с выполненными работами remaining = null, а признак показывает,
 * уложились ли в срок устранения.
 */
data class SlaIndicator(val stage: SlaStage, val due: Instant, val remaining: Duration?, val state: SlaState)

/**
 * Расчётный модуль: сроки реакции и устранения по уровню SLA и календарю, остаток времени
 * и признак срока. Алгоритм — проект системы, раздел 8 (блок-схема на рис. 13).
 */
class SlaCalculator(private val calendar: WorkCalendar, private val zone: ZoneId = MOSCOW) {

    /**
     * Срок = начало отсчёта [start] + [minutes] минут в режиме [mode]
     * (1 — круглосуточно, 2 — рабочее время пн–пт 9:00–18:00 по календарю).
     */
    fun due(start: Instant, minutes: Int, mode: Int): Instant {
        require(minutes > 0) { "Время по уровню SLA должно быть больше нуля" }          // п. 1
        if (mode == MODE_24X7) return start.plus(Duration.ofMinutes(minutes.toLong()))  // п. 2

        var t = LocalDateTime.ofInstant(start, zone)                                    // п. 3
        var rest = Duration.ofMinutes(minutes.toLong())
        repeat(MAX_DAYS) {
            t = toWorkingTime(t)                                                        // п. 4–5
            val available = Duration.between(t, t.toLocalDate().atTime(WORK_END))       // п. 6
            if (rest <= available) return t.plus(rest).atZone(zone).toInstant()         // п. 7
            rest = rest.minus(available)                                                // п. 8
            t = t.toLocalDate().plusDays(1).atTime(WORK_START)
        }
        error("Не найден рабочий день за $MAX_DAYS дней — проверьте календарь")
    }

    /**
     * Остаток: время режима от [now] до [due]. Отрицательный, если срок прошёл
     * (тогда по модулю — насколько просрочено).
     */
    fun remaining(now: Instant, due: Instant, mode: Int): Duration =
        if (now <= due) between(now, due, mode) else between(due, now, mode).negated()

    /** Признак: просрочена при остатке ≤ 0, под угрозой при остатке < 25 % длительности этапа. */
    fun state(remaining: Duration, stageMinutes: Int): SlaState = when {
        remaining <= Duration.ZERO -> SlaState.OVERDUE
        remaining.multipliedBy(4) < Duration.ofMinutes(stageMinutes.toLong()) -> SlaState.AT_RISK
        else -> SlaState.IN_TIME
    }

    /** Индикатор для заявки: до прибытия — по сроку реакции, после — по сроку устранения. */
    fun indicator(
        now: Instant, reactionDue: Instant, resolutionDue: Instant, arrivedAt: Instant?, completedAt: Instant?,
        reactionMin: Int, resolutionMin: Int, mode: Int,
    ): SlaIndicator = when {
        completedAt != null -> SlaIndicator(
            SlaStage.RESOLUTION, resolutionDue, null,
            if (completedAt <= resolutionDue) SlaState.IN_TIME else SlaState.OVERDUE,
        )
        arrivedAt == null -> remaining(now, reactionDue, mode).let {
            SlaIndicator(SlaStage.REACTION, reactionDue, it, state(it, reactionMin))
        }
        else -> remaining(now, resolutionDue, mode).let {
            SlaIndicator(SlaStage.RESOLUTION, resolutionDue, it, state(it, resolutionMin))
        }
    }

    /** Время режима между двумя моментами (from ≤ to): обход по дням с рабочими часами. */
    private fun between(from: Instant, to: Instant, mode: Int): Duration {
        if (mode == MODE_24X7) return Duration.between(from, to)
        val start = LocalDateTime.ofInstant(from, zone)
        val end = LocalDateTime.ofInstant(to, zone)
        var total = Duration.ZERO
        var day = start.toLocalDate()
        while (!day.isAfter(end.toLocalDate())) {
            if (calendar.isWorkingDay(day)) {
                val a = maxOf(start, day.atTime(WORK_START))
                val b = minOf(end, day.atTime(WORK_END))
                if (a < b) total = total.plus(Duration.between(a, b))
            }
            day = day.plusDays(1)
        }
        return total
    }

    /** п. 4–5: сдвиг на ближайший рабочий момент. */
    private fun toWorkingTime(value: LocalDateTime): LocalDateTime {
        var t = value
        while (!calendar.isWorkingDay(t.toLocalDate()) || !t.toLocalTime().isBefore(WORK_END)) {
            t = t.toLocalDate().plusDays(1).atTime(WORK_START)
        }
        if (t.toLocalTime().isBefore(WORK_START)) t = t.toLocalDate().atTime(WORK_START)
        return t
    }

    companion object {
        const val MODE_24X7 = 1
        const val MODE_WORK_HOURS = 2
        val WORK_START: LocalTime = LocalTime.of(9, 0)
        val WORK_END: LocalTime = LocalTime.of(18, 0)
        private const val MAX_DAYS = 3660   // защита от бесконечного цикла при пустом календаре
    }
}
