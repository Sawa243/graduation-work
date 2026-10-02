package ru.fieldengineer.app.data.repo

import android.content.Context
import androidx.core.content.edit

/**
 * Кто вошёл в приложение: идентификатор сотрудника, задан ли уже PIN после входа и почему
 * инженера вернули на Э1. Сами токены сервера хранит TokenStore (зашифрованными ключом
 * Android Keystore). Всё хранится в SharedPreferences — переживает перезапуск приложения.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var employeeId: Int?
        get() = prefs.getInt(KEY_EMPLOYEE, -1).takeIf { it > 0 }
        set(value) = prefs.edit { if (value == null) remove(KEY_EMPLOYEE) else putInt(KEY_EMPLOYEE, value) }

    /** true — вход по паролю выполнен, но PIN ещё не задан: дальше шага «Задайте PIN» не пускаем. */
    var pinRequired: Boolean
        get() = prefs.getBoolean(KEY_PIN_REQUIRED, false)
        set(value) = prefs.edit { putBoolean(KEY_PIN_REQUIRED, value) }

    /** Пояснение для Э1 (блокировка сеанса); показывается, пока инженер не войдёт. */
    var notice: String?
        get() = prefs.getString(KEY_NOTICE, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_NOTICE) else putString(KEY_NOTICE, value) }

    private companion object {
        const val KEY_EMPLOYEE = "employee_id"
        const val KEY_PIN_REQUIRED = "pin_required"
        const val KEY_NOTICE = "login_notice"
    }
}
