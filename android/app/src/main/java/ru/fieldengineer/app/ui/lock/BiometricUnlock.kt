package ru.fieldengineer.app.ui.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Разблокировка отпечатком через системный BiometricPrompt. Принимается только биометрия
 * класса 3 (BIOMETRIC_STRONG) — устойчивая к подделке. Разблокировка экраном телефона
 * (DEVICE_CREDENTIAL) не принимается: код телефона могут знать коллеги или родственники,
 * а Э0 должен подтвердить именно инженера; запасной способ — его личный PIN.
 * На Android 8.0–8.1 окно отпечатка библиотека рисует сама, а не система, — там только PIN.
 */
object BiometricUnlock {

    fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Отказ, отмена или «Ввести PIN» ничего не делают — инженер просто вводит PIN на Э0. */
    fun show(activity: FragmentActivity, onSuccess: () -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Разблокировка")
            .setSubtitle("Приложите палец, чтобы продолжить работу")
            .setNegativeButtonText("Ввести PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }
}
