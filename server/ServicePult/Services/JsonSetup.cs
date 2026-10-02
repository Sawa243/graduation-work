using System.Text.Json;

namespace ServicePult.Services;

/// <summary>Единые настройки JSON: имена полей snake_case (как столбцы таблиц), время по Москве со смещением.</summary>
public static class JsonSetup
{
    public static void Apply(JsonSerializerOptions options)
    {
        options.PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower;
        options.Converters.Add(new MoscowTimeJsonConverter());
    }

    public static JsonSerializerOptions Create()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        Apply(options);
        return options;
    }
}
