using System.Globalization;
using ServicePult.Services;

namespace ServicePult.Tests;

/// <summary>
/// Контрольный пример проекта системы (раздел 8) и граничные случаи — те же, что в тестах
/// SlaCalculator приложения (SlaCalculatorTest.kt), с теми же ожиданиями.
/// Уровни SLA — легенда 6.1: SLA-1 120/480 мин 24×7, SLA-2 240/1440 мин рабочего времени.
/// </summary>
public class SlaServiceTests
{
    private readonly SlaService _noHolidays = new(WorkCalendar.Empty);
    private const int Work = SlaService.ModeWorkHours;
    private const int Round = SlaService.Mode24x7;

    /// <summary>Московское время из строки «2026-10-02T16:30».</summary>
    internal static DateTimeOffset Msk(string text)
    {
        var local = DateTime.Parse(text, CultureInfo.InvariantCulture);
        return new DateTimeOffset(local, SlaService.Moscow.GetUtcOffset(local));
    }

    internal static SlaService Calc(params (string Day, bool IsWorking)[] days) =>
        new(new WorkCalendar(days.ToDictionary(d => DateOnly.Parse(d.Day, CultureInfo.InvariantCulture), d => d.IsWorking)));

    private static TimeSpan Min(int minutes) => TimeSpan.FromMinutes(minutes);

    // ------------------------------------------------ зона

    [Fact]
    public void Зона_Europe_Moscow_находится_и_даёт_смещение_плюс_3()
    {
        Assert.Equal(TimeSpan.FromHours(3), SlaService.Moscow.GetUtcOffset(new DateTime(2026, 10, 2)));
        Assert.Equal(TimeSpan.FromHours(3), SlaService.Moscow.GetUtcOffset(new DateTime(2026, 1, 15)));
    }

    // ------------------------------------------------ контрольный пример

    [Fact]
    public void Контрольный_пример_SLA2_пятница_16_30()
    {
        var t0 = Msk("2026-10-02T16:30");
        Assert.Equal(Msk("2026-10-05T11:30"), _noHolidays.Due(t0, 240, Work));   // реакция — пн 11:30
        Assert.Equal(Msk("2026-10-07T13:30"), _noHolidays.Due(t0, 1440, Work));  // устранение — ср 13:30
    }

    [Fact]
    public void Праздник_в_понедельник_сдвигает_сроки_на_день()
    {
        var c = Calc(("2026-10-05", false));
        var t0 = Msk("2026-10-02T16:30");
        Assert.Equal(Msk("2026-10-06T11:30"), c.Due(t0, 240, Work));    // вт 11:30
        Assert.Equal(Msk("2026-10-08T13:30"), c.Due(t0, 1440, Work));   // чт 13:30
    }

    [Fact]
    public void SLA1_считается_круглосуточно()
    {
        var t0 = Msk("2026-10-02T16:30");
        Assert.Equal(Msk("2026-10-02T18:30"), _noHolidays.Due(t0, 120, Round));  // пт 18:30
        Assert.Equal(Msk("2026-10-03T00:30"), _noHolidays.Due(t0, 480, Round));  // сб 00:30
    }

    [Fact]
    public void Срок_записывается_по_московскому_времени_со_смещением()
    {
        var utc = Msk("2026-10-02T16:30").ToUniversalTime();
        var due = _noHolidays.Due(utc, 240, Work);
        Assert.Equal(TimeSpan.FromHours(3), due.Offset);
        Assert.Equal(new DateTime(2026, 10, 5, 11, 30, 0), due.DateTime);
    }

    // ------------------------------------------------ граничные случаи

    [Fact]
    public void Регистрация_ровно_в_18_00__отсчёт_с_9_00_следующего_рабочего_дня() =>
        Assert.Equal(Msk("2026-10-05T13:00"), _noHolidays.Due(Msk("2026-10-02T18:00"), 240, Work));

    [Fact]
    public void Регистрация_ровно_в_9_00__отсчёт_сразу() =>
        Assert.Equal(Msk("2026-10-01T13:00"), _noHolidays.Due(Msk("2026-10-01T09:00"), 240, Work));

    [Fact]
    public void Регистрация_до_начала_рабочего_дня() =>
        Assert.Equal(Msk("2026-10-01T13:00"), _noHolidays.Due(Msk("2026-10-01T07:15"), 240, Work));

    [Fact]
    public void Регистрация_в_выходной() =>
        Assert.Equal(Msk("2026-10-05T13:00"), _noHolidays.Due(Msk("2026-10-03T12:00"), 240, Work));

