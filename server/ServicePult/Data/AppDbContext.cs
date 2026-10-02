using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace ServicePult.Data;

/// <summary>База «Сервисного пульта»: 14 таблиц информационной модели (PostgreSQL).</summary>
public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    public DbSet<Customer> Customers => Set<Customer>();
    public DbSet<Contract> Contracts => Set<Contract>();
    public DbSet<SlaLevel> SlaLevels => Set<SlaLevel>();
    public DbSet<Site> Sites => Set<Site>();
    public DbSet<Equipment> Equipment => Set<Equipment>();
    public DbSet<WorkType> WorkTypes => Set<WorkType>();
    public DbSet<ChecklistItem> ChecklistItems => Set<ChecklistItem>();
    public DbSet<Employee> Employees => Set<Employee>();
    public DbSet<CalendarDay> CalendarDays => Set<CalendarDay>();
    public DbSet<Request> Requests => Set<Request>();
    public DbSet<ChecklistResult> ChecklistResults => Set<ChecklistResult>();
    public DbSet<Photo> Photos => Set<Photo>();
    public DbSet<WorkLog> WorkLogs => Set<WorkLog>();
    public DbSet<Act> Acts => Set<Act>();

    protected override void ConfigureConventions(ModelConfigurationBuilder builder)
    {
        // Столбец timestamptz принимает только время в UTC — переводим при записи.
        // Момент времени от этого не меняется, меняется только запись смещения.
        builder.Properties<DateTimeOffset>().HaveConversion<UtcConverter>();
    }

    protected override void OnModelCreating(ModelBuilder model)
    {
        // Каскадного удаления нет: справочник или заявку со ссылками на них удалить нельзя.
        foreach (var fk in model.Model.GetEntityTypes().SelectMany(t => t.GetForeignKeys()))
            fk.DeleteBehavior = DeleteBehavior.Restrict;
    }

    public override int SaveChanges(bool acceptAllChangesOnSuccess)
    {
        StampUpdatedAt();
        return base.SaveChanges(acceptAllChangesOnSuccess);
    }

    public override Task<int> SaveChangesAsync(bool acceptAllChangesOnSuccess, CancellationToken ct = default)
    {
        StampUpdatedAt();
        return base.SaveChangesAsync(acceptAllChangesOnSuccess, ct);
    }

    /// <summary>
    /// updated_at ставит сервер сам при каждой записи: по нему pull отбирает изменения
    /// (updated_at &gt; since), поэтому время с телефона сюда не принимается.
    /// </summary>
    private void StampUpdatedAt()
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var entry in ChangeTracker.Entries<IHasUpdatedAt>())
        {
            if (entry.State is EntityState.Added or EntityState.Modified)
                entry.Entity.UpdatedAt = now;
        }
    }

    public class UtcConverter() : ValueConverter<DateTimeOffset, DateTimeOffset>(
        v => v.ToUniversalTime(), v => v.ToUniversalTime());
}
