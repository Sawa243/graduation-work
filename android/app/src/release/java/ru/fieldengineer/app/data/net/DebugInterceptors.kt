package ru.fieldengineer.app.data.net

import okhttp3.Interceptor

/** Рабочая сборка: журнал запросов не ведётся. */
fun debugInterceptors(): List<Interceptor> = emptyList()
