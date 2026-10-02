package ru.fieldengineer.app.data.net

import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Отладочная сборка: строка в Logcat на каждый запрос (метод, адрес, код ответа, время).
 * Тела и заголовки не пишем — в них пароль, токены и персональные данные.
 */
fun debugInterceptors(): List<Interceptor> =
    listOf(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
