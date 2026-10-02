package ru.fieldengineer.app.data.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Токены сервера (доступа и обновления). Каждый токен шифруется AES-256-GCM ключом из
 * Android Keystore: ключ создаётся и хранится в защищённом хранилище системы, извлечь его
 * из телефона нельзя — приложение только просит систему зашифровать или расшифровать.
 * В SharedPreferences лежат лишь шифротекст и IV (вектор инициализации).
 * Если расшифровать не удалось (ключ пропал, например после сброса блокировки экрана), токены
 * считаются отсутствующими — инженеру нужно войти заново.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)

    // расшифрованные токены держим в памяти, чтобы не обращаться к Keystore на каждый запрос
    @Volatile private var access: String? = null
    @Volatile private var refresh: String? = null
    @Volatile private var loaded = false

    val accessToken: String? get() { load(); return access }
    val refreshToken: String? get() { load(); return refresh }
    val hasTokens: Boolean get() = refreshToken != null

    @Synchronized
    fun save(accessToken: String, refreshToken: String) {
        prefs.edit {
            putString(KEY_ACCESS, encrypt(accessToken))
            putString(KEY_REFRESH, encrypt(refreshToken))
        }
        access = accessToken
        refresh = refreshToken
        loaded = true
    }

    /**
     * Сохранить обновлённую пару, только если токен обновления всё ещё [usedRefresh]: пока шёл
     * запрос к серверу, сеанс могли заблокировать (токены удалены) или войти заново.
     */
    @Synchronized
    fun saveIfCurrent(usedRefresh: String, accessToken: String, refreshToken: String): Boolean {
        if (this.refreshToken != usedRefresh) return false
        save(accessToken, refreshToken)
        return true
    }

    /** Удалить токены, если сервер отклонил именно текущий токен обновления, а не прежний. */
    @Synchronized
    fun clearIfCurrent(usedRefresh: String): Boolean {
        if (refreshToken != usedRefresh) return false
        clear()
        return true
    }

    @Synchronized
    fun clear() {
        prefs.edit { clear() }
        access = null
        refresh = null
        loaded = true
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        access = prefs.getString(KEY_ACCESS, null)?.let(::decrypt)
        refresh = prefs.getString(KEY_REFRESH, null)?.let(::decrypt)
        if (access == null || refresh == null) {
            // расшифровать не вышло — старый шифротекст бесполезен
            access = null
            refresh = null
            prefs.edit { clear() }
        }
        loaded = true
    }

    /** Результат — «IV:шифротекст» в Base64. IV новый для каждого шифрования, его выбирает Keystore. */
    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return b64.encodeToString(cipher.iv) + ":" + b64.encodeToString(data)
    }

    private fun decrypt(stored: String): String? = try {
        val parts = stored.split(":")
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, b64.decode(parts[0])))
        String(cipher.doFinal(b64.decode(parts[1])), Charsets.UTF_8)
    } catch (_: Exception) {
        // ключа нет, ключ другой, данные повреждены — во всех случаях входа нет
        null
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey?
    }

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "field_engineer_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
    }
}
