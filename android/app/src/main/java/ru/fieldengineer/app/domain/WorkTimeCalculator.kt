package ru.fieldengineer.app.domain

import java.time.Duration
import java.time.Instant

/** Интервал работы (таблица work_log): вид 1 — дорога, 2 — работа на объекте. */
data class WorkInterval(val kind: Int, val start: Instant, val end: Instant?)

/**
 * Трудозатраты по интервалам (проект системы, раздел 4):
 * по заявке — Σ (coalesce(конец, сейчас) − начало);
 * за период — Σ (min(конец, конец периода) − max(начало, начало периода)), если результат > 0.
 */
object WorkTimeCalculator {
    const val KIND_ROAD = 1
    const val KIND_WORK = 2

    /** Сумма по всем интервалам; незакрытый интервал считается до [now]. [kind] = null — все виды. */
    fun total(intervals: List<WorkInterval>, now: Instant, kind: Int? = null): Duration =
        intervals.filter { kind == null || it.kind == kind }
            .fold(Duration.ZERO) { sum, i -> sum.plus(positive(i.start, i.end ?: now)) }

    /** Сумма за период [from, to) с обрезкой интервалов по его границам. */
    fun inPeriod(intervals: List<WorkInterval>, from: Instant, to: Instant, now: Instant, kind: Int? = null): Duration =
        intervals.filter { kind == null || it.kind == kind }
            .fold(Duration.ZERO) { sum, i -> sum.plus(positive(maxOf(i.start, from), minOf(i.end ?: now, to))) }

    /** Целые минуты — так трудозатраты попадают в акт (ОКЕИ 355). */
    fun minutes(duration: Duration): Int = duration.toMinutes().toInt()

    private fun positive(a: Instant, b: Instant): Duration =
        if (b > a) Duration.between(a, b) else Duration.ZERO
}
