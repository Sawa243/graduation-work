using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.SeedData;

/// <summary>
/// Тестовые данные (seed.json, его пишет tools/make_seed.py). Заливаются при старте, если таблица
/// employee пуста, одной транзакцией. Идентификаторы из файла сохраняются, после заливки
/// счётчики identity выставляются на max(id) + 1, чтобы новые записи пульта не столкнулись с ними.
/// </summary>
public static class SeedLoader
{
    /// <summary>Разделы файла; имена — как таблицы (лишние разделы файла пропускаются).</summary>
    private class SeedFile
    {
        public List<Customer> Customer { get; set; } = [];
        public List<Contract> Contract { get; set; } = [];
        public List<SlaLevel> SlaLevel { get; set; } = [];
        public List<Site> Site { get; set; } = [];
        public List<Equipment> Equipment { get; set; } = [];
        public List<WorkType> WorkType { get; set; } = [];
        public List<ChecklistItem> ChecklistItem { get; set; } = [];
        public List<Employee> Employee { get; set; } = [];
        public List<CalendarDay> CalendarDay { get; set; } = [];
        public List<Request> Request { get; set; } = [];
        public List<ChecklistResult> ChecklistResult { get; set; } = [];
        public List<Photo> Photo { get; set; } = [];
        public List<WorkLog> WorkLog { get; set; } = [];
        public List<Act> Act { get; set; } = [];
    }

    // таблицы с числовым id, который выдаёт сервер
    private static readonly string[] IdentityTables =
        ["customer", "contract", "sla_level", "site", "equipment", "work_type", "checklist_item", "employee", "request"];

    public static async Task LoadIfEmptyAsync(AppDbContext db, string path, ILogger log)
    {
        if (await db.Employees.AnyAsync()) return;
        if (!File.Exists(path))
        {
            log.LogWarning("Таблица employee пуста, а файла тестовых данных {Path} нет — база осталась пустой", path);
            return;
        }

        var seed = JsonSerializer.Deserialize<SeedFile>(await File.ReadAllTextAsync(path), JsonSetup.Create())
                   ?? throw new InvalidOperationException("Файл тестовых данных пуст");
        if (seed.Employee.Any(e => string.IsNullOrEmpty(e.PassHash)))
            throw new InvalidOperationException("В seed.json у сотрудника нет pass_hash — перезапустите tools/make_seed.py");

        await using var tx = await db.Database.BeginTransactionAsync();
        db.AddRange(seed.Customer);
        db.AddRange(seed.Contract);
        db.AddRange(seed.SlaLevel);
        db.AddRange(seed.Site);
        db.AddRange(seed.Equipment);
        db.AddRange(seed.WorkType);
        db.AddRange(seed.ChecklistItem);
        db.AddRange(seed.Employee);
        db.AddRange(seed.CalendarDay);
        db.AddRange(seed.Request);
        db.AddRange(seed.ChecklistResult);
        db.AddRange(seed.Photo);
        db.AddRange(seed.WorkLog);
        db.AddRange(seed.Act);
        await db.SaveChangesAsync();

        foreach (var table in IdentityTables)
        {
            // имена таблиц — константы из списка выше, не ввод пользователя
#pragma warning disable EF1002
            await db.Database.ExecuteSqlRawAsync(
                $"SELECT setval(pg_get_serial_sequence('\"{table}\"', 'id'), COALESCE((SELECT MAX(id) FROM \"{table}\"), 0) + 1, false)");
#pragma warning restore EF1002
        }
        await tx.CommitAsync();
        db.ChangeTracker.Clear();

        log.LogInformation("Загружены тестовые данные {Path}: заявок {Requests}, сотрудников {Employees}",
            path, seed.Request.Count, seed.Employee.Count);
    }
}
