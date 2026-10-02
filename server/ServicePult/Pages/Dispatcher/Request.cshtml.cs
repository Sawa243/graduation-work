using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Pages.Dispatcher;

/// <summary>
/// Карточка заявки на пульте: реквизиты, сроки, назначение или переназначение инженера и отмена
/// (функция 8). Менять заявку диспетчер может только в статусах 1–2 (проект, разделы 1 и 3).
/// </summary>
public class RequestModel(AppDbContext db, RequestService requests) : PageModel
{
    public Request Item { get; private set; } = null!;
    public SlaIndicator? Indicator { get; private set; }
    public List<Employee> Engineers { get; private set; } = [];
    public bool CanChange => RequestService.DispatcherCanChange(Item.Status);

    [BindProperty] public int? EngineerId { get; set; }
    [BindProperty] public string? CancelReason { get; set; }

    public async Task<IActionResult> OnGetAsync(int id) => await LoadAsync(id) ? Page() : NotFound();

    public async Task<IActionResult> OnPostAssignAsync(int id)
    {
        var error = EngineerId == null
            ? "Выберите инженера"
            : await requests.AssignAsync(id, EngineerId.Value, User.EmployeeId());
        return Done(id, error, "Инженер назначен");
    }

    public async Task<IActionResult> OnPostCancelAsync(int id)
    {
        var error = await requests.CancelAsync(id, CancelReason, User.EmployeeId());
        return Done(id, error, "Заявка отменена");
    }

    /// <summary>После действия — снова карточка (POST → redirect → GET), сообщение через TempData.</summary>
    private RedirectResult Done(int id, string? error, string success)
    {
        TempData[error == null ? "Message" : "Error"] = error ?? success;
        return Redirect($"/Dispatcher/Request/{id}");
    }

    private async Task<bool> LoadAsync(int id)
    {
        var item = await db.Requests.AsNoTracking()
            .Include(r => r.Site)
            .Include(r => r.Contract).ThenInclude(c => c!.Customer)
            .Include(r => r.Equipment)
            .Include(r => r.WorkType)
            .Include(r => r.SlaLevel)
            .Include(r => r.Engineer)
            .Include(r => r.Dispatcher)
            .Include(r => r.Parent)
            .FirstOrDefaultAsync(r => r.Id == id);
        if (item == null) return false;
        Item = item;
        Indicator = IndexModel.IndicatorOf(await CalendarLoader.SlaServiceAsync(db), item, DateTimeOffset.UtcNow);
        Engineers = await db.Employees.AsNoTracking()
            .Where(e => e.Role == 1 && !e.Blocked).OrderBy(e => e.FullName).ToListAsync();
        EngineerId = item.EngineerId;
        return true;
    }
}
