package ru.fieldengineer.app.domain

/** Пункт чек-листа заявки с отметкой и числом фото — для проверок перед сменой статуса. */
data class ChecklistCheck(
    val itemId: Int,
    val text: String,
    val required: Boolean,
    val needPhoto: Boolean,
    val done: Boolean,
    val photoCount: Int,
)

/** Что нужно записать в базу при переходе (применяет RequestRepository одной транзакцией). */
data class TransitionChanges(
    val newStatus: Int,
    val at: Moment,
    val setArrivedAt: Boolean = false,
    val setCompletedAt: Boolean = false,
    val setClosedAt: Boolean = false,
    /** Закрыть все незакрытые интервалы моментом [at]. */
    val closeOpenIntervals: Boolean = false,
    /** Открыть новый интервал этого вида (1 — дорога, 2 — работа) или null. */
    val openIntervalKind: Int? = null,
)

sealed interface TransitionResult {
    data class Allowed(val changes: TransitionChanges) : TransitionResult
    data class Denied(val reasons: List<String>) : TransitionResult
}

/**
 * Статусы заявки и допустимые переходы для инженера (проект системы, раздел 3).
 * 2 «Назначена» → 3 «В пути» → 4 «На объекте» → 5 «Работы выполнены» (или 8 «Приостановлена»).
 * В 6 «Закрыта» заявку переводит подписание акта (И5), в 7 «Отменена» — диспетчер (И6).
 */
object RequestStatusMachine {
    /** Куда инженер может перевести заявку из статуса [status] кнопками карточки. */
    fun targetsFor(status: Int): List<Int> = when (status) {
        RequestStatus.ASSIGNED -> listOf(RequestStatus.ON_THE_WAY)
        RequestStatus.ON_THE_WAY -> listOf(RequestStatus.ON_SITE)
        RequestStatus.ON_SITE -> listOf(RequestStatus.WORK_DONE, RequestStatus.SUSPENDED)
        else -> emptyList()
    }

    /** Чек-лист и «Что сделано» меняются только на объекте. */
    fun canEditWork(status: Int): Boolean = status == RequestStatus.ON_SITE

    /** После подписи акта или отмены заявка и её записи не меняются. */
    fun isLocked(status: Int): Boolean = status == RequestStatus.CLOSED || status == RequestStatus.CANCELLED

    fun transition(
        from: Int,
        to: Int,
        checklist: List<ChecklistCheck>,
        resultText: String?,
        now: Moment,
    ): TransitionResult {
        if (to !in targetsFor(from)) {
            return TransitionResult.Denied(
                listOf("Переход «${RequestStatus.title(from)}» → «${RequestStatus.title(to)}» недопустим"),
            )
        }
        val reasons = when (to) {
            RequestStatus.WORK_DONE -> checkWorkDone(checklist, resultText)
            RequestStatus.SUSPENDED -> if (resultText.isNullOrBlank())
                listOf("Заполните «Что сделано»: что выполнено и чего ждём") else emptyList()
            else -> emptyList()
        }
        if (reasons.isNotEmpty()) return TransitionResult.Denied(reasons)

        val changes = when (to) {
            // «В пути» открывает интервал дороги
            RequestStatus.ON_THE_WAY -> TransitionChanges(to, now, openIntervalKind = WorkTimeCalculator.KIND_ROAD)
            // «На объекте» — факт реакции: закрывает дорогу, открывает работу
            RequestStatus.ON_SITE -> TransitionChanges(to, now, setArrivedAt = true, closeOpenIntervals = true,
                openIntervalKind = WorkTimeCalculator.KIND_WORK)
            // «Работы выполнены» — факт устранения, закрывает всё
            RequestStatus.WORK_DONE -> TransitionChanges(to, now, setCompletedAt = true, closeOpenIntervals = true)
            else -> TransitionChanges(to, now, closeOpenIntervals = true)
        }
        return TransitionResult.Allowed(changes)
    }

    /**
     * Закрытие заявки (5 → 6) — с экрана акта, когда акт готов: подписан на экране или
     * сфотографирован бумажный подписанный акт (проект, раздел 3).
     */
    fun close(from: Int, actReady: Boolean, now: Moment): TransitionResult = when {
        from != RequestStatus.WORK_DONE -> TransitionResult.Denied(
            listOf("Закрыть можно только заявку в статусе «${RequestStatus.title(RequestStatus.WORK_DONE)}»"))
        !actReady -> TransitionResult.Denied(
            listOf("Сначала подпишите акт на экране или сфотографируйте подписанный бумажный акт"))
        else -> TransitionResult.Allowed(TransitionChanges(RequestStatus.CLOSED, now, setClosedAt = true))
    }

    /** Проверки перед «Работы выполнены» (проект, раздел 3). */
    fun checkWorkDone(checklist: List<ChecklistCheck>, resultText: String?): List<String> = buildList {
        checklist.filter { it.required && !it.done }
            .forEach { add("Не отмечен обязательный пункт: ${it.text}") }
        checklist.filter { it.needPhoto && it.done && it.photoCount == 0 }
            .forEach { add("Нужно фото к пункту: ${it.text}") }
        if (resultText.isNullOrBlank()) add("Заполните «Что сделано»")
    }
}
