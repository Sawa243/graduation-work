package ru.fieldengineer.app.ui

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.fieldengineer.app.domain.MOSCOW
import ru.fieldengineer.app.domain.SlaIndicator
import ru.fieldengineer.app.domain.SlaStage
import ru.fieldengineer.app.domain.SlaState
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EE dd.MM HH:mm", RU)
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", RU)

/** Дата и время по Москве: «пн 05.10 11:30». */
fun Instant.moscow(): String = DATE_TIME.format(atZone(MOSCOW))

/** Только время по Москве: «11:30». */
fun Instant.moscowTime(): String = TIME.format(atZone(MOSCOW))

/** «2 ч 05 мин», «40 мин». */
fun Duration.hoursMinutes(): String {
    val total = abs().toMinutes()
    val h = total / 60
    val m = total % 60
    return if (h > 0) "$h ч ${m.toString().padStart(2, '0')} мин" else "$m мин"
}

val ColorInTime = Color(0xFF2E7D32)
val ColorAtRisk = Color(0xFFB26A00)
val ColorOverdue = Color(0xFFB3261E)

fun SlaState.color(): Color = when (this) {
    SlaState.IN_TIME -> ColorInTime
    SlaState.AT_RISK -> ColorAtRisk
    SlaState.OVERDUE -> ColorOverdue
}

/** Подпись индикатора срока для списка и карточки. */
fun SlaIndicator.label(): String {
    val what = if (stage == SlaStage.REACTION) "прибыть" else "устранить"
    val left = remaining
    return when {
        left == null -> if (state == SlaState.IN_TIME) "устранено в срок" else "устранено с нарушением срока"
        state == SlaState.OVERDUE -> "$what: просрочено на ${left.hoursMinutes()}"
        left < Duration.ofMinutes(1) -> "$what: осталось меньше минуты"
        else -> "$what: осталось ${left.hoursMinutes()}"
    }
}

/** Тик раз в 30 секунд, чтобы остаток времени на экране обновлялся сам. */
fun ticker(periodMs: Long = 30_000): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(periodMs)
    }
}
