using ServicePult.Services;
using static ServicePult.Tests.SlaServiceTests;

namespace ServicePult.Tests;

/// <summary>
/// Дополнительная проверка расчётного модуля — те же случаи и ожидания, что в
/// SlaCalculatorExtraTest.kt приложения: алгоритм раздела 8, формулы остатка и признака срока
/// (раздел 4), правило рабочего дня (раздел 2.1, таблица 9). Расчёт — в комментарии к каждому тесту.
/// SLA-3: реакция 1 080 мин (18 ч), устранение 2 700 мин (45 ч) рабочего времени пн–пт 9:00–18:00.
/// </summary>
public class SlaServiceExtraTests
{
    private readonly SlaService _noHolidays = new(WorkCalendar.Empty);

    /// <summary>Исключения производственного календаря 2026–2027 — раздел calendar_day файла seed.json.</summary>
    private readonly SlaService _seedCalendar = Calc(
        ("2026-01-01", false), ("2026-01-02", false), ("2026-01-05", false), ("2026-01-06", false),
        ("2026-01-07", false), ("2026-01-08", false), ("2026-01-09", false), ("2026-02-23", false),
        ("2026-03-09", false), ("2026-05-01", false), ("2026-05-11", false), ("2026-06-12", false),
        ("2026-11-04", false), ("2026-12-31", false), ("2027-01-01", false), ("2027-01-04", false),
        ("2027-01-05", false), ("2027-01-06", false), ("2027-01-07", false), ("2027-01-08", false),
        ("2027-02-20", true), ("2027-02-22", false), ("2027-02-23", false), ("2027-03-08", false),
        ("2027-05-03", false), ("2027-05-10", false), ("2027-06-14", false), ("2027-11-04", false),
        ("2027-11-05", false), ("2027-12-31", false));

    private const int Work = SlaService.ModeWorkHours;
    private const int Round = SlaService.Mode24x7;
    private const int Sla3Reaction = 1080;
    private const int Sla3Resolution = 2700;

    private static TimeSpan Min(int minutes) => TimeSpan.FromMinutes(minutes);

    // ------------------------------------------------ SLA-3: сроки

    [Fact]
    public void SLA3_через_выходные_без_праздников()
    {
        // Чт 01.10.2026 14:00. Реакция 1080: чт 14–18 = 240, R = 840; пт 540, R = 300;
        // сб, вс пропущены; пн 05.10 9:00 + 300 = 14:00.
        // Устранение 2700: чт 240 → 2460; пт 540 → 1920; пн 540 → 1380; вт 540 → 840;
        // ср 540 → 300; чт 08.10 9:00 + 300 = 14:00 (ровно 5 рабочих дней).
        var t0 = Msk("2026-10-01T14:00");
        Assert.Equal(Msk("2026-10-05T14:00"), _noHolidays.Due(t0, Sla3Reaction, Work));
        Assert.Equal(Msk("2026-10-08T14:00"), _noHolidays.Due(t0, Sla3Resolution, Work));
    }

    [Fact]
    public void SLA3_срок_ровно_в_18_00_не_переносится_на_следующее_утро()
    {
        // Пн 05.10.2026 9:00. Реакция 1080 = 540 + 540: пн 540, R = 540; вт A = 540, R ≤ A (п. 7)
        // → вт 06.10 18:00. Устранение 2700 = 5 × 540 → пт 09.10 18:00.
        var t0 = Msk("2026-10-05T09:00");
        Assert.Equal(Msk("2026-10-06T18:00"), _noHolidays.Due(t0, Sla3Reaction, Work));
        Assert.Equal(Msk("2026-10-09T18:00"), _noHolidays.Due(t0, Sla3Resolution, Work));
    }

    [Fact]
    public void SLA3_через_1_мая_2026_по_календарю_seed()
    {
        // Чт 30.04.2026 12:00. Реакция 1080: чт 12–18 = 360, R = 720; пт 01.05 праздник, сб, вс;
        // пн 04.05 (в исключениях нет, будний) 540, R = 180; вт 05.05 9:00 + 180 = 12:00.
        // Устранение 2700: чт 360 → 2340; пн 04.05 540 → 1800; вт 540 → 1260; ср 540 → 720;
        // чт 07.05 540 → 180; пт 08.05 9:00 + 180 = 12:00.
        var t0 = Msk("2026-04-30T12:00");
        Assert.Equal(Msk("2026-05-05T12:00"), _seedCalendar.Due(t0, Sla3Reaction, Work));
        Assert.Equal(Msk("2026-05-08T12:00"), _seedCalendar.Due(t0, Sla3Resolution, Work));
    }

    [Fact]
    public void SLA3_через_9_11_мая_2026_по_календарю_seed()
    {
        // Ср 06.05.2026 15:00. Реакция 1080: ср 180, R = 900; чт 540, R = 360; пт 08.05 9:00 + 360 = 15:00.
        // Устранение 2700: ср 180 → 2520; чт 540 → 1980; пт 540 → 1440; сб 09, вс 10, пн 11.05 (перенос)
        // пропущены; вт 12.05 540 → 900; ср 13.05 540 → 360; чт 14.05 9:00 + 360 = 15:00.
        var t0 = Msk("2026-05-06T15:00");
        Assert.Equal(Msk("2026-05-08T15:00"), _seedCalendar.Due(t0, Sla3Reaction, Work));
        Assert.Equal(Msk("2026-05-14T15:00"), _seedCalendar.Due(t0, Sla3Resolution, Work));
    }

