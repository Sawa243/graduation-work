using System.Globalization;
using ServicePult.Services;

namespace ServicePult.Pages;

/// <summary>Вывод на страницах пульта: время — по Москве, длительности — «2 ч 05 мин».</summary>
public static class Format
{
    private static readonly CultureInfo Ru = CultureInfo.GetCultureInfo("ru-RU");

    public static string Time(DateTimeOffset value) => SlaService.ToMoscow(value).ToString("dd.MM.yyyy HH:mm", Ru);

    public static string Time(DateTimeOffset? value) => value == null ? "—" : Time(value.Value);

    public static string Date(DateOnly value) => value.ToString("dd.MM.yyyy", Ru);

    public static string Date(DateOnly? value) => value == null ? "—" : Date(value.Value);

    // Разумные годы для дат, которые вводят на страницах пульта. Без ограничения год 9999 или 0
    // приводил к исключению при расчёте границ периода (день после последнего — уже за пределами дат).
    public const int MinYear = 2000;
    public const int MaxYear = 2100;

    public static bool IsReasonableYear(int year) => year is >= MinYear and <= MaxYear;

    /// <summary>
    /// Длительность со знаком: «43 ч 05 мин», «−45 мин». Без дней: остаток SLA-2 и SLA-3 считается
    /// в рабочих часах, и «сутки» в нём не равны 24 часам календаря.
    /// </summary>
    public static string Duration(TimeSpan value)
    {
        var sign = value < TimeSpan.Zero ? "−" : "";
        var total = (long)Math.Abs(value.TotalMinutes);
        var hours = total / 60;
        var minutes = total % 60;
        return sign + (hours > 0 ? $"{hours} ч {minutes:00} мин" : $"{minutes} мин");
    }

    /// <summary>Норматив SLA: 240 → «4 ч», 1080 → «18 ч», 90 → «1 ч 30 мин».</summary>
    public static string Minutes(int minutes) =>
        minutes % 60 == 0 ? $"{minutes / 60} ч" : minutes < 60 ? $"{minutes} мин" : $"{minutes / 60} ч {minutes % 60} мин";

    public static string StateTitle(SlaState state) => state switch
    {
        SlaState.InTime => "в срок",
        SlaState.AtRisk => "под угрозой",
        _ => "просрочена",
    };

    /// <summary>CSS-класс цветного признака срока.</summary>
    public static string StateCss(SlaState state) => state switch
    {
        SlaState.InTime => "sla-ok",
        SlaState.AtRisk => "sla-risk",
        _ => "sla-late",
    };

    /// <summary>«Тестов Иван Петрович» → «Тестов И. П.»</summary>
    public static string ShortName(string? fullName)
    {
        if (string.IsNullOrWhiteSpace(fullName)) return "—";
        var parts = fullName.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        return parts[0] + (parts.Length > 1 ? " " + string.Join(" ", parts.Skip(1).Select(p => p[0] + ".")) : "");
    }

    public static string Percent(int? value) => value == null ? "—" : value + " %";

    public static string RoleTitle(short role) => role switch
    {
        1 => "инженер",
        2 => "диспетчер",
        3 => "руководитель службы",
        _ => $"роль {role}",
    };
}
