package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.domain.TimeReference
import ru.fieldengineer.app.domain.TimeReferenceStore
import ru.fieldengineer.app.domain.TimeSource
import ru.fieldengineer.app.domain.TrustedClock
import java.time.Instant

class TrustedClockTest {
    private class FakeSource(var wall: Instant, var elapsed: Long, var boot: Int) : TimeSource {
        override fun wallClock() = wall
        override fun elapsedRealtimeMs() = elapsed
        override fun bootCount() = boot
    }

    private class MemoryStore : TimeReferenceStore {
        var ref: TimeReference? = null
        override fun load() = ref
        override fun save(reference: TimeReference) { ref = reference }
        override fun clear() { ref = null }
    }

    private val server = Instant.parse("2026-10-05T08:00:00Z")

    @Test
    fun `без опорной пары — часы устройства, время не доверенное`() {
        val src = FakeSource(Instant.parse("2026-10-05T09:00:00Z"), 1_000, 1)
        val m = TrustedClock(src, MemoryStore()).now()
        assertEquals(src.wall, m.time)
        assertFalse(m.trusted)
    }

    @Test
    fun `после синхронизации перевод часов телефона не влияет`() {
        val src = FakeSource(server, 50_000, 3)
        val clock = TrustedClock(src, MemoryStore())
        clock.onServerTime(server)
        // прошло 10 минут, а пользователь перевёл часы на сутки назад
        src.elapsed += 600_000
        src.wall = server.minusSeconds(86_400)
        val m = clock.now()
        assertEquals(server.plusSeconds(600), m.time)
        assertTrue(m.trusted)
    }

    @Test
    fun `после перезагрузки опорная пара не действует`() {
        val src = FakeSource(server, 50_000, 3)
        val clock = TrustedClock(src, MemoryStore())
        clock.onServerTime(server)
        src.boot = 4
        src.elapsed = 5_000
        src.wall = server.plusSeconds(3_600)
        val m = clock.now()
        assertEquals(src.wall, m.time)
        assertFalse(m.trusted)
    }

    @Test
    fun `монотонные часы меньше опорных — пара не действует, даже если номер загрузки тот же`() {
        val src = FakeSource(server, 50_000, -1)   // номер загрузки недоступен
        val clock = TrustedClock(src, MemoryStore())
        clock.onServerTime(server)
        src.elapsed = 10_000                       // телефон перезагрузился
        assertFalse(clock.now().trusted)
    }
}
