package ru.fieldengineer.app.domain

/**
 * Задание PIN после входа по паролю: ввести PIN (правила PinRules) и повторить его.
 * Чистая логика без Android — проверяется модульными тестами.
 */
class PinSetup {
    enum class Step { CREATE, CONFIRM }

    sealed interface Result {
        /** PIN не подошёл или повтор не совпал — текст для экрана. */
        data class Error(val message: String) : Result
        /** Первый ввод принят — теперь повторить. */
        data object Confirm : Result
        /** Повтор совпал — PIN можно сохранять. */
        data class Done(val pin: String) : Result
    }

    var step = Step.CREATE
        private set

    private var first: String? = null

    fun onPin(pin: String): Result = when (step) {
        Step.CREATE -> PinRules.validate(pin)?.let { Result.Error(it) } ?: run {
            first = pin
            step = Step.CONFIRM
            Result.Confirm
        }
        Step.CONFIRM -> if (pin == first) {
            Result.Done(pin)
        } else {
            first = null
            step = Step.CREATE
            Result.Error("PIN не совпал, задайте заново")
        }
    }
}
