package ru.fieldengineer.app.ui.lock

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.fieldengineer.app.domain.ActivityMark
import ru.fieldengineer.app.domain.AppLockTimer
import ru.fieldengineer.app.domain.TimeSource

/**
 * Блокировка приложения после 5 минут бездействия (функция 1.2, экран Э0). Считает время
 * AppLockTimer; здесь — связь с активностью: касания, уход в фон и возврат, сохранение
 * момента последнего действия на случай, если система выгрузит свёрнутое приложение.
 * Пока инженер не вошёл (экран Э1) и не задал PIN (шаг после входа), блокировать нечего —
 * отсчёт стоит на нуле; [isLoggedIn] — это AuthRepository.isReady.
 */
class AppLockManager(context: Context, source: TimeSource, private val isLoggedIn: () -> Boolean) {
    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private val timer = AppLockTimer(source, loadMark())

    private val _locked = MutableStateFlow(false)

    /** true — поверх текущего экрана показывается Э0. */
    val locked: StateFlow<Boolean> = _locked

    init {
        refresh()
    }

    /** Любое касание экрана (Activity.onUserInteraction). */
    fun onUserInteraction() = timer.onUserActivity()

    /** Приложение ушло в фон: запоминаем момент последнего действия. */
    fun onBackground() {
        val mark = timer.mark()
        prefs.edit {
            putLong(KEY_ELAPSED, mark.elapsedMs)
            putInt(KEY_BOOT, mark.bootCount)
        }
    }

    /** Проверка по таймеру и при возврате из фона. */
    fun refresh() {
        if (!isLoggedIn()) timer.unlock()
        _locked.value = timer.check()
    }

    fun millisUntilLock(): Long = timer.millisUntilLock()

    /** PIN или отпечаток подтверждены. */
    fun unlock() {
        timer.unlock()
        _locked.value = false
    }

    private fun loadMark(): ActivityMark? =
        if (prefs.contains(KEY_ELAPSED)) ActivityMark(prefs.getLong(KEY_ELAPSED, 0), prefs.getInt(KEY_BOOT, -1)) else null

    private companion object {
        const val KEY_ELAPSED = "last_activity_elapsed_ms"
        const val KEY_BOOT = "last_activity_boot"
    }
}
