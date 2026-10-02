using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Pages.Dispatcher;

/// <summary>Строка таблицы: заявка и её признак срока (null — у отменённой и приостановленной).</summary>
public record RequestRow(Request Request, SlaIndicator? Indicator)
{
    /// <summary>Заявка завершена для диспетчера: закрыта, отменена или приостановлена.</summary>
    public bool Finished => Request.Status is RequestStatus.Closed or RequestStatus.Cancelled or RequestStatus.Suspended;
}

/// <summary>
/// В1 «Пульт диспетчера» (функция 9): заявки с признаком срока по текущему времени сервера,
/// фильтры, сортировка по действующему сроку, обновление страницы раз в минуту.
/// </summary>
public class IndexModel(AppDbContext db) : PageModel
{
    /// <summary>active — в работе (статусы 1–5), closed — закрытые, отменённые, приостановленные, all — все.</summary>
    [BindProperty(SupportsGet = true)] public string Show { get; set; } = "active";
    [BindProperty(SupportsGet = true)] public int? EngineerId { get; set; }
    [BindProperty(SupportsGet = true)] public int? CustomerId { get; set; }

    public List<RequestRow> Rows { get; private set; } = [];
    public List<Employee> Engineers { get; private set; } = [];
    public List<Customer> Customers { get; private set; } = [];
    public DateTimeOffset Now { get; private set; }

    public async Task OnGetAsync()
    {
        Now = DateTimeOffset.UtcNow;
        Engineers = await db.Employees.AsNoTracking().Where(e => e.Role == 1).OrderBy(e => e.FullName).ToListAsync();
        Customers = await db.Customers.AsNoTracking().OrderBy(c => c.Code).ToListAsync();

        var query = db.Requests.AsNoTracking()
            .Include(r => r.Site)
            .Include(r => r.Contract).ThenInclude(c => c!.Customer)
            .Include(r => r.WorkType)
            .Include(r => r.SlaLevel)
            .Include(r => r.Engineer)
            .AsQueryable();

        query = Show switch
        {
            "closed" => query.Where(r => r.Status >= RequestStatus.Closed),
            "all" => query,
            _ => query.Where(r => r.Status < RequestStatus.Closed),
        };
        if (EngineerId != null) query = query.Where(r => r.EngineerId == EngineerId);
        if (CustomerId != null) query = query.Where(r => r.Contract!.CustomerId == CustomerId);

        var sla = await CalendarLoader.SlaServiceAsync(db);
        Rows = (await query.ToListAsync())
            .Select(r => new RequestRow(r, IndicatorOf(sla, r, Now)))
            .ToList();

        // Сначала заявки в работе — по возрастанию действующего срока (ближайший сверху),
        // затем завершённые — последние сверху.
        Rows = Rows.Where(x => !x.Finished).OrderBy(x => x.Indicator!.Due)
            .Concat(Rows.Where(x => x.Finished).OrderByDescending(x => x.Indicator?.Due ?? x.Request.ResolutionDue))
            .ToList();

        ViewData["Refresh"] = 60; // автообновление раз в минуту
    }

    /// <summary>Признак срока (проект, раздел 4); у отменённой и приостановленной заявки его нет.</summary>
    public static SlaIndicator? IndicatorOf(SlaService sla, Request r, DateTimeOffset now) =>
        r.Status is RequestStatus.Cancelled or RequestStatus.Suspended
            ? null
            : sla.Indicator(now, r.ReactionDue, r.ResolutionDue, r.ArrivedAt, r.CompletedAt,
                r.SlaLevel!.ReactionMin, r.SlaLevel.ResolutionMin, r.SlaLevel.Mode);
}
