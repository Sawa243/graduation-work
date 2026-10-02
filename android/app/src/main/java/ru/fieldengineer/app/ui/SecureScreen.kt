package ru.fieldengineer.app.ui

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import ru.fieldengineer.app.BuildConfig
import java.util.WeakHashMap

/**
 * Экран с персональными данными (Э3, Э6, Э7, Э8): пока он показан, окну ставится FLAG_SECURE —
 * система запрещает снимки и запись экрана, а в списке недавних приложений показывает
 * пустое окно. Диалоги экрана наследуют флаг от окна активности.
 * В отладочной сборке флаг по умолчанию выключен (BuildConfig.SECURE_SCREENS) — чтобы снять
 * скриншоты для документации; в рабочей сборке он включён всегда.
 */
@Composable
fun SecureScreen() {
    if (!BuildConfig.SECURE_SCREENS) return
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        SecureWindow.acquire(window)
        onDispose { SecureWindow.release(window) }
    }
}

/**
 * Сколько защищённых экранов сейчас на окне. При переходе Э3 → Э8 новый экран появляется
 * раньше, чем уходит старый, — счётчик не даёт флагу сняться между ними.
 */
private object SecureWindow {
    private val counts = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        val n = (counts[window] ?: 0) + 1
        counts[window] = n
        if (n == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val n = (counts[window] ?: 1) - 1
        if (n > 0) {
            counts[window] = n
        } else {
            counts.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
