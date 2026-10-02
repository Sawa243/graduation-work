using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using ServicePult.Data;
using ServicePult.Services;
using static ServicePult.Tests.SlaServiceTests;

namespace ServicePult.Tests;

/// <summary>
/// Регистрация заявки диспетчером (проект, разделы 2, 3, 10) на тестовой базе. Каждый тест работает
/// внутри транзакции, которая откатывается, — созданные заявки не мешают другим тестам.
/// Тестовые данные: договор 2 (заказчик 2, max_sla = 2), объект 3 — заказчика 2, объект 1 — заказчика 1,
/// оборудование 1 стоит на объекте 1; SLA-1 — id 1, SLA-2 — id 2; инженер — id 1, диспетчер — id 2.
/// </summary>
[Collection(ServerCollection.Name)]
public class RequestRegistrationTests(ServerFixture server)
{
    private static RegistrationInput Input(int site = 3, int sla = 2, int? equipment = null, int? engineer = 1) =>
        new(ContractId: 2, SiteId: site, EquipmentId: equipment, WorkTypeId: 1, SlaLevelId: sla,
            Description: "Не работает коммутатор", EngineerId: engineer);

    /// <summary>Выполняет действие в транзакции с откатом.</summary>
    private Task<T> InRollback<T>(Func<RequestService, Task<T>> action) => InRollback((_, s) => action(s));

    private Task<T> InRollback<T>(Func<AppDbContext, RequestService, Task<T>> action) => server.WithDb(async db =>
    {
        await using var tx = await db.Database.BeginTransactionAsync();
        var result = await action(db, new RequestService(db, NullLogger<RequestService>.Instance));
        await tx.RollbackAsync();
        return result;
    });

    [Fact]
    public async Task Контрольный_пример_пятница_16_30_SLA_2_сроки_пн_11_30_и_ср_13_30()
    {
        server.RequireDatabase();
        var r = await InRollback(async s =>
        {
            var result = await s.RegisterAsync(Input(), dispatcherId: 2, now: Msk("2026-10-02T16:30"));
            Assert.True(result.Ok, string.Join("; ", result.Errors));
            return result.Request!;
        });

        Assert.Equal(Msk("2026-10-02T16:30"), r.RegisteredAt);
        Assert.Equal(Msk("2026-10-05T11:30"), r.ReactionDue);
        Assert.Equal(Msk("2026-10-07T13:30"), r.ResolutionDue);
        Assert.Equal(RequestStatus.Assigned, r.Status); // инженер выбран — сразу «Назначена»
        Assert.Equal(2, r.DispatcherId);
        Assert.True(r.TimeTrusted);
    }

    [Fact]
    public async Task Номер_следующий_в_месяце_регистрации()
    {
        server.RequireDatabase();
        string expectedSeptember = "";
        var numbers = await InRollback(async (db, s) =>
        {
            // в сентябре 2026 в тестовых данных уже есть заявки (контрольная — З-2609-0137);
            // следующий номер — последний в месяце + 1, сколько бы заявок ни дал генератор
            var last = await db.Requests.Where(r => r.Number.StartsWith("З-2609-")).MaxAsync(r => r.Number);
            expectedSeptember = $"З-2609-{int.Parse(last[^4..]) + 1:D4}";
            var september = await s.RegisterAsync(Input(engineer: null), 2, Msk("2026-09-28T10:00"));
            // в октябре заявок ещё нет — нумерация месяца начинается с 0001
            var october1 = await s.RegisterAsync(Input(), 2, Msk("2026-10-01T09:00"));
            var october2 = await s.RegisterAsync(Input(), 2, Msk("2026-10-01T09:05"));
            // 31.10 23:30 по Москве — ещё октябрь; 01.11 00:10 по Москве (31.10 21:10 UTC) — уже ноябрь
            var lateOctober = await s.RegisterAsync(Input(), 2, Msk("2026-10-31T23:30"));
            var november = await s.RegisterAsync(Input(), 2, Msk("2026-11-01T00:10"));
            Assert.Equal(RequestStatus.New, september.Request!.Status); // без инженера — «Новая»
            return new[] { september, october1, october2, lateOctober, november }.Select(x => x.Request!.Number).ToArray();
        });

        Assert.Equal([expectedSeptember, "З-2610-0001", "З-2610-0002", "З-2610-0003", "З-2611-0001"], numbers);
    }

    [Fact]
    public async Task Уровень_SLA_выше_разрешённого_договором_отклоняется()
    {
        server.RequireDatabase();
        var result = await InRollback(s => s.RegisterAsync(Input(sla: 1), 2, Msk("2026-10-02T16:30")));

        Assert.False(result.Ok);
        Assert.Contains(result.Errors, e => e.Contains("выше разрешённого договором"));
    }

    [Fact]
    public async Task Объект_чужого_заказчика_отклоняется()
    {
        server.RequireDatabase();
        var result = await InRollback(s => s.RegisterAsync(Input(site: 1), 2, Msk("2026-10-02T16:30")));

        Assert.False(result.Ok);
        Assert.Contains(result.Errors, e => e.Contains("другому заказчику"));
    }

    [Fact]
    public async Task Оборудование_с_другого_объекта_отклоняется()
    {
        server.RequireDatabase();
        var result = await InRollback(s => s.RegisterAsync(Input(equipment: 1), 2, Msk("2026-10-02T16:30")));

        Assert.False(result.Ok);
        Assert.Contains(result.Errors, e => e.Contains("на другом объекте"));
    }

    [Fact]
    public async Task Отмена_и_назначение_только_в_статусах_1_и_2()
    {
        server.RequireDatabase();
        var (onTheWay, cancelled, reassigned) = await InRollback(async (db, s) =>
        {
            var created = (await s.RegisterAsync(Input(engineer: null), 2, Msk("2026-10-02T16:30"))).Request!;
            var assign = await s.AssignAsync(created.Id, engineerId: 1, dispatcherId: 2);
            var cancel = await s.CancelAsync(created.Id, "Заказчик сам устранил неисправность", 2);

            // заявка, по которой инженер уже в пути, диспетчером не меняется
            var started = (await s.RegisterAsync(Input(), 2, Msk("2026-10-02T16:40"))).Request!;
            started.Status = RequestStatus.OnTheWay;
            await db.SaveChangesAsync();
            var onWay = await s.CancelAsync(started.Id, "Проверка", 2);
            return (onWay, cancel, assign);
        });

        Assert.Null(reassigned);
        Assert.Null(cancelled);
        Assert.Contains("только с подтверждением инженера", onTheWay);
    }
}
