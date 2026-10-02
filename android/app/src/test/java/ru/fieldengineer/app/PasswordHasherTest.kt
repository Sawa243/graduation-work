package ru.fieldengineer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.domain.PasswordHasher

class PasswordHasherTest {
    // эталон посчитан независимо: Python hashlib.pbkdf2_hmac("sha256", b"Test2026!", bytes(range(16)), 1000)
    private val salt = "000102030405060708090a0b0c0d0e0f"
    private val expected = "1da51bd882e03eb0958b0484437cc53c52f84f3ad2c01a18424dc982a105ca4a"

    @Test
    fun `верный пароль проходит`() {
        assertTrue(PasswordHasher.verify("Test2026!", salt, 1000, expected))
    }

    @Test
    fun `неверный пароль не проходит`() {
        assertFalse(PasswordHasher.verify("test2026!", salt, 1000, expected))
        assertFalse(PasswordHasher.verify("", salt, 1000, expected))
    }

    @Test
    fun `другая соль даёт другой хеш`() {
        assertFalse(PasswordHasher.verify("Test2026!", "0f0e0d0c0b0a09080706050403020100", 1000, expected))
    }
}