    [Fact]
    public void SLA3_через_майские_2027__выходные_3_и_10_мая_по_календарю_seed()
    {
        // Пт 30.04.2027 16:00. Реакция 1080: пт 120, R = 960; сб 01, вс 02, пн 03.05 выходной;
        // вт 04.05 540, R = 420; ср 05.05 9:00 + 420 = 16:00.
        // Устранение 2700: пт 120 → 2580; вт 04 540 → 2040; ср 05 → 1500; чт 06 → 960; пт 07 → 420;
        // сб 08, вс 09, пн 10.05 выходной; вт 11.05 9:00 + 420 = 16:00.
        var t0 = Msk("2027-04-30T16:00");
        Assert.Equal(Msk("2027-05-05T16:00"), _seedCalendar.Due(t0, Sla3Reaction, Work));
        Assert.Equal(Msk("2027-05-11T16:00"), _seedCalendar.Due(t0, Sla3Resolution, Work));
    }

    [Fact]
    public void SLA3_с_рабочей_субботой_20_февраля_2027_и_праздниками_22_23_февраля()
    {
        // Пт 19.02.2027 10:00. Реакция 1080: пт 480, R = 600; сб 20.02 рабочая 540, R = 60;
        // вс, пн 22 и вт 23 — нерабочие; ср 24.02 9:00 + 60 = 10:00.
        // Устранение 2700: пт 480 → 2220; сб 540 → 1680; ср 24 540 → 1140; чт 25 → 600; пт 26 → 60;
        // сб 27, вс 28 пропущены; пн 01.03 9:00 + 60 = 10:00.
        var t0 = Msk("2027-02-19T10:00");
        Assert.Equal(Msk("2027-02-24T10:00"), _seedCalendar.Due(t0, Sla3Reaction, Work));
        Assert.Equal(Msk("2027-03-01T10:00"), _seedCalendar.Due(t0, Sla3Resolution, Work));
    }

    // ------------------------------------------------ SLA-2: регистрация в праздник и на границе дня

    [Fact]
    public void Регистрация_в_праздник_1_мая_2026__отсчёт_с_9_00_ближайшего_рабочего_дня()
    {
        // Пт 01.05.2026 10:00 — праздник; п. 4: сб, вс нерабочие → пн 04.05 9:00.
        // Реакция 240: пн 9:00 + 240 = 13:00. Устранение 1440: пн 540 → 900; вт 540 → 360;
        // ср 06.05 9:00 + 360 = 15:00.
        var t0 = Msk("2026-05-01T10:00");
        Assert.Equal(Msk("2026-05-04T13:00"), _seedCalendar.Due(t0, 240, Work));
        Assert.Equal(Msk("2026-05-06T15:00"), _seedCalendar.Due(t0, 1440, Work));
    }

    [Fact]
    public void Регистрация_в_17_59_и_в_18_01_пятницы()
    {
        // Пт 02.10.2026 17:59: A = 1 мин, R = 239; пн 05.10 9:00 + 239 = 12:59.
        // Пт 18:01: время ≥ 18:00 (п. 4) → пн 9:00; 9:00 + 240 = 13:00.
        Assert.Equal(Msk("2026-10-05T12:59"), _noHolidays.Due(Msk("2026-10-02T17:59"), 240, Work));
        Assert.Equal(Msk("2026-10-05T13:00"), _noHolidays.Due(Msk("2026-10-02T18:01"), 240, Work));
    }

    [Fact]
    public void SLA1_круглосуточно_не_замечает_праздников()
    {
        // Чт 31.12.2026 22:00 (нерабочий день по seed) + 120 = пт 01.01.2027 00:00; + 480 = 06:00.
        var t0 = Msk("2026-12-31T22:00");
        Assert.Equal(Msk("2027-01-01T00:00"), _seedCalendar.Due(t0, 120, Round));
        Assert.Equal(Msk("2027-01-01T06:00"), _seedCalendar.Due(t0, 480, Round));
    }

    // ------------------------------------------------ остаток

    [Fact]
    public void Остаток_через_выходные_и_1_мая_2026()
    {
        // Сейчас чт 30.04.2026 17:00, срок вт 05.05 12:00 (рабочее время):
        // чт 17–18 = 60; пт 01.05 праздник, сб, вс — 0; пн 04.05 540; вт 9–12 = 180. Итого 780.
        Assert.Equal(Min(780), _seedCalendar.Remaining(Msk("2026-04-30T17:00"), Msk("2026-05-05T12:00"), Work));
        // В выходной остаток не убывает. Срок пн 05.10.2026 11:30. Из сб 10:00 и из вс 20:00
        // рабочих минут до срока одинаково: сб, вс — 0; пн 9:00–11:30 = 150.
        var due = Msk("2026-10-05T11:30");
        Assert.Equal(Min(150), _noHolidays.Remaining(Msk("2026-10-03T10:00"), due, Work));
        Assert.Equal(Min(150), _noHolidays.Remaining(Msk("2026-10-04T20:00"), due, Work));
    }

