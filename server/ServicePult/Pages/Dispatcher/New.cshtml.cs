using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Pages.Dispatcher;

/// <summary>
/// «Новая заявка» (функция 7): договор, объект, оборудование, вид работ, уровень SLA, описание,
/// инженер. Списки на странице фильтруются скриптом по выбранному договору и объекту,
/// но все правила ещё раз проверяет RequestService при сохранении.
/// </summary>
public class NewModel(AppDbContext db, RequestService requests) : PageModel
{
    [BindProperty] public int? ContractId { get; set; }
    [BindProperty] public int? SiteId { get; set; }
    [BindProperty] public int? EquipmentId { get; set; }
    [BindProperty] public int? WorkTypeId { get; set; }
    [BindProperty] public int? SlaLevelId { get; set; }
    [BindProperty] public string Description { get; set; } = "";
    [BindProperty] public int? EngineerId { get; set; }

    public List<Contract> Contracts { get; private set; } = [];
    public List<Site> Sites { get; private set; } = [];
    public List<Equipment> EquipmentList { get; private set; } = [];
    public List<WorkType> WorkTypes { get; private set; } = [];
    public List<SlaLevel> SlaLevels { get; private set; } = [];
    public List<Employee> Engineers { get; private set; } = [];
    public List<string> Errors { get; private set; } = [];

    public async Task OnGetAsync() => await LoadListsAsync();

    public async Task<IActionResult> OnPostAsync()
    {
        if (ContractId == null) Errors.Add("Выберите договор");
        if (SiteId == null) Errors.Add("Выберите объект");
        if (WorkTypeId == null) Errors.Add("Выберите вид работ");
        if (SlaLevelId == null) Errors.Add("Выберите уровень SLA");
        if (Errors.Count == 0)
        {
            var result = await requests.RegisterAsync(
                new RegistrationInput(ContractId!.Value, SiteId!.Value, EquipmentId, WorkTypeId!.Value, SlaLevelId!.Value,
                    Description, EngineerId),
                User.EmployeeId(), DateTimeOffset.UtcNow);
            if (result.Ok)
            {
                var r = result.Request!;
                TempData["Message"] = $"Заявка {r.Number} зарегистрирована. Срок реакции — {Format.Time(r.ReactionDue)}, " +
                                      $"срок устранения — {Format.Time(r.ResolutionDue)}" +
                                      (r.EngineerId == null ? ". Инженер не назначен." : ".");
                return Redirect($"/Dispatcher/Request/{r.Id}");
            }
            Errors.AddRange(result.Errors);
        }
        await LoadListsAsync();
        return Page();
    }

    private async Task LoadListsAsync()
    {
        var today = CalendarLoader.MoscowToday(DateTimeOffset.UtcNow);
        // только договоры, действующие сегодня
        Contracts = await db.Contracts.AsNoTracking().Include(c => c.Customer)
            .Where(c => c.DateFrom <= today && (c.DateTo == null || c.DateTo >= today))
            .OrderBy(c => c.Number).ToListAsync();
        Sites = await db.Sites.AsNoTracking().OrderBy(s => s.Code).ToListAsync();
        EquipmentList = await db.Equipment.AsNoTracking().OrderBy(e => e.InvCode).ToListAsync();
        WorkTypes = await db.WorkTypes.AsNoTracking().OrderBy(w => w.Code).ToListAsync();
        SlaLevels = await db.SlaLevels.AsNoTracking().OrderBy(l => l.Code).ToListAsync();
        Engineers = await db.Employees.AsNoTracking().Where(e => e.Role == 1 && !e.Blocked)
            .OrderBy(e => e.FullName).ToListAsync();
    }
}
