using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;

namespace ServicePult.Pages.Directories;

/// <summary>Справочник оборудования (только просмотр).</summary>
public class EquipmentModel(AppDbContext db) : PageModel
{
    public List<Equipment> Items { get; private set; } = [];

    public async Task OnGetAsync() =>
        Items = await db.Equipment.AsNoTracking().Include(e => e.Site).OrderBy(e => e.InvCode).ToListAsync();
}
