package ru.fieldengineer.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.fieldengineer.app.ui.AppNavHost
import ru.fieldengineer.app.ui.lock.AppLockManager
import ru.fieldengineer.app.ui.lock.AppLockOverlay
import ru.fieldengineer.app.ui.theme.FieldEngineerTheme

/**
 * Точка входа: одна активность, экраны переключает навигация Compose. Поверх любого экрана
 * может появиться Э0 «Разблокировка» (AppLockManager). FragmentActivity нужна BiometricPrompt.
 */
class MainActivity : FragmentActivity() {
    private lateinit var lock: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as App).container
        lock = container.appLockManager

        // пока приложение на экране, проверяем бездействие; при возврате из фона цикл начинается
        // заново — первая же проверка заблокирует, если прошло 5 минут
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    lock.refresh()
                    delay(lock.millisUntilLock().coerceAtLeast(1_000))
                }
            }
        }

        setContent {
            FieldEngineerTheme {
                AppNavHost(container)
                AppLockOverlay(container)
            }
        }
    }

    /** Любое касание экрана или нажатие клавиши — инженер работает, отсчёт бездействия заново. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        lock.onUserInteraction()
    }

    override fun onStop() {
        super.onStop()
        lock.onBackground()
    }
}
