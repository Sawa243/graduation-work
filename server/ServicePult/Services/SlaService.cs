namespace ServicePult.Services;

/// <summary>Признак срока (проект, раздел 4).</summary>
public enum SlaState { InTime, AtRisk, Overdue }

/// <summary>Какой срок сейчас действует у заявки.</summary>
public enum SlaStage { Reaction, Resolution }

/// <summary>
/// Состояние срока заявки: этап, срок, остаток в минутах режима и признак.
/// Для заявки с выполненными работами Remaining = null, а признак показывает,
/// уложились ли в срок устранения.
/// </summary>
public record SlaIndicator(SlaStage Stage, DateTimeOffset Due, TimeSpan? Remaining, SlaState State);

/// <summary>
/// Производственный календарь, заданный исключениями (таблица calendar_day):
/// день рабочий, если он есть в исключениях с is_working = true,
/// или если его там нет и это понедельник–пятница.
/// </summary>
public class WorkCalendar(IReadOnlyDictionary<DateOnly, bool> exceptions)
{
    public static readonly WorkCalendar Empty = new(new Dictionary<DateOnly, bool>());

    public bool IsWorkingDay(DateOnly day) =>
        exceptions.TryGetValue(day, out var isWorking)
            ? isWorking
            : day.DayOfWeek is not (DayOfWeek.Saturday or DayOfWeek.Sunday);
}

/// <summary>
/// Расчётный модуль сроков SLA — тот же алгоритм, что SlaCalculator в приложении
/// (проект системы, раздел 8). Работает без базы данных: календарь передаётся готовым набором
/// дат-исключений, поэтому модуль проверяется тестами на тех же примерах, что и в приложении.
/// Все расчёты — по московскому времени.
/// </summary>
public class SlaService(WorkCalendar calendar)
{
    public const int Mode24x7 = 1;
    public const int ModeWorkHours = 2;
    public static readonly TimeOnly WorkStart = new(9, 0);
    public static readonly TimeOnly WorkEnd = new(18, 0);
    private const int MaxDays = 3660; // защита от бесконечного цикла при ошибочном календаре

    /// <summary>Зона Europe/Moscow (в .NET 8 IANA-имя работает и на Windows).</summary>
    public static readonly TimeZoneInfo Moscow = TimeZoneInfo.FindSystemTimeZoneById("Europe/Moscow");

    /// <summary>
    /// Срок = начало отсчёта <paramref name="start"/> + <paramref name="minutes"/> минут в режиме
    /// <paramref name="mode"/> (1 — круглосуточно, 2 — рабочее время пн–пт 9:00–18:00 по календарю).
    /// </summary>
    public DateTimeOffset Due(DateTimeOffset start, int minutes, int mode)
    {
        if (minutes <= 0)                                                               // п. 1
            throw new ArgumentException("Время по уровню SLA должно быть больше нуля", nameof(minutes));
        if (mode == Mode24x7) return ToMoscow(start.AddMinutes(minutes));               // п. 2

        var t = ToMoscow(start).DateTime;                                               // п. 3
        var rest = TimeSpan.FromMinutes(minutes);
        for (var i = 0; i < MaxDays; i++)
        {
            t = ToWorkingTime(t);                                                       // п. 4–5
            var available = t.Date.Add(WorkEnd.ToTimeSpan()) - t;                       // п. 6
            if (rest <= available) return FromMoscow(t + rest);                         // п. 7
            rest -= available;                                                          // п. 8
            t = t.Date.AddDays(1).Add(WorkStart.ToTimeSpan());
        }
        throw new InvalidOperationException($"Не найден рабочий день за {MaxDays} дней — проверьте календарь");
    }

    /// <summary>
    /// Остаток: время режима от <paramref name="now"/> до <paramref name="due"/>.
    /// Отрицательный, если срок прошёл (по модулю — насколько просрочено).
    /// </summary>
    public TimeSpan Remaining(DateTimeOffset now, DateTimeOffset due, int mode) =>
        now <= due ? Between(now, due, mode) : -Between(due, now, mode);

    /// <summary>Признак: просрочена при остатке ≤ 0, под угрозой при остатке &lt; 25 % длительности этапа.</summary>
    public static SlaState State(TimeSpan remaining, int stageMinutes)
    {
        if (remaining <= TimeSpan.Zero) return SlaState.Overdue;
        if (remaining * 4 < TimeSpan.FromMinutes(stageMinutes)) return SlaState.AtRisk;
        return SlaState.InTime;
    }

    /// <summary>Индикатор заявки: до прибытия — по сроку реакции, после — по сроку устранения.</summary>
    public SlaIndicator Indicator(
        DateTimeOffset now, DateTimeOffset reactionDue, DateTimeOffset resolutionDue,
        DateTimeOffset? arrivedAt, DateTimeOffset? completedAt,
        int reactionMin, int resolutionMin, int mode)
    {
        if (completedAt != null)
        {
            var state = completedAt <= resolutionDue ? SlaState.InTime : SlaState.Overdue;
            return new SlaIndicator(SlaStage.Resolution, resolutionDue, null, state);
        }
        if (arrivedAt == null)
        {
            var r = Remaining(now, reactionDue, mode);
            return new SlaIndicator(SlaStage.Reaction, reactionDue, r, State(r, reactionMin));
        }
        var rest = Remaining(now, resolutionDue, mode);
        return new SlaIndicator(SlaStage.Resolution, resolutionDue, rest, State(rest, resolutionMin));
    }

    /// <summary>Время режима между двумя моментами (from ≤ to): обход по дням с рабочими часами.</summary>
    private TimeSpan Between(DateTimeOffset from, DateTimeOffset to, int mode)
    {
        if (mode == Mode24x7) return to - from;
        var start = ToMoscow(from).DateTime;
        var end = ToMoscow(to).DateTime;
        var total = TimeSpan.Zero;
        for (var day = start.Date; day <= end.Date; day = day.AddDays(1))
        {
            if (!calendar.IsWorkingDay(DateOnly.FromDateTime(day))) continue;
            var a = Max(start, day.Add(WorkStart.ToTimeSpan()));
            var b = Min(end, day.Add(WorkEnd.ToTimeSpan()));
            if (a < b) total += b - a;
        }
        return total;
    }

    /// <summary>п. 4–5: сдвиг на ближайший рабочий момент.</summary>
    private DateTime ToWorkingTime(DateTime value)
    {
        var t = value;
        while (!calendar.IsWorkingDay(DateOnly.FromDateTime(t)) || TimeOnly.FromDateTime(t) >= WorkEnd)
            t = t.Date.AddDays(1).Add(WorkStart.ToTimeSpan());
        if (TimeOnly.FromDateTime(t) < WorkStart) t = t.Date.Add(WorkStart.ToTimeSpan());
        return t;
    }

    /// <summary>Момент времени в записи по московскому времени (со смещением +03:00).</summary>
    public static DateTimeOffset ToMoscow(DateTimeOffset value) => TimeZoneInfo.ConvertTime(value, Moscow);

    /// <summary>Московское «настенное» время → момент со смещением.</summary>
    private static DateTimeOffset FromMoscow(DateTime local) =>
        new(DateTime.SpecifyKind(local, DateTimeKind.Unspecified), Moscow.GetUtcOffset(local));

    private static DateTime Max(DateTime a, DateTime b) => a > b ? a : b;
    private static DateTime Min(DateTime a, DateTime b) => a < b ? a : b;
}