    [Fact]
    public void Просрочка_через_праздники_9_11_мая_2026()
    {
        // Срок пт 08.05.2026 17:00, сейчас вт 12.05 10:00: пт 17–18 = 60; сб, вс, пн 11.05 — 0;
        // вт 9–10 = 60. Просрочено на 120 мин рабочего времени → остаток −120.
        Assert.Equal(Min(-120), _seedCalendar.Remaining(Msk("2026-05-12T10:00"), Msk("2026-05-08T17:00"), Work));
    }

    [Fact]
    public void Остаток_SLA1_считается_круглосуточно()
    {
        // Пт 02.10.2026 17:00 → пн 05.10 11:30: 2 сут 18 ч 30 мин = 3990 мин (выходные входят).
        // После срока: сб 03.10 01:00 при сроке сб 00:30 → −30.
        Assert.Equal(Min(3990), _noHolidays.Remaining(Msk("2026-10-02T17:00"), Msk("2026-10-05T11:30"), Round));
        Assert.Equal(Min(-30), _noHolidays.Remaining(Msk("2026-10-03T01:00"), Msk("2026-10-03T00:30"), Round));
    }

    [Fact]
    public void В_момент_срока_остаток_ровно_0_и_заявка_просрочена()
    {
        // Раздел 4: «просрочена», если остаток ≤ 0. В момент T остаток = 0 в обоих режимах.
        var due = Msk("2026-10-05T11:30");
        var rWork = _noHolidays.Remaining(due, due, Work);
        var rRound = _noHolidays.Remaining(due, due, Round);
        Assert.Equal(TimeSpan.Zero, rWork);
        Assert.Equal(TimeSpan.Zero, rRound);
        Assert.Equal(SlaState.Overdue, SlaService.State(rWork, 240));
        // Срок пт 18:00, сейчас сб 12:00: рабочих минут между ними 0 → остаток 0, просрочена.
        var rWeekend = _noHolidays.Remaining(Msk("2026-10-03T12:00"), Msk("2026-10-02T18:00"), Work);
        Assert.Equal(TimeSpan.Zero, rWeekend);
        Assert.Equal(SlaState.Overdue, SlaService.State(rWeekend, 240));
    }

    // ------------------------------------------------ признак срока SLA-3

    [Fact]
    public void Признак_SLA3__граница_25_процентов()
    {
        // Реакция 1080: 0,25 × 1080 = 270. «Под угрозой» — строго остаток < 270.
        Assert.Equal(SlaState.InTime, SlaService.State(Min(270), Sla3Reaction));
        Assert.Equal(SlaState.AtRisk, SlaService.State(Min(269), Sla3Reaction));
        Assert.Equal(SlaState.AtRisk, SlaService.State(Min(270) - TimeSpan.FromSeconds(1), Sla3Reaction));
        // Устранение 2700: 0,25 × 2700 = 675.
        Assert.Equal(SlaState.InTime, SlaService.State(Min(675), Sla3Resolution));
        Assert.Equal(SlaState.AtRisk, SlaService.State(Min(674), Sla3Resolution));
    }

    [Fact]
    public void Индикатор_SLA3_через_1_мая_2026()
    {
        // Регистрация чт 30.04.2026 12:00: реакция вт 05.05 12:00, устранение пт 08.05 12:00 (см. выше).
        var reaction = Msk("2026-05-05T12:00");
        var resolution = Msk("2026-05-08T12:00");
        // Пн 04.05 12:00, до прибытия: пн 360 + вт 180 = 540 ≥ 270 → в срок.
        var a = _seedCalendar.Indicator(Msk("2026-05-04T12:00"), reaction, resolution, null, null,
            Sla3Reaction, Sla3Resolution, Work);
        Assert.Equal(SlaStage.Reaction, a.Stage);
        Assert.Equal(Min(540), a.Remaining);
        Assert.Equal(SlaState.InTime, a.State);
        // Пн 04.05 17:00: 60 + 180 = 240 < 270 → под угрозой.
        var b = _seedCalendar.Indicator(Msk("2026-05-04T17:00"), reaction, resolution, null, null,
            Sla3Reaction, Sla3Resolution, Work);
        Assert.Equal(Min(240), b.Remaining);
        Assert.Equal(SlaState.AtRisk, b.State);
        // Прибыл вт 05.05 11:00; сейчас чт 07.05 10:00 → до устранения: чт 480 + пт 180 = 660 < 675
        // → под угрозой по этапу устранения.
        var c = _seedCalendar.Indicator(Msk("2026-05-07T10:00"), reaction, resolution,
            Msk("2026-05-05T11:00"), null, Sla3Reaction, Sla3Resolution, Work);
        Assert.Equal(SlaStage.Resolution, c.Stage);
        Assert.Equal(Min(660), c.Remaining);
        Assert.Equal(SlaState.AtRisk, c.State);
    }
}
