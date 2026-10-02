using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;

namespace ServicePult.Pages.Directories;

/// <summary>Справочник объектов обслуживания (только просмотр).</summary>
public class SitesModel(AppDbContext db) : PageModel
{
    public List<Site> Items { get; private set; } = [];

    public async Task OnGetAsync() =>
        Items = await db.Sites.AsNoTracking().Include(s => s.Customer).OrderBy(s => s.Code).ToListAsync();
}
