using Microsoft.EntityFrameworkCore;
using ServicePult.Data;

namespace ServicePult.Services;

/// <summary>Производственный календарь из таблицы calendar_day для расчёта сроков SlaService.</summary>
public static class CalendarLoader
{
    public static async Task<WorkCalendar> LoadAsync(AppDbContext db) =>
        new(await db.CalendarDays.AsNoTracking().ToDictionaryAsync(d => d.Day, d => d.IsWorking));

    public static async Task<SlaService> SlaServiceAsync(AppDbContext db) => new(await LoadAsync(db));

    /// <summary>Сегодняшняя дата по московскому времени.</summary>
    public static DateOnly MoscowToday(DateTimeOffset now) => DateOnly.FromDateTime(SlaService.ToMoscow(now).DateTime);
}
