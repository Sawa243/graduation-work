package ru.fieldengineer.app.domain

import java.time.Instant

/**
 * Какой срок сейчас «горит» у заявки: до прибытия инженера — срок реакции,
 * после прибытия — срок устранения (проект системы, раздел 4).
 */
fun currentDue(reactionDue: Instant, resolutionDue: Instant, arrivedAt: Instant?): Instant =
    if (arrivedAt == null) reactionDue else resolutionDue

/** Порядок списка «Мои заявки»: активные — ближайший срок сверху, закрытые — последние сверху. */
fun <T> sortRequests(items: List<T>, isFinished: (T) -> Boolean, due: (T) -> Instant, finishedAt: (T) -> Instant): List<T> {
    val (finished, active) = items.partition(isFinished)
    return active.sortedBy(due) + finished.sortedByDescending(finishedAt)
}
