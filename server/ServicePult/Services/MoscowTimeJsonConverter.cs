using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Text.Json.Serialization;

namespace ServicePult.Services;

/// <summary>
/// Время в JSON — ISO 8601 со смещением по московскому времени: 2026-09-25T16:30:00+03:00
/// (проект, раздел 10). Дробная часть секунд пишется, только если она есть.
///
/// Время без смещения («2026-09-25T16:30:00») не принимается — запрос получает ответ 400.
/// Такое время неоднозначно: это может быть и Москва, и UTC, и часы телефона; если молча считать
/// его UTC или местным, отметки сдвинутся на 3 часа и испортят сроки SLA. Приложение всегда
/// передаёт смещение, так что отказ ловит только ошибку в клиенте.
/// </summary>
public class MoscowTimeJsonConverter : JsonConverter<DateTimeOffset>
{
    private const string Format = "yyyy-MM-dd'T'HH:mm:ss.FFFFFFFzzz";

    /// <summary>Время кончается смещением: «Z» или «±ЧЧ:ММ» (допускается и «±ЧЧММ»).</summary>
    public static bool HasOffset(string text) => OffsetPattern.IsMatch(text);

    private static readonly Regex OffsetPattern = new(@"(Z|z|[+-]\d{2}:?\d{2})\z");

    public override DateTimeOffset Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        var text = reader.GetString();
        if (text == null || !HasOffset(text))
            throw new JsonException("Время без смещения: нужно ISO 8601 со смещением, например 2026-09-25T16:30:00+03:00");
        return reader.GetDateTimeOffset();
    }

    public override void Write(Utf8JsonWriter writer, DateTimeOffset value, JsonSerializerOptions options) =>
        writer.WriteStringValue(SlaService.ToMoscow(value).ToString(Format, CultureInfo.InvariantCulture));
}
