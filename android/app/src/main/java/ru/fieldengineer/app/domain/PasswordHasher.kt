package ru.fieldengineer.app.domain

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Проверка пароля по хешу PBKDF2-HMAC-SHA256. Пароль в открытом виде нигде не хранится:
 * хранится соль, число итераций и результат. Сравнение — за постоянное время.
 */
object PasswordHasher {
    private const val KEY_BITS = 256

    fun hash(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(password: String, saltHex: String, iterations: Int, expectedHex: String): Boolean =
        MessageDigest.isEqual(hash(password, saltHex.hexToByteArray(), iterations), expectedHex.hexToByteArray())
}
