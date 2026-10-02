package ru.fieldengineer.app.domain

/** PIN инженера для подписи акта (простая ЭП: вход по паролю + PIN при подписании). */
object PinRules {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    /** null — PIN подходит, иначе текст ошибки. */
    fun validate(pin: String): String? = when {
        pin.length !in MIN_LENGTH..MAX_LENGTH || !pin.all(Char::isDigit) -> "PIN — от 4 до 6 цифр"
        pin.toSet().size == 1 -> "PIN из одинаковых цифр слишком простой"
        pin in "0123456789" || pin in "9876543210" -> "PIN из цифр подряд слишком простой"
        else -> null
    }
}
