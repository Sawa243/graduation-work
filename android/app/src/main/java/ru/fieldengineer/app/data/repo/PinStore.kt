package ru.fieldengineer.app.data.repo

import ru.fieldengineer.app.domain.PasswordHasher
import java.security.MessageDigest
import java.security.SecureRandom

/** Где лежат записи PIN: в приложении — SharedPreferences, в тестах — таблица в памяти. */
interface PinStorage {
    fun getString(key: String): String?
    /** 0, если записи нет. */
    fun getInt(key: String): Int
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun remove(key: String)
    fun clear()
}

/**
 * «Перец» — секрет, которого нет в файле настроек. В приложении это HMAC-SHA256 ключом
 * Android Keystore (KeystorePinPepper): ключ не извлекается из телефона.
 */
interface PinPepper {
    /** HMAC от данных; ключ создаётся при первом вызове. */
    fun mac(data: ByteArray): ByteArray
    /** Есть ли уже ключ. Нет — хеши, записанные с ним раньше, проверить невозможно. */
    fun hasKey(): Boolean
}

/**
 * PIN инженера: им подтверждается подпись акта (Э7) и разблокируется приложение (Э0).
 * Задаётся заново после каждого входа по паролю (шаг «Задайте PIN» после Э1): так забытый
 * PIN восстанавливается входом по паролю, а без пароля новый PIN не задать.
 * Хранится соль и HMAC(ключ Keystore, PBKDF2(PIN, соль)). Без ключа, который не покидает
 * телефон, украденный файл настроек не позволяет перебрать 4–6 цифр на компьютере.
 * Формат записи: «v2:соль:хеш». Старый формат «соль:хеш» (только PBKDF2) проверяется как
 * раньше и при первом верном PIN пересохраняется в новом.
 */
class PinStore(private val storage: PinStorage, private val pepper: PinPepper) {

    fun hasPin(employeeId: Int): Boolean {
        val stored = storage.getString(key(employeeId)) ?: return false
        // хеш с перцем есть, а ключа нет (система удалила его) — такой PIN не проверить;
        // новый задаётся только после входа по паролю
        if (stored.startsWith(V2) && !pepper.hasKey()) {
            storage.remove(key(employeeId))
            return false
        }
        return true
    }

    fun setPin(employeeId: Int, pin: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        storage.putString(key(employeeId), V2 + salt.toHexString() + ":" + peppered(pin, salt).toHexString())
    }

    /** Счётчик неверных PIN хранится постоянно: выход с экрана или перезапуск его не сбрасывают. */
    fun isLocked(employeeId: Int): Boolean = storage.getInt(failKey(employeeId)) >= MAX_FAILS

    fun attemptsLeft(employeeId: Int): Int = (MAX_FAILS - storage.getInt(failKey(employeeId))).coerceAtLeast(0)

    fun verify(employeeId: Int, pin: String): Boolean {
        if (isLocked(employeeId)) return false
        val ok = check(employeeId, pin)
        if (ok) storage.remove(failKey(employeeId))
        else storage.putInt(failKey(employeeId), storage.getInt(failKey(employeeId)) + 1)
        return ok
    }

    /** Снять блокировку PIN — после входа по логину и паролю. */
    fun resetFails(employeeId: Int) = storage.remove(failKey(employeeId))

    /** Удалить PIN и счётчик ошибок — после входа по паролю PIN задаётся заново. */
    fun removePin(employeeId: Int) {
        storage.remove(key(employeeId))
        storage.remove(failKey(employeeId))
    }

    fun clear() = storage.clear()

    private fun check(employeeId: Int, pin: String): Boolean {
        val stored = storage.getString(key(employeeId)) ?: return false
        if (stored.startsWith(V2)) {
            val parts = stored.removePrefix(V2).split(":")
            return parts.size == 2 &&
                MessageDigest.isEqual(peppered(pin, parts[0].hexToByteArray()), parts[1].hexToByteArray())
        }
        // старый формат — только PBKDF2; верный PIN сразу пересохраняем с перцем
        val parts = stored.split(":")
        val ok = parts.size == 2 && PasswordHasher.verify(pin, parts[0], ITERATIONS, parts[1])
        if (ok) setPin(employeeId, pin)
        return ok
    }

    private fun peppered(pin: String, salt: ByteArray) = pepper.mac(PasswordHasher.hash(pin, salt, ITERATIONS))

    private fun key(employeeId: Int) = "pin_$employeeId"
    private fun failKey(employeeId: Int) = "fails_$employeeId"

    companion object {
        const val MAX_FAILS = 5
        private const val ITERATIONS = 120_000
        private const val V2 = "v2:"
    }
}
