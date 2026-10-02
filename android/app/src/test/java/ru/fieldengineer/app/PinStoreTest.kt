package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.data.repo.PinPepper
import ru.fieldengineer.app.data.repo.PinStorage
import ru.fieldengineer.app.data.repo.PinStore
import ru.fieldengineer.app.domain.PasswordHasher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PinStoreTest {
    /** Настройки в памяти вместо SharedPreferences. */
    private class MemoryStorage : PinStorage {
        val map = HashMap<String, Any>()
        override fun getString(key: String) = map[key] as String?
        override fun getInt(key: String) = map[key] as Int? ?: 0
        override fun putString(key: String, value: String) { map[key] = value }
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() = map.clear()
    }

    /** Перец с известным ключом вместо Android Keystore — тот же HMAC-SHA256. */
    private class TestPepper(private val key: ByteArray) : PinPepper {
        var exists = true
        override fun mac(data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        override fun hasKey() = exists
    }

    private val id = 3
    private val storage = MemoryStorage()
    private val pepper = TestPepper(ByteArray(32) { 1 })
    private val pins = PinStore(storage, pepper)

    @Test
    fun `верный PIN подходит, неверный — нет`() {
        pins.setPin(id, "4071")
        assertTrue(pins.hasPin(id))
        assertTrue(pins.verify(id, "4071"))
        assertFalse(pins.verify(id, "4072"))
    }

    @Test
    fun `хранится формат v2 — соль и хеш, соль каждый раз новая`() {
        pins.setPin(id, "4071")
        val first = storage.getString("pin_$id")!!
        assertTrue(first.startsWith("v2:"))
        assertEquals(3, first.split(":").size)
        pins.setPin(id, "4071")
        assertNotEquals(first, storage.getString("pin_$id"))
    }

    @Test
    fun `без ключа перца украденную запись не проверить`() {
        pins.setPin(id, "4071")
        // файл настроек скопирован, ключа Keystore нет — перебор одним PBKDF2 не совпадёт
        val (saltHex, hashHex) = storage.getString("pin_$id")!!.removePrefix("v2:").split(":")
        val onlyPbkdf2 = PasswordHasher.hash("4071", saltHex.hexToByteArray(), 120_000).toHexString()
        assertNotEquals(hashHex, onlyPbkdf2)
        // и с другим ключом верный PIN тоже не подходит
        assertFalse(PinStore(storage, TestPepper(ByteArray(32) { 2 })).verify(id, "4071"))
    }

    @Test
    fun `5 неверных PIN — блокировка, даже верный уже не принимается`() {
        pins.setPin(id, "4071")
        repeat(4) { assertFalse(pins.verify(id, "1111")) }
        assertEquals(1, pins.attemptsLeft(id))
        assertFalse(pins.isLocked(id))
        assertFalse(pins.verify(id, "1111"))
        assertTrue(pins.isLocked(id))
        assertFalse(pins.verify(id, "4071"))
        // после входа по паролю счётчик сброшен
        pins.resetFails(id)
        assertTrue(pins.verify(id, "4071"))
    }

    @Test
    fun `верный PIN сбрасывает счётчик ошибок`() {
        pins.setPin(id, "4071")
        repeat(3) { pins.verify(id, "1111") }
        assertTrue(pins.verify(id, "4071"))
        assertEquals(PinStore.MAX_FAILS, pins.attemptsLeft(id))
    }

    @Test
    fun `старый формат проверяется и пересохраняется с перцем`() {
        val salt = ByteArray(16) { it.toByte() }
        val old = salt.toHexString() + ":" + PasswordHasher.hash("4071", salt, 120_000).toHexString()
        storage.putString("pin_$id", old)
        assertFalse(pins.verify(id, "1234"))
        assertEquals(old, storage.getString("pin_$id"))          // неверный PIN запись не меняет
        assertTrue(pins.verify(id, "4071"))
        assertTrue(storage.getString("pin_$id")!!.startsWith("v2:"))
        assertTrue(pins.verify(id, "4071"))
    }

    @Test
    fun `после входа по паролю PIN и счётчик ошибок удаляются`() {
        pins.setPin(id, "4071")
        repeat(PinStore.MAX_FAILS) { pins.verify(id, "1111") }
        assertTrue(pins.isLocked(id))
        pins.removePin(id)
        assertFalse(pins.hasPin(id))
        assertFalse(pins.isLocked(id))
        assertFalse(pins.verify(id, "4071"))   // старый PIN больше не подходит
        pins.setPin(id, "5820")
        assertTrue(pins.verify(id, "5820"))
    }

    @Test
    fun `PIN другого сотрудника не удаляется`() {
        pins.setPin(id, "4071")
        pins.setPin(7, "5820")
        pins.removePin(7)
        assertTrue(pins.hasPin(id))
    }

    @Test
    fun `ключ перца пропал — PIN нужно задать заново`() {
        pins.setPin(id, "4071")
        pepper.exists = false
        assertFalse(pins.hasPin(id))
        assertFalse(pins.verify(id, "4071"))
    }
}