    [Fact]
    public void Нулевое_время_SLA__ошибка_входных_данных() =>
        Assert.Throws<ArgumentException>(() => _noHolidays.Due(Msk("2026-10-02T16:30"), 0, Work));

    [Fact]
    public void Отрицательное_время_SLA__ошибка_входных_данных() =>
        Assert.Throws<ArgumentException>(() => _noHolidays.Due(Msk("2026-10-02T16:30"), -5, Round));

    [Fact]
    public void Рабочая_суббота_20_февраля_2027()
    {
        var c = Calc(("2027-02-20", true), ("2027-02-22", false), ("2027-02-23", false));
        // пт 16:30 → 90 мин в пятницу, остальные 150 — в рабочую субботу
        Assert.Equal(Msk("2027-02-20T11:30"), c.Due(Msk("2027-02-19T16:30"), 240, Work));
    }

    [Fact]
    public void Переход_через_Новый_год_с_каникулами()
    {
        var c = Calc(("2026-12-31", false), ("2027-01-01", false), ("2027-01-04", false), ("2027-01-05", false),
            ("2027-01-06", false), ("2027-01-07", false), ("2027-01-08", false));
        // ср 30.12 16:00: 120 мин; дальше 11.01 — 540, 12.01 — 540, 13.01 — ещё 240 → 13:00
        Assert.Equal(Msk("2027-01-13T13:00"), c.Due(Msk("2026-12-30T16:00"), 1440, Work));
    }

    [Fact]
    public void Срок_ровно_18_00_если_время_укладывается_в_день_без_остатка() =>
        Assert.Equal(Msk("2026-10-02T18:00"), _noHolidays.Due(Msk("2026-10-02T14:00"), 240, Work));

    [Fact]
    public void Секунды_в_моменте_регистрации_не_теряются() =>
        Assert.Equal(Msk("2026-10-05T11:30:20"), _noHolidays.Due(Msk("2026-10-02T16:30:20"), 240, Work));

    // ------------------------------------------------ остаток и признак

    [Fact]
    public void Остаток_считается_в_минутах_режима()
    {
        // пт 17:00 → пн 11:30: 60 мин в пятницу + 150 в понедельник
        Assert.Equal(Min(210), _noHolidays.Remaining(Msk("2026-10-02T17:00"), Msk("2026-10-05T11:30"), Work));
    }

    [Fact]
    public void После_срока_остаток_отрицательный() =>
        Assert.Equal(Min(-30), _noHolidays.Remaining(Msk("2026-10-05T12:00"), Msk("2026-10-05T11:30"), Work));

    [Fact]
    public void Признак_срока__граница_25_процентов()
    {
        Assert.Equal(SlaState.InTime, SlaService.State(Min(60), 240));
        Assert.Equal(SlaState.AtRisk, SlaService.State(Min(59), 240));
        Assert.Equal(SlaState.Overdue, SlaService.State(TimeSpan.Zero, 240));
        Assert.Equal(SlaState.Overdue, SlaService.State(Min(-1), 240));
    }

    [Fact]
    public void Индикатор_до_прибытия_по_реакции_после_по_устранению()
    {
        var reaction = Msk("2026-10-05T11:30");
        var resolution = Msk("2026-10-07T13:30");
        var before = _noHolidays.Indicator(Msk("2026-10-05T11:00"), reaction, resolution, null, null, 240, 1440, Work);
        Assert.Equal(SlaStage.Reaction, before.Stage);
        Assert.Equal(SlaState.AtRisk, before.State);   // 30 мин < 60 мин
        var after = _noHolidays.Indicator(Msk("2026-10-05T12:00"), reaction, resolution,
            Msk("2026-10-05T11:20"), null, 240, 1440, Work);
        Assert.Equal(SlaStage.Resolution, after.Stage);
        Assert.Equal(SlaState.InTime, after.State);
    }

    [Fact]
    public void После_выполнения_работ_признак__уложились_ли_в_срок_устранения()
    {
        var due = Msk("2026-10-07T13:30");
        var ok = _noHolidays.Indicator(Msk("2026-10-09T10:00"), Msk("2026-10-05T11:30"), due,
            Msk("2026-10-05T11:00"), Msk("2026-10-07T13:30"), 240, 1440, Work);
        Assert.Equal(SlaState.InTime, ok.State);
        Assert.Null(ok.Remaining);
        var late = _noHolidays.Indicator(Msk("2026-10-09T10:00"), Msk("2026-10-05T11:30"), due,
            Msk("2026-10-05T11:00"), Msk("2026-10-07T13:31"), 240, 1440, Work);
        Assert.Equal(SlaState.Overdue, late.State);
    }
}
