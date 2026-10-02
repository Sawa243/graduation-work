using Microsoft.EntityFrameworkCore;
using ServicePult.Data;

namespace ServicePult.Services;

/// <summary>Период [From, To) — границы по московскому времени.</summary>
public record ReportPeriod(DateTimeOffset From, DateTimeOffset To)
{
    /// <summary>Период с первого по последний день включительно: от 00:00 первого дня до 00:00 дня после последнего (МСК).</summary>
    public static ReportPeriod FromDates(DateOnly first, DateOnly last) =>
        new(MoscowMidnight(first), MoscowMidnight(last.AddDays(1)));

    /// <summary>Календарный месяц по Москве.</summary>
    public static ReportPeriod Month(int year, int month)
    {
        var first = new DateOnly(year, month, 1);
        return FromDates(first, first.AddMonths(1).AddDays(-1));
    }

    /// <summary>Месяц, в котором находится момент now (по Москве).</summary>
    public static ReportPeriod MonthOf(DateTimeOffset now)
    {
        var today = CalendarLoader.MoscowToday(now);
        return Month(today.Year, today.Month);
    }

    /// <summary>Первый и последний день периода (для формы).</summary>
    public DateOnly FirstDay => CalendarLoader.MoscowToday(From);
    public DateOnly LastDay => CalendarLoader.MoscowToday(To).AddDays(-1);

    private static DateTimeOffset MoscowMidnight(DateOnly day)
    {
        var local = day.ToDateTime(TimeOnly.MinValue);
        return new DateTimeOffset(local, SlaService.Moscow.GetUtcOffset(local));
    }
}

/// <summary>Заявка для отчёта — только поля, нужные формулам раздела 4.</summary>
public record ReportItem(
    short Status, DateTimeOffset? ClosedAt, DateTimeOffset? ArrivedAt, DateTimeOffset? CompletedAt,
    DateTimeOffset ReactionDue, DateTimeOffset ResolutionDue,
    string SlaCode, string CustomerName, int? EngineerId, bool TimeTrusted);

/// <summary>Интервал работы (work_log) для расчёта загрузки.</summary>
public record ReportInterval(int EmployeeId, DateTimeOffset StartedAt, DateTimeOffset? EndedAt);

/// <summary>Строка отчёта: всего закрыто, устранено в срок, реакций в срок; проценты — null, если заявок нет.</summary>
public record SlaRow(string Name, int Total, int ResolvedInTime, int? ResolvedPercent, int ReactedInTime, int? ReactedPercent);

public record EngineerLoadRow(string Name, int Closed, int WorkMinutes);

public record SlaReport(
    ReportPeriod Period, SlaRow Total, IReadOnlyList<SlaRow> ByLevel, IReadOnlyList<SlaRow> ByCustomer,
    int UntrustedCount, IReadOnlyList<EngineerLoadRow> Engineers);

/// <summary>
/// Отчёт по SLA за период (В2, функция 11; формулы — проект, раздел 4). Заявки периода — со статусом
/// 6 «Закрыта» и closed_at внутри периода; отменённые (7) и приостановленные (8) не учитываются.
/// Расчёт вынесен в статические методы без базы данных — их проверяют модульные тесты.
/// </summary>
public class ReportService(AppDbContext db)
{
    public async Task<SlaReport> BuildAsync(ReportPeriod period, DateTimeOffset now)
    {
        var closed = await db.Requests.AsNoTracking()
            .Where(r => r.Status == RequestStatus.Closed && r.ClosedAt >= period.From && r.ClosedAt < period.To)
            .Select(r => new ReportItem(r.Status, r.ClosedAt, r.ArrivedAt, r.CompletedAt, r.ReactionDue, r.ResolutionDue,
                r.SlaLevel!.Code, r.Contract!.Customer!.Name, r.EngineerId, r.TimeTrusted))
            .ToListAsync();

        // интервалы работы на объекте (kind 2), пересекающие период
        var intervals = await db.WorkLogs.AsNoTracking()
            .Where(w => w.Kind == 2 && w.StartedAt < period.To && (w.EndedAt == null || w.EndedAt > period.From))
            .Select(w => new ReportInterval(w.EmployeeId, w.StartedAt, w.EndedAt))
            .ToListAsync();

        var levels = await db.SlaLevels.OrderBy(l => l.Code).Select(l => l.Code).ToListAsync();
        var customers = await db.Customers.OrderBy(c => c.Code).Select(c => c.Name).ToListAsync();
        var engineerIds = intervals.Select(i => i.EmployeeId).Concat(closed.Where(c => c.EngineerId != null).Select(c => c.EngineerId!.Value)).ToList();
        var engineers = await db.Employees.AsNoTracking()
            .Where(e => e.Role == 1 || engineerIds.Contains(e.Id))
            .OrderBy(e => e.FullName)
            .ToDictionaryAsync(e => e.Id, e => e.FullName);

        return Build(closed, intervals, levels, customers, engineers, period, now);
    }

