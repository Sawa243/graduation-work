package ru.fieldengineer.app.data.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import ru.fieldengineer.app.domain.MOSCOW
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Формат JSON обмена с сервером (проект системы, раздел 10): имена полей — как столбцы
 * таблиц, время — ISO 8601 со смещением, даты — «ГГГГ-ММ-ДД».
 */
val ApiJson = Json {
    ignoreUnknownKeys = true   // новые поля сервера не ломают старую версию приложения
    encodeDefaults = true      // поле deleted у фото отправляется всегда
    explicitNulls = true       // пустое поле отправляется как null, а не пропадает
}

/** «2026-09-25T16:30:00+03:00» ↔ Instant. Отправляем по Москве, принимаем любое смещение. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atZone(MOSCOW)))

    override fun deserialize(decoder: Decoder): Instant = OffsetDateTime.parse(decoder.decodeString()).toInstant()
}

/** «2026-09-25» ↔ LocalDate. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
