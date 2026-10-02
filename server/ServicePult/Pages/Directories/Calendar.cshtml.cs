using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;

namespace ServicePult.Pages.Directories;

/// <summary>
/// Производственный календарь — дни-исключения (calendar_day): праздники и перенесённые выходные,
/// рабочие субботы. Влияет на сроки новых заявок; сроки уже зарегистрированных не пересчитываются.
/// </summary>
public class CalendarModel(AppDbContext db, ILogger<CalendarModel> log) : PageModel
{
    [BindProperty(SupportsGet = true)] public int? Year { get; set; }

    [BindProperty] public DateOnly? Day { get; set; }
    [BindProperty] public bool IsWorking { get; set; }
    [BindProperty] public string? Note { get; set; }

    public List<CalendarDay> Items { get; private set; } = [];
    public List<int> Years { get; private set; } = [];
    public string? Error { get; private set; }

    public async Task OnGetAsync() => await LoadAsync();

    public async Task<IActionResult> OnPostAddAsync()
    {
        var note = Note?.Trim();
        if (Day == null) Error = "Укажите дату";
        else if (!Format.IsReasonableYear(Day.Value.Year)) Error = $"Дата — только с {Format.MinYear} по {Format.MaxYear} год";
        else if (note?.Length > 60) Error = "Примечание длиннее 60 знаков";
        if (Error != null)
        {
            await LoadAsync();
            return Page();
        }

        // Если день уже есть — запись заменяется (например, праздник стал рабочим днём).
        var item = await db.CalendarDays.FindAsync(Day!.Value);
        var isNew = item == null;
        item ??= db.CalendarDays.Add(new CalendarDay { Day = Day.Value }).Entity;
        item.IsWorking = IsWorking;
        item.Note = string.IsNullOrEmpty(note) ? null : note;
        await db.SaveChangesAsync();

        log.LogInformation("[Справочник] Календарь: {Action} {Day:dd.MM.yyyy} — {Kind}, сотрудник {Employee}",
            isNew ? "добавлен" : "изменён", Day, IsWorking ? "рабочий" : "нерабочий", User.FindFirst("sub")?.Value);
        TempData["Message"] = $"День {Format.Date(Day.Value)} {(isNew ? "добавлен" : "изменён")}";
        return Redirect($"/Directories/Calendar?Year={Day.Value.Year}");
    }

    public async Task<IActionResult> OnPostDeleteAsync(DateOnly day)
    {
        var item = await db.CalendarDays.FindAsync(day);
        if (item != null)
        {
            db.CalendarDays.Remove(item);
            await db.SaveChangesAsync();
            log.LogInformation("[Справочник] Календарь: удалён {Day:dd.MM.yyyy}, сотрудник {Employee}",
                day, User.FindFirst("sub")?.Value);
            TempData["Message"] = $"День {Format.Date(day)} удалён";
        }
        return Redirect($"/Directories/Calendar?Year={day.Year}");
    }

    private async Task LoadAsync()
    {
        Years = await db.CalendarDays.Select(d => d.Day.Year).Distinct().OrderBy(y => y).ToListAsync();
        if (Year != null && !Format.IsReasonableYear(Year.Value))
        {
            Error ??= $"Год — только с {Format.MinYear} по {Format.MaxYear}. Показан текущий год";
            Year = null;
        }
        Year ??= DateTime.Today.Year;
        var from = new DateOnly(Year.Value, 1, 1);
        var to = from.AddYears(1);
        Items = await db.CalendarDays.AsNoTracking().Where(d => d.Day >= from && d.Day < to).OrderBy(d => d.Day).ToListAsync();
    }
}
