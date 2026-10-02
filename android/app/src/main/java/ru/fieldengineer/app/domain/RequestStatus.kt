package ru.fieldengineer.app.domain

/** Статусы заявки (проект системы, раздел 3). Правила переходов — RequestStatusMachine (И2). */
object RequestStatus {
    const val NEW = 1
    const val ASSIGNED = 2
    const val ON_THE_WAY = 3
    const val ON_SITE = 4
    const val WORK_DONE = 5
    const val CLOSED = 6
    const val CANCELLED = 7
    const val SUSPENDED = 8

    fun title(status: Int): String = when (status) {
        NEW -> "Новая"
        ASSIGNED -> "Назначена"
        ON_THE_WAY -> "В пути"
        ON_SITE -> "На объекте"
        WORK_DONE -> "Работы выполнены"
        CLOSED -> "Закрыта"
        CANCELLED -> "Отменена"
        SUSPENDED -> "Приостановлена"
        else -> "Статус $status"
    }

    /** Закрытые для инженера: подписан акт, отменена или приостановлена до повторного выезда. */
    fun isFinished(status: Int): Boolean = status == CLOSED || status == CANCELLED || status == SUSPENDED
}
