package ru.fieldengineer.app.domain

/** Момент последнего действия: монотонные часы и номер загрузки (после перезагрузки часы идут с нуля). */
data class ActivityMark(val elapsedMs: Long, val bootCount: Int)

/**
 * Таймер блокировки приложения (функция 1.2, экран Э0): после [timeoutMs] без действий
 * пользователя приложение блокируется. Время берётся из монотонных часов, поэтому перевод
 * часов телефона на блокировку не влияет. Класс не зависит от Android — проверяется тестами.
 *
 * [saved] — момент последнего действия, сохранённый прошлым процессом (система могла выгрузить
 * приложение, пока оно было свёрнуто). Если его нет, телефон перезагружался или прошло
 * [timeoutMs] и больше, приложение запускается заблокированным.
 */
class AppLockTimer(
    private val source: TimeSource,
    saved: ActivityMark? = null,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private var lastActivity: Long = source.elapsedRealtimeMs()

    var isLocked: Boolean = !restore(saved)
        private set

    /** Касание экрана: отсчёт заново. Пока приложение заблокировано, касания не считаются. */
    fun onUserActivity() {
        if (!isLocked) lastActivity = source.elapsedRealtimeMs()
    }

    /** Не пора ли заблокировать. Вызывается по таймеру и при возврате приложения из фона. */
    fun check(): Boolean {
        if (!isLocked && source.elapsedRealtimeMs() - lastActivity >= timeoutMs) isLocked = true
        return isLocked
    }

    /** Сколько осталось до блокировки — через столько проверить снова. */
    fun millisUntilLock(): Long =
        if (isLocked) 0 else (timeoutMs - (source.elapsedRealtimeMs() - lastActivity)).coerceAtLeast(0)

    /** Разблокировали (PIN, отпечаток) или вошли по паролю — отсчёт с нуля. */
    fun unlock() {
        isLocked = false
        lastActivity = source.elapsedRealtimeMs()
    }

    /** Что сохранить при уходе в фон, чтобы новый процесс продолжил отсчёт. */
    fun mark() = ActivityMark(lastActivity, source.bootCount())

    private fun restore(saved: ActivityMark?): Boolean {
        if (saved == null || saved.bootCount != source.bootCount()) return false
        val idle = source.elapsedRealtimeMs() - saved.elapsedMs
        if (idle !in 0 until timeoutMs) return false
        lastActivity = saved.elapsedMs
        return true
    }

    companion object {
        /** 5 минут без действий — проект системы, функция 1.2. */
        const val TIMEOUT_MS = 5 * 60 * 1000L
    }
}
