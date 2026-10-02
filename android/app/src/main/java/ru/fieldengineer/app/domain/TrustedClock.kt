package ru.fieldengineer.app.domain

import java.time.Instant

/** Момент времени и признак: получен ли он по доверенным часам (поле time_trusted). */
data class Moment(val time: Instant, val trusted: Boolean)

/** Источники времени устройства; в тестах подменяются. */
interface TimeSource {
    /** Часы устройства — пользователь может их перевести. */
    fun wallClock(): Instant
    /** Монотонные часы с момента загрузки (SystemClock.elapsedRealtime), от настроек не зависят. */
    fun elapsedRealtimeMs(): Long
    /** Номер загрузки устройства: после перезагрузки монотонные часы начинаются заново. */
    fun bootCount(): Int
}

/** Опорная пара «время сервера — монотонные часы», запомненная при синхронизации. */
data class TimeReference(val serverTime: Instant, val elapsedMs: Long, val bootCount: Int)

interface TimeReferenceStore {
    fun load(): TimeReference?
    fun save(reference: TimeReference)
    fun clear()
}

/**
 * Доверенное время (проект системы, раздел 1). Если есть опорная пара с последней
 * синхронизации и телефон с тех пор не перезагружался, время = время сервера + прошедшие
 * монотонные миллисекунды. Иначе берутся часы устройства с пометкой trusted = false.
 * Опорную пару задаёт каждый ответ сервера со временем (вход, обновление токена, pull, push).
 */
class TrustedClock(private val source: TimeSource, private val store: TimeReferenceStore) {

    fun now(): Moment {
        val ref = store.load()
        val elapsed = source.elapsedRealtimeMs()
        if (ref != null && ref.bootCount == source.bootCount() && elapsed >= ref.elapsedMs) {
            return Moment(ref.serverTime.plusMillis(elapsed - ref.elapsedMs), trusted = true)
        }
        return Moment(source.wallClock(), trusted = false)
    }

    /** Вызывается при синхронизации: сервер сообщил своё текущее время. */
    fun onServerTime(serverTime: Instant) {
        store.save(TimeReference(serverTime, source.elapsedRealtimeMs(), source.bootCount()))
    }

    /** Забыть опорную пару — при выходе из приложения. */
    fun reset() = store.clear()
}
