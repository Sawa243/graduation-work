package ru.fieldengineer.app.data.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Записи PIN в SharedPreferences «pin» (в резервную копию не попадают — data_extraction_rules.xml). */
class PrefsPinStorage(context: Context) : PinStorage {
    private val prefs = context.getSharedPreferences("pin", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getInt(key: String): Int = prefs.getInt(key, 0)
    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }
    override fun clear() = prefs.edit { clear() }
}

/**
 * Перец для хеша PIN: HMAC-SHA256 ключом, который создаётся в Android Keystore и не
 * экспортируется — приложение только просит систему посчитать HMAC.
 */
class KeystorePinPepper : PinPepper {

    override fun hasKey(): Boolean = keyStore().containsAlias(KEY_ALIAS)

    override fun mac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key())
        return mac.doFinal(data)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey = keyStore().getKey(KEY_ALIAS, null) as SecretKey?
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        }.generateKey()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "field_engineer_pin_pepper"
    }
}
