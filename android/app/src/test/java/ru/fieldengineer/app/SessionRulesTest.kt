package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fieldengineer.app.domain.SessionRules
import ru.fieldengineer.app.domain.SessionRules.LocalData

class SessionRulesTest {

    @Test
    fun `вошёл тот же сотрудник — его неотправленные записи остаются`() {
        assertEquals(LocalData.KEEP, SessionRules.beforeLogin(previous = 3, incoming = 3, unsent = 4))
        assertEquals(LocalData.KEEP, SessionRules.beforeLogin(previous = 3, incoming = 3, unsent = 0))
    }

    @Test
    fun `другой сотрудник при неотправленных записях — сначала спросить`() {
        assertEquals(LocalData.ASK, SessionRules.beforeLogin(previous = 3, incoming = 5, unsent = 2))
    }

    @Test
    fun `другой сотрудник, очередь пуста — очистить без вопроса`() {
        assertEquals(LocalData.CLEAR, SessionRules.beforeLogin(previous = 3, incoming = 5, unsent = 0))
    }

    @Test
    fun `владелец данных неизвестен (выход прервался) — очистить перед загрузкой`() {
        assertEquals(LocalData.CLEAR, SessionRules.beforeLogin(previous = null, incoming = 5, unsent = 7))
        assertEquals(LocalData.CLEAR, SessionRules.beforeLogin(previous = null, incoming = 5, unsent = 0))
    }
}
