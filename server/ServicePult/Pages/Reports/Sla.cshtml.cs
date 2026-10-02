using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using ServicePult.Services;

namespace ServicePult.Pages.Reports;

/// <summary>В2 «Отчёт по SLA» (функция 11, только руководитель службы). Расчёт — ReportService.</summary>
public class SlaModel(ReportService reports) : PageModel
{
    /// <summary>Первый и последний день периода; по умолчанию — текущий месяц (по Москве).</summary>
    [BindProperty(SupportsGet = true)] public DateOnly? From { get; set; }
    [BindProperty(SupportsGet = true)] public DateOnly? To { get; set; }

    public SlaReport Report { get; private set; } = null!;
    public string? Error { get; private set; }

    public async Task OnGetAsync()
    {
        var now = DateTimeOffset.UtcNow;
        var period = ReportPeriod.MonthOf(now);
        if (From != null && To != null)
        {
            if (From > To) Error = "Начало периода позже конца — показан текущий месяц";
            else if (!Format.IsReasonableYear(From.Value.Year) || !Format.IsReasonableYear(To.Value.Year))
                Error = $"Даты периода — только с {Format.MinYear} по {Format.MaxYear} год. Показан текущий месяц";
            else period = ReportPeriod.FromDates(From.Value, To.Value);
        }
        From = period.FirstDay;
        To = period.LastDay;
        Report = await reports.BuildAsync(period, now);
    }
}
