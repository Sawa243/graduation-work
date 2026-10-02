package ru.fieldengineer.app.data.repo

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import ru.fieldengineer.app.domain.TimeReference
import ru.fieldengineer.app.domain.TimeReferenceStore
import ru.fieldengineer.app.domain.TimeSource
import java.time.Instant

/** Часы устройства для TrustedClock. */
class AndroidTimeSource(private val context: Context) : TimeSource {
    override fun wallClock(): Instant = Instant.now()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}

/** Опорная пара времени хранится в настройках приложения и переживает его перезапуск. */
class PrefsTimeReferenceStore(context: Context) : TimeReferenceStore {
    private val prefs = context.getSharedPreferences("trusted_time", Context.MODE_PRIVATE)

    override fun load(): TimeReference? {
        if (!prefs.contains(KEY_SERVER)) return null
        return TimeReference(
            serverTime = Instant.ofEpochMilli(prefs.getLong(KEY_SERVER, 0)),
            elapsedMs = prefs.getLong(KEY_ELAPSED, 0),
            bootCount = prefs.getInt(KEY_BOOT, -1),
        )
    }

    override fun save(reference: TimeReference) = prefs.edit {
        putLong(KEY_SERVER, reference.serverTime.toEpochMilli())
        putLong(KEY_ELAPSED, reference.elapsedMs)
        putInt(KEY_BOOT, reference.bootCount)
    }

    override fun clear() = prefs.edit { clear() }

    private companion object {
        const val KEY_SERVER = "server_time"
        const val KEY_ELAPSED = "elapsed_ms"
        const val KEY_BOOT = "boot_count"
    }
}
