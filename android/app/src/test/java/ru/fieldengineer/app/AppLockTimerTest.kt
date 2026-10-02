package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.domain.ActivityMark
import ru.fieldengineer.app.domain.AppLockTimer
import ru.fieldengineer.app.domain.TimeSource
import java.time.Instant

class AppLockTimerTest {
    /** Подменяемые часы: время двигает сам тест. */
    private class FakeSource(var elapsed: Long = 1_000_000, var boot: Int = 7) : TimeSource {
        override fun wallClock(): Instant = Instant.EPOCH
        override fun elapsedRealtimeMs() = elapsed
        override fun bootCount() = boot
        fun pass(ms: Long) { elapsed += ms }
    }

    private val min = 60_000L

    /** Приложение уже работает (отметка «только что»), таймер не заблокирован. */
    private fun running(src: FakeSource) = AppLockTimer(src, ActivityMark(src.elapsed, src.boot))

    @Test
    fun `до 5 минут без действий — не блокируется`() {
        val src = FakeSource()
        val timer = running(src)
        src.pass(5 * min - 1)
        assertFalse(timer.check())
    }

    @Test
    fun `ровно 5 минут без действий — блокировка`() {
        val src = FakeSource()
        val timer = running(src)
        src.pass(5 * min)
        assertTrue(timer.check())
    }

    @Test
    fun `касание сбрасывает отсчёт`() {
        val src = FakeSource()
        val timer = running(src)
        src.pass(4 * min)
        timer.onUserActivity()
        src.pass(4 * min)
        assertFalse(timer.check())
        assertEquals(1 * min, timer.millisUntilLock())
    }

    @Test
    fun `касания при блокировке её не снимают`() {
        val src = FakeSource()
        val timer = running(src)
        src.pass(6 * min)
        assertTrue(timer.check())
        timer.onUserActivity()
        assertTrue(timer.check())
        assertEquals(0, timer.millisUntilLock())
    }

    @Test
    fun `возврат из фона через 3 минуты — без блокировки, через 5 — с блокировкой`() {
        val src = FakeSource()
        val timer = running(src)
        src.pass(3 * min)                 // свернули и вернулись
        assertFalse(timer.check())
        src.pass(2 * min)                 // снова свернули; всего без действий 5 минут
        assertTrue(timer.check())
    }

    @Test
    fun `после разблокировки отсчёт с нуля`() {
        val src = FakeSource()
        val timer = running(src)
        src.pass(10 * min)
        assertTrue(timer.check())
        timer.unlock()
        src.pass(4 * min)
        assertFalse(timer.check())
    }

    @Test
    fun `новый процесс без сохранённой отметки — сразу блокировка`() {
        assertTrue(AppLockTimer(FakeSource(), saved = null).isLocked)
    }

    @Test
    fun `новый процесс продолжает отсчёт по сохранённой отметке`() {
        val src = FakeSource()
        val first = running(src)
        src.pass(2 * min)
        first.onUserActivity()
        val mark = first.mark()           // сохранили при уходе в фон, процесс выгружен
        src.pass(2 * min)
        val second = AppLockTimer(src, mark)
        assertFalse(second.isLocked)
        assertEquals(3 * min, second.millisUntilLock())
        src.pass(4 * min)                 // всего без действий 6 минут
        assertTrue(AppLockTimer(src, mark).isLocked)
    }

    @Test
    fun `после перезагрузки телефона — блокировка`() {
        val src = FakeSource(elapsed = 500_000, boot = 7)
        assertTrue(AppLockTimer(src, ActivityMark(elapsedMs = 400_000, bootCount = 6)).isLocked)
        // номер загрузки неизвестен, а монотонные часы «ушли назад» — тоже перезагрузка
        val unknown = FakeSource(elapsed = 10_000, boot = -1)
        assertTrue(AppLockTimer(unknown, ActivityMark(400_000, -1)).isLocked)
    }
}
