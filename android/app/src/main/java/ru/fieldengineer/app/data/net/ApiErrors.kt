package ru.fieldengineer.app.data.net

import retrofit2.HttpException

/** Сообщения об ошибках сети для экранов. Без Android — проверяются модульными тестами. */
object ApiErrors {
    const val NO_CONNECTION = "Нет связи с сервером"
    const val WRONG_CREDENTIALS = "Неверный логин или пароль"
    const val BLOCKED = "Учётная запись заблокирована, обратитесь к руководителю службы"
    const val NOT_ENGINEER = "Приложение предназначено для выездных инженеров"
    const val SERVER_FAILURE = "Сервер временно недоступен, попробуйте позже"

    /**
     * Ответ сервера на вход → сообщение. Неверный логин и неверный пароль — одно сообщение,
     * чтобы не подсказывать, что из них не так. [title] — заголовок ProblemDetails от сервера.
     */
    fun loginMessage(code: Int, title: String?): String = when {
        code == 400 || code == 401 || code == 404 -> WRONG_CREDENTIALS
        // 403 — вход запрещён: либо учётная запись заблокирована, либо это не инженер
        code == 403 -> if (title?.contains("заблокир", ignoreCase = true) == true) BLOCKED else NOT_ENGINEER
        code == 423 -> BLOCKED
        // 5 неудачных попыток подряд — сервер закрывает вход на 15 минут
        code == 429 -> title?.takeIf { it.isNotBlank() } ?: "Слишком много попыток входа, повторите через 15 минут"
        code >= 500 -> SERVER_FAILURE
        else -> title?.takeIf { it.isNotBlank() } ?: "Ошибка входа (код $code)"
    }

    /** Заголовок ProblemDetails из тела ошибки, если сервер его прислал. */
    fun problemTitle(e: HttpException): String? = try {
        e.response()?.errorBody()?.string()?.let { ApiJson.decodeFromString<ProblemDetails>(it).title }
    } catch (_: Exception) {
        null
    }
}
