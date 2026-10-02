package ru.fieldengineer.app.domain

/** Единица оборудования объекта — то, с чем сверяется отсканированный код. */
data class EquipmentRef(val id: Int, val invCode: String, val serialNo: String?, val title: String)

enum class CodeKind {
    /** Метка компании «BK-EQ-000123» (QR-код, наклеивает сервисная служба). */
    COMPANY_LABEL,
    /** Заводской серийный номер производителя (Code 128, Data Matrix и т. п.). */
    SERIAL,
}

data class ParsedCode(val value: String, val kind: CodeKind)

sealed interface ScanMatch {
    val code: ParsedCode

    /** Найдено на объекте. [isRequestEquipment] — это та самая единица, что указана в заявке. */
    data class Found(override val code: ParsedCode, val equipment: EquipmentRef, val isRequestEquipment: Boolean) : ScanMatch

    /** На объекте такого нет — код всё равно сохраняется в заявке (scanned_code), работа продолжается. */
    data class NotFound(override val code: ParsedCode) : ScanMatch
}

/**
 * Разбор отсканированного или введённого вручную кода и поиск оборудования среди
 * единиц объекта заявки (проект системы, разделы 3 и 7).
 */
object BarcodeAnalyzer {
    private val COMPANY_LABEL = Regex("^BK-EQ-\\d{6}$")
    const val MAX_LENGTH = 100   // длина поля request.scanned_code

    /** null — пустой код. Пробелы по краям убираются, метка компании приводится к верхнему регистру. */
    fun parse(raw: String): ParsedCode? {
        val text = raw.trim().take(MAX_LENGTH)
        if (text.isEmpty()) return null
        val upper = text.uppercase()
        return if (COMPANY_LABEL.matches(upper)) ParsedCode(upper, CodeKind.COMPANY_LABEL)
        else ParsedCode(text, CodeKind.SERIAL)
    }

    fun match(code: ParsedCode, siteEquipment: List<EquipmentRef>, requestEquipmentId: Int?): ScanMatch {
        val found = when (code.kind) {
            CodeKind.COMPANY_LABEL -> siteEquipment.firstOrNull { it.invCode == code.value }
            CodeKind.SERIAL -> siteEquipment.firstOrNull { it.serialNo?.trim().equals(code.value, ignoreCase = true) }
        }
        return if (found == null) ScanMatch.NotFound(code)
        else ScanMatch.Found(code, found, isRequestEquipment = found.id == requestEquipmentId)
    }
}
