using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;

namespace ServicePult.Pages.Directories;

/// <summary>Справочник заказчиков с договорами (только просмотр).</summary>
public class CustomersModel(AppDbContext db) : PageModel
{
    public record Row(Customer Customer, List<Contract> Contracts, int SiteCount);

    public List<Row> Items { get; private set; } = [];

    public async Task OnGetAsync()
    {
        var contracts = await db.Contracts.AsNoTracking().OrderBy(c => c.Number).ToListAsync();
        var sites = await db.Sites.AsNoTracking().GroupBy(s => s.CustomerId)
            .Select(g => new { g.Key, Count = g.Count() }).ToDictionaryAsync(x => x.Key, x => x.Count);
        Items = (await db.Customers.AsNoTracking().OrderBy(c => c.Code).ToListAsync())
            .Select(c => new Row(c, contracts.Where(k => k.CustomerId == c.Id).ToList(), sites.GetValueOrDefault(c.Id)))
            .ToList();
    }
}
