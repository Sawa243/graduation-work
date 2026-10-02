package ru.fieldengineer.app.domain

import java.time.Instant
import java.time.LocalDate

/** Всё, что нужно знать для акта по одной заявке. */
data class ActInput(
    val requestNumber: String,
    val actDate: LocalDate,
    val executorName: String,
    val customerName: String,
    val customerInn: String,
    val contractNumber: String,
    val siteAddress: String,
    val workTypeCode: String,
    val workTypeName: String,
    val equipment: String?,
    val resultText: String?,
    /** Выполненные пункты чек-листа: текст и замечание. */
    val doneItems: List<Pair<String, String?>>,
    val intervals: List<WorkInterval>,
    val completedAt: Instant?,
    val engineerName: String,
    val engineerPosition: String,
    /** Способ подписания: 1 — на экране, 2 — на бумаге. */
    val signMode: Int,
    val signerName: String? = null,
    val signerPosition: String? = null,
    val hasSignature: Boolean = false,
    val signedAt: Instant? = null,
)

/** Строка «реквизит — значение» в шапке акта. */
data class ActField(val name: String, val value: String)

/** Готовое к отрисовке содержание акта (рисует ActPdfWriter). */
data class ActContent(
    val title: String,
    val number: String,
    val date: LocalDate,
    val fields: List<ActField>,
    val resultText: String,
    val doneItems: List<String>,
    val workMin: Int,
    val engineerLine: String,
    val signerLine: String?,
    val signMode: Int,
)

/**
 * Акт выполненных работ: номер, трудозатраты и проверка обязательных реквизитов
 * первичного документа по ч. 2 ст. 9 Федерального закона № 402-ФЗ (проект, раздел 2.1, таблица act).
 */
object ActBuilder {
    const val SIGN_ON_SCREEN = 1
    const val SIGN_ON_PAPER = 2

    /** Номер акта производный от номера заявки: «З-2610-0137» → «А-2610-0137». */
    fun actNumber(requestNumber: String): String = "А" + requestNumber.drop(1)

    /**
     * Трудозатраты на объекте в минутах: только интервалы работы, дорога в акт не входит.
     * Незакрытые интервалы считаются до «Работы выполнены» (к этому моменту их закрыла машина статусов).
     * Начатая минута считается целой: иначе работа короче минуты дала бы 0, а акт без величины
     * подписать нельзя, и заявку уже не закрыть (после «Работы выполнены» время не идёт).
     */
    fun workMinutes(intervals: List<WorkInterval>, completedAt: Instant?): Int {
        val end = completedAt ?: intervals.mapNotNull { it.end }.maxOrNull() ?: return 0
        val total = WorkTimeCalculator.total(intervals, end, WorkTimeCalculator.KIND_WORK)
        val whole = WorkTimeCalculator.minutes(total)
        return if (total > java.time.Duration.ofMinutes(whole.toLong())) whole + 1 else whole
    }

    /**
     * Акт готов к закрытию заявки (проект, раздел 3): подписан на экране — есть изображение
     * подписи и время подписи; подписан на бумаге — к заявке приложено фото подписанного экземпляра.
     */
    fun isReady(signMode: Int, hasSignatureFile: Boolean, signedAt: Instant?, paperPhotos: Int): Boolean =
        when (signMode) {
            SIGN_ON_SCREEN -> hasSignatureFile && signedAt != null
            SIGN_ON_PAPER -> paperPhotos > 0
            else -> false
        }

    /** Каких реквизитов не хватает. Пустой список — акт можно подписывать. */
    fun missingRequisites(input: ActInput): List<String> = buildList {
        if (input.requestNumber.isBlank()) add("номер документа")
        if (input.executorName.isBlank()) add("наименование исполнителя")
        if (input.customerName.isBlank()) add("наименование заказчика")
        if (input.workTypeName.isBlank() || input.resultText.isNullOrBlank()) add("содержание факта — что сделано")
        if (workMinutes(input.intervals, input.completedAt) <= 0) add("величина — трудозатраты на объекте")
        if (input.engineerName.isBlank() || input.engineerPosition.isBlank()) add("должность и ФИО инженера")
        if (input.signMode == SIGN_ON_SCREEN) {
            if (input.signerName.isNullOrBlank()) add("ФИО представителя заказчика")
            if (input.signerPosition.isNullOrBlank()) add("должность представителя заказчика")
            if (!input.hasSignature) add("подпись представителя заказчика")
        }
    }

    fun build(input: ActInput): ActContent {
        val number = actNumber(input.requestNumber)
        val fields = buildList {
            add(ActField("Исполнитель", input.executorName))
            add(ActField("Заказчик", "${input.customerName}, ИНН ${input.customerInn}"))
            add(ActField("Договор", "№ ${input.contractNumber}"))
            add(ActField("Объект", input.siteAddress))
            add(ActField("Заявка", "№ ${input.requestNumber}"))
            add(ActField("Вид работ", "${input.workTypeCode} · ${input.workTypeName}"))
            input.equipment?.let { add(ActField("Оборудование", it)) }
        }
        return ActContent(
            title = "Акт выполненных работ",
            number = number,
            date = input.actDate,
            fields = fields,
            resultText = input.resultText.orEmpty(),
            doneItems = input.doneItems.map { (text, comment) -> if (comment.isNullOrBlank()) text else "$text ($comment)" },
            workMin = workMinutes(input.intervals, input.completedAt),
            engineerLine = "${input.engineerPosition} ${input.engineerName}",
            signerLine = if (input.signerName.isNullOrBlank()) null
                else "${input.signerPosition.orEmpty()} ${input.signerName}".trim(),
            signMode = input.signMode,
        )
    }
}