    /// <summary>Сборка отчёта из готовых данных.</summary>
    public static SlaReport Build(
        IEnumerable<ReportItem> requests, IEnumerable<ReportInterval> intervals,
        IEnumerable<string> levelCodes, IEnumerable<string> customerNames, IReadOnlyDictionary<int, string> engineers,
        ReportPeriod period, DateTimeOffset now)
    {
        var closed = ClosedInPeriod(requests, period);
        var work = intervals.ToList();
        return new SlaReport(
            period,
            Row("Всего", closed),
            levelCodes.Select(code => Row(code.Trim(), closed.Where(r => r.SlaCode.Trim() == code.Trim()))).ToList(),
            customerNames.Select(name => Row(name, closed.Where(r => r.CustomerName == name))).ToList(),
            closed.Count(r => !r.TimeTrusted),
            engineers.Select(e => new EngineerLoadRow(
                e.Value,
                closed.Count(r => r.EngineerId == e.Key),
                WorkMinutes(work.Where(w => w.EmployeeId == e.Key), period, now))).ToList());
    }

    /// <summary>Заявки периода: статус 6 и closed_at в [From, To).</summary>
    public static List<ReportItem> ClosedInPeriod(IEnumerable<ReportItem> requests, ReportPeriod period) =>
        requests.Where(r => r.Status == RequestStatus.Closed && r.ClosedAt >= period.From && r.ClosedAt < period.To).ToList();

    /// <summary>Строка: устранено в срок — completed_at ≤ resolution_due, реакция в срок — arrived_at ≤ reaction_due.</summary>
    public static SlaRow Row(string name, IEnumerable<ReportItem> closed)
    {
        var list = closed.ToList();
        var resolved = list.Count(r => r.CompletedAt != null && r.CompletedAt <= r.ResolutionDue);
        var reacted = list.Count(r => r.ArrivedAt != null && r.ArrivedAt <= r.ReactionDue);
        return new SlaRow(name, list.Count, resolved, Percent(resolved, list.Count), reacted, Percent(reacted, list.Count));
    }

    /// <summary>Процент с округлением до целого (0,5 — вверх); при нуле заявок — null («нет данных»).</summary>
    public static int? Percent(int part, int total) =>
        total == 0 ? null : (int)Math.Round(part * 100.0 / total, MidpointRounding.AwayFromZero);

    /// <summary>
    /// Минуты работы за период: Σ min(конец, конец периода) − max(начало, начало периода), если &gt; 0;
    /// открытый интервал считается до now. Дробные минуты отбрасываются (как в приложении).
    /// </summary>
    public static int WorkMinutes(IEnumerable<ReportInterval> intervals, ReportPeriod period, DateTimeOffset now)
    {
        var total = TimeSpan.Zero;
        foreach (var w in intervals)
        {
            var start = w.StartedAt > period.From ? w.StartedAt : period.From;
            var endRaw = w.EndedAt ?? now;
            var end = endRaw < period.To ? endRaw : period.To;
            if (end > start) total += end - start;
        }
        return (int)total.TotalMinutes;
    }
}
