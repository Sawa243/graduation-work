using ServicePult.Data;
using ServicePult.Services;
using static ServicePult.Tests.SlaServiceTests;

namespace ServicePult.Tests;

/// <summary>Отчёт по SLA на подготовленных данных (проект, раздел 4) — без базы данных.</summary>
public class ReportServiceTests
{
    private static readonly ReportPeriod October = ReportPeriod.Month(2026, 10);
    private static readonly DateTimeOffset Now = Msk("2026-11-05T12:00");

    /// <summary>Закрытая заявка: срок устранения 10.10 18:00, реакции 10.10 12:00.</summary>
    private static ReportItem Closed(string closedAt, bool resolvedInTime = true, bool reactedInTime = true,
        string sla = "SLA-2", string customer = "Заказчик А", short status = RequestStatus.Closed, bool trusted = true, int? engineer = 1) =>
        new(status, Msk(closedAt),
            ArrivedAt: reactedInTime ? Msk("2026-10-10T11:00") : Msk("2026-10-10T13:00"),
            CompletedAt: resolvedInTime ? Msk("2026-10-10T17:00") : Msk("2026-10-10T19:00"),
            ReactionDue: Msk("2026-10-10T12:00"), ResolutionDue: Msk("2026-10-10T18:00"),
            SlaCode: sla, CustomerName: customer, EngineerId: engineer, TimeTrusted: trusted);

    private static SlaReport Build(params ReportItem[] items) =>
        ReportService.Build(items, [], ["SLA-1", "SLA-2", "SLA-3"], ["Заказчик А", "Заказчик Б"],
            new Dictionary<int, string> { [1] = "Тестов И. П." }, October, Now);

    [Fact]
    public void Доля_в_срок_2_из_3_округляется_до_67_процентов()
    {
        var report = Build(
            Closed("2026-10-10T20:00"),
            Closed("2026-10-11T10:00"),
            Closed("2026-10-12T10:00", resolvedInTime: false, reactedInTime: false));

        Assert.Equal(3, report.Total.Total);
        Assert.Equal(2, report.Total.ResolvedInTime);
        Assert.Equal(67, report.Total.ResolvedPercent);
        Assert.Equal(67, report.Total.ReactedPercent);
    }

    [Fact]
    public void Процент_округляется_до_целого_половина_вверх()
    {
        Assert.Equal(33, ReportService.Percent(1, 3));
        Assert.Equal(50, ReportService.Percent(1, 2));
        Assert.Equal(13, ReportService.Percent(1, 8)); // 12,5 → 13
        Assert.Null(ReportService.Percent(0, 0));     // нет заявок — «нет данных», а не 0 %
    }

    [Fact]
    public void Отменённые_и_приостановленные_не_считаются()
    {
        var report = Build(
            Closed("2026-10-10T20:00"),
            Closed("2026-10-10T20:00", resolvedInTime: false, status: RequestStatus.Cancelled),
            Closed("2026-10-10T20:00", resolvedInTime: false, status: RequestStatus.Suspended));

        Assert.Equal(1, report.Total.Total);
        Assert.Equal(100, report.Total.ResolvedPercent);
    }

    [Fact]
    public void Границы_периода_по_Москве_31_10_23_30_МСК_относится_к_октябрю()
    {
        var lateOctober = Closed("2026-10-31T23:30"); // в UTC это уже 31.10 20:30 — но важна граница по Москве
        var november = Closed("2026-11-01T00:00");     // полночь 1 ноября по Москве — уже ноябрь
        var firstOfOctober = Closed("2026-10-01T00:00");
        var september = Closed("2026-09-30T23:59");

        var closed = ReportService.ClosedInPeriod([lateOctober, november, firstOfOctober, september], October);

        Assert.Equal([lateOctober, firstOfOctober], closed);
        Assert.Equal(Msk("2026-10-01T00:00"), October.From);
        Assert.Equal(Msk("2026-11-01T00:00"), October.To);
        Assert.Contains(lateOctober, ReportService.ClosedInPeriod([lateOctober], ReportPeriod.FromDates(new(2026, 10, 31), new(2026, 10, 31))));
    }

    [Fact]
    public void Разбивка_по_уровням_и_заказчикам_и_недоверенное_время()
    {
        var report = Build(
            Closed("2026-10-10T20:00", sla: "SLA-1", customer: "Заказчик А"),
            Closed("2026-10-11T20:00", sla: "SLA-2", customer: "Заказчик Б", resolvedInTime: false, trusted: false),
            Closed("2026-10-12T20:00", sla: "SLA-2", customer: "Заказчик Б"));

        Assert.Equal(["SLA-1", "SLA-2", "SLA-3"], report.ByLevel.Select(r => r.Name));
        Assert.Equal([1, 2, 0], report.ByLevel.Select(r => r.Total));
        Assert.Equal(50, report.ByLevel[1].ResolvedPercent);
        Assert.Null(report.ByLevel[2].ResolvedPercent);
        Assert.Equal([1, 2], report.ByCustomer.Select(r => r.Total));
        Assert.Equal(1, report.UntrustedCount);
        Assert.Equal(3, report.Engineers.Single().Closed);
    }

    [Fact]
    public void Минуты_работы_обрезаются_по_границам_периода()
    {
        ReportInterval Work(string from, string? to) => new(1, Msk(from), to == null ? null : Msk(to));
        var minutes = ReportService.WorkMinutes(
        [
            Work("2026-09-30T23:00", "2026-10-01T01:00"), // 60 мин в октябре
            Work("2026-10-15T10:00", "2026-10-15T11:30"), // 90
            Work("2026-10-31T23:30", "2026-11-01T00:30"), // 30
            Work("2026-11-02T10:00", "2026-11-02T11:00"), // вне периода
        ], October, Now);
        Assert.Equal(180, minutes);

        // незавершённый интервал считается до текущего момента
        Assert.Equal(45, ReportService.WorkMinutes([Work("2026-10-20T10:00", null)],
            October, Msk("2026-10-20T10:45")));
    }
}
