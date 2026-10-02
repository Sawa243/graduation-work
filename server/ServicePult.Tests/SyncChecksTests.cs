using System.Net;
using System.Text;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using ServicePult.Controllers;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Tests;

/// <summary>
/// Проверки синхронизации по итогам рецензии: имена файлов без учёта регистра, длины полей push,
/// закрытие отклонённой заявки, неизменность времени после «Работы выполнены», порядок отметок,
/// запас pull на незафиксированные транзакции, время без смещения.
/// Каждый тест создаёт себе отдельную заявку (NewRequest), чтобы не зависеть от других тестов.
/// </summary>
[Collection(ServerCollection.Name)]
public class SyncChecksTests(ServerFixture server)
{
    private static int _lastNumber = 9100;

    /// <summary>Текущее время с точностью до секунды: так значение из базы совпадает с отправленным.</summary>
    private static DateTimeOffset NowSec()
    {
        var now = DateTimeOffset.Now;
        return now.AddTicks(-(now.Ticks % TimeSpan.TicksPerSecond));
    }

    /// <summary>Новая заявка инженера engineer (id 1) — копия заявки 6 из seed с нужным статусом и отметками.</summary>
    private Task<Request> NewRequest(short status, DateTimeOffset registeredAt, DateTimeOffset? arrivedAt = null,
        DateTimeOffset? completedAt = null, string? resultText = null) => server.WithDb(async db =>
    {
        var sample = await db.Requests.AsNoTracking().SingleAsync(r => r.Id == 6);
        var r = new Request
        {
            // номер из декабря 2025: тест нумерации проверяет сентябрь–ноябрь 2026, и эти
            // заявки (они остаются в тестовой базе до следующего прогона) не должны сдвигать его номера
            Number = $"З-2512-{Interlocked.Increment(ref _lastNumber)}",
            ContractId = sample.ContractId, SiteId = sample.SiteId, WorkTypeId = sample.WorkTypeId,
            SlaLevelId = sample.SlaLevelId, DispatcherId = sample.DispatcherId, EngineerId = 1,
            Description = "Заявка для проверки сервера", RegisteredAt = registeredAt,
            ReactionDue = registeredAt.AddHours(4), ResolutionDue = registeredAt.AddHours(24),
            ArrivedAt = arrivedAt, CompletedAt = completedAt, ResultText = resultText, Status = status, TimeTrusted = true,
        };
        db.Requests.Add(r);
        await db.SaveChangesAsync();
        return r;
    });

    private static object Req(Request r, short status, DateTimeOffset? arrivedAt = null, DateTimeOffset? completedAt = null,
        DateTimeOffset? closedAt = null, string? resultText = null, string? scannedCode = null) => new
    {
        r.Id, Status = status,
        ArrivedAt = arrivedAt ?? r.ArrivedAt, CompletedAt = completedAt ?? r.CompletedAt, ClosedAt = closedAt ?? r.ClosedAt,
        ScannedCode = scannedCode ?? r.ScannedCode, ResultText = resultText ?? r.ResultText, r.EquipmentId, r.TimeTrusted,
    };

    private Task<Request> Stored(int id) => server.WithDb(db => db.Requests.AsNoTracking().SingleAsync(r => r.Id == id));

    private Task<string?> ShaOnServer(string kind, string name) => server.Service<FileStorage>().Sha256OfAsync(kind, name);

    // ---------------------------------------------------------------- файлы: регистр и точка в конце

    [Fact]
    public async Task Принятый_файл_не_заменить_другим_регистром_или_точкой_в_конце()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var now = NowSec();
        var r = await NewRequest(RequestStatus.OnSite, now.AddHours(-2), arrivedAt: now.AddHours(-1));
        var original = Encoding.UTF8.GetBytes("принятое фото");
        var forged = Encoding.UTF8.GetBytes("подменённое фото");
        var name = $"case-probe-{Guid.NewGuid():N}.jpg";

        Assert.Equal(HttpStatusCode.OK, (await server.Upload(client, "photo", name, original)).StatusCode);
        var push = await server.Push(client, new
        {
            Photo = new[] { new { Id = Guid.NewGuid(), RequestId = r.Id, Kind = 1, FileName = name, TakenAt = now, Sha256 = ServerFixture.Sha256(original) } },
        });
        Assert.Empty(push.Rejected);

        // точка в конце: Windows отбросил бы её и записал в тот же файл
        Assert.Equal(HttpStatusCode.BadRequest, (await server.Upload(client, "photo", name + ".", forged)).StatusCode);
        // другой регистр: в Windows это тот же файл — отказ; в Linux — просто другой файл
        var upper = await server.Upload(client, "photo", name.ToUpperInvariant(), forged);
        if (OperatingSystem.IsWindows())
        {
            Assert.Equal(HttpStatusCode.Conflict, upper.StatusCode);
            // то же содержимое под другим регистром — не ошибка
            Assert.Equal(HttpStatusCode.OK, (await server.Upload(client, "photo", name.ToUpperInvariant(), original)).StatusCode);
        }
        // тем же именем — тоже отказ: на файл ссылается принятое фото
        Assert.Equal(HttpStatusCode.Conflict, (await server.Upload(client, "photo", name, forged)).StatusCode);

        Assert.Equal(ServerFixture.Sha256(original), await ShaOnServer("photo", name));
    }

    [Fact]
    public async Task Файл_без_ссылок_заменяется_только_под_тем_же_именем()
    {
        // подпись, переснятая после отказа в приёме акта, уходит под тем же именем signature_<id>.png
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var name = $"signature_probe{Guid.NewGuid():N}.png";
        var first = Encoding.UTF8.GetBytes("первая подпись");
        var second = Encoding.UTF8.GetBytes("вторая подпись");

        Assert.Equal(HttpStatusCode.OK, (await server.Upload(client, "signature", name, first)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await server.Upload(client, "signature", name, second)).StatusCode);
        Assert.Equal(ServerFixture.Sha256(second), await ShaOnServer("signature", name));

        if (OperatingSystem.IsWindows())
        {
            var upper = await server.Upload(client, "signature", name.ToUpperInvariant(), first);
            Assert.Equal(HttpStatusCode.Conflict, upper.StatusCode);
            Assert.Equal(ServerFixture.Sha256(second), await ShaOnServer("signature", name));
        }
    }

    [Fact]
    public async Task Запись_фото_с_именем_в_другом_регистре_отклоняется()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var now = NowSec();
        var r = await NewRequest(RequestStatus.OnSite, now.AddHours(-2), arrivedAt: now.AddHours(-1));
        var content = Encoding.UTF8.GetBytes("фото для проверки регистра");
        var name = $"reg-{Guid.NewGuid():N}.jpg";
        Assert.Equal(HttpStatusCode.OK, (await server.Upload(client, "photo", name, content)).StatusCode);

        var push = await server.Push(client, new
        {
            Photo = new[] { new { Id = Guid.NewGuid(), RequestId = r.Id, Kind = 1, FileName = name.ToUpperInvariant(), TakenAt = now, Sha256 = ServerFixture.Sha256(content) } },
        });
        var rejected = Assert.Single(push.Rejected);
        if (OperatingSystem.IsWindows()) Assert.Contains("регистром", rejected.Reason);
        Assert.Empty(push.Accepted.Photo);
    }

    // ---------------------------------------------------------------- длины полей

    [Fact]
    public async Task Слишком_длинное_замечание_отклоняет_только_свою_запись()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var now = NowSec();
        var r = await NewRequest(RequestStatus.OnSite, now.AddHours(-2), arrivedAt: now.AddHours(-1));
        var item = await server.WithDb(db => db.ChecklistItems.FirstAsync(i => i.WorkTypeId == r.WorkTypeId));
        var workLog = Guid.NewGuid();

        var push = await server.Push(client, new
        {
            ChecklistResult = new[] { new { Id = Guid.NewGuid(), RequestId = r.Id, ChecklistItemId = item.Id, Done = true, Comment = new string('ж', 301), DoneAt = now } },
            WorkLog = new[] { new { Id = workLog, RequestId = r.Id, EmployeeId = 1, Kind = 2, StartedAt = now.AddMinutes(-50), TimeTrusted = true } },
        });

        Assert.Equal(new[] { workLog }, push.Accepted.WorkLog);
        var rejected = Assert.Single(push.Rejected);
        Assert.Equal("checklist_result", rejected.Table);
        Assert.Contains("300", rejected.Reason);

        // ровно 300 знаков — принимается
        var ok = await server.Push(client, new
        {
            ChecklistResult = new[] { new { Id = Guid.NewGuid(), RequestId = r.Id, ChecklistItemId = item.Id, Done = true, Comment = new string('ж', 300), DoneAt = now } },
        });
        Assert.Empty(ok.Rejected);
    }

    // ---------------------------------------------------------------- закрытие

    [Fact]
    public async Task Отклонённая_заявка_не_закрывается_и_не_попадает_в_принятые()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var now = NowSec();
        var r = await NewRequest(RequestStatus.WorkDone, now.AddHours(-3), arrivedAt: now.AddHours(-2),
            completedAt: now.AddHours(-1), resultText: "Заменён блок питания");
        await server.WithDb(async db =>
        {
            db.Acts.Add(new Act
            {
                Id = Guid.NewGuid(), RequestId = r.Id, Number = "А" + r.Number[1..], ActDate = DateOnly.FromDateTime(now.Date),
                EngineerId = 1, SignMode = 1, SignerName = "Проверочный Заказчик", PdfFile = "act-probe.pdf",
                SignedAt = now, Sha256 = new string('a', 64), WorkMin = 60,
            });
            return await db.SaveChangesAsync();
        });

        var push = await server.Push(client, new { Request = new[] { Req(r, 6, closedAt: now, scannedCode: new string('Q', 101)) } });
        var rejected = Assert.Single(push.Rejected);
        Assert.Contains("scanned_code", rejected.Reason);
        Assert.Empty(push.Accepted.Request);
        var stored = await Stored(r.Id);
        Assert.Equal(RequestStatus.WorkDone, stored.Status);
        Assert.Null(stored.ClosedAt);

        // та же заявка без ошибки закрывается
        var ok = await server.Push(client, new { Request = new[] { Req(r, 6, closedAt: now) } });
        Assert.Empty(ok.Rejected);
        Assert.Equal(new[] { r.Id }, ok.Accepted.Request);
        Assert.Equal(RequestStatus.Closed, (await Stored(r.Id)).Status);
    }

    // ---------------------------------------------------------------- время

    [Fact]
    public async Task После_работ_выполнены_и_приостановки_время_и_что_сделано_не_переписываются()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var now = NowSec();
        var done = await NewRequest(RequestStatus.WorkDone, now.AddHours(-3), arrivedAt: now.AddHours(-2),
            completedAt: now.AddHours(-1), resultText: "Заменён блок питания");
        var suspended = await NewRequest(RequestStatus.Suspended, now.AddHours(-3), arrivedAt: now.AddHours(-2),
            resultText: "Ждём запчасть");

        var changedText = await server.Push(client, new { Request = new[] { Req(done, 5, resultText: "Другой текст") } });
        Assert.Contains("не меняются", Assert.Single(changedText.Rejected).Reason);
        var changedTime = await server.Push(client, new { Request = new[] { Req(done, 5, completedAt: now.AddMinutes(-10)) } });
        Assert.Contains("не меняются", Assert.Single(changedTime.Rejected).Reason);
        var changedArrival = await server.Push(client, new { Request = new[] { Req(suspended, 8, arrivedAt: now.AddMinutes(-90)) } });
        Assert.Contains("не меняются", Assert.Single(changedArrival.Rejected).Reason);

        // повтор тех же значений принимается (идемпотентность)
        var same = await server.Push(client, new { Request = new[] { Req(done, 5), Req(suspended, 8) } });
        Assert.Empty(same.Rejected);

        var stored = await Stored(done.Id);
        Assert.Equal("Заменён блок питания", stored.ResultText);
        Assert.Equal(done.CompletedAt, stored.CompletedAt);
    }

    [Fact]
    public async Task Отметки_времени_идут_по_порядку_и_не_из_будущего()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var now = NowSec();
        var registered = now.AddHours(-2);
        var r = await NewRequest(RequestStatus.OnTheWay, registered);

        var beforeRegistration = await server.Push(client, new { Request = new[] { Req(r, 4, arrivedAt: registered.AddMinutes(-1)) } });
        Assert.Contains("раньше регистрации", Assert.Single(beforeRegistration.Rejected).Reason);

        var future = await server.Push(client, new { Request = new[] { Req(r, 4, arrivedAt: now.AddHours(1)) } });
        Assert.Contains("позже текущего времени", Assert.Single(future.Rejected).Reason);

        var futureLog = await server.Push(client, new
        {
            WorkLog = new[] { new { Id = Guid.NewGuid(), RequestId = r.Id, EmployeeId = 1, Kind = 1, StartedAt = now.AddHours(1), TimeTrusted = false } },
        });
        Assert.Contains("позже текущего времени", Assert.Single(futureLog.Rejected).Reason);

        var reversedLog = await server.Push(client, new
        {
            WorkLog = new[] { new { Id = Guid.NewGuid(), RequestId = r.Id, EmployeeId = 1, Kind = 1, StartedAt = now.AddMinutes(-30), EndedAt = now.AddMinutes(-40), TimeTrusted = true } },
        });
        Assert.Contains("раньше начала", Assert.Single(reversedLog.Rejected).Reason);

        // часы телефона спешат на 5 минут — в пределах допуска
        var slightlyAhead = await server.Push(client, new { Request = new[] { Req(r, 4, arrivedAt: now.AddMinutes(5)) } });
        Assert.Empty(slightlyAhead.Rejected);
        Assert.Equal(RequestStatus.OnSite, (await Stored(r.Id)).Status);
    }

    [Fact]
    public async Task Время_без_смещения_отклоняется_с_понятным_заголовком()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();

        var pull = await client.GetAsync("/api/sync/pull?since=2026-09-25T16:30:00", TestContext.Current.CancellationToken);
        Assert.Equal(HttpStatusCode.BadRequest, pull.StatusCode);
        Assert.Contains("смещением", (await server.Read<ProblemDetails>(pull)).Title);

        var json = $$"""{"work_log":[{"id":"{{Guid.NewGuid()}}","request_id":6,"employee_id":1,"kind":2,"started_at":"2026-09-27T10:00:00","time_trusted":true}]}""";
        var push = await client.PostAsync("/api/sync/push", new StringContent(json, Encoding.UTF8, "application/json"),
            TestContext.Current.CancellationToken);
        Assert.Equal(HttpStatusCode.BadRequest, push.StatusCode);
        Assert.Contains("без смещения", (await server.Read<ProblemDetails>(push)).Title);
    }

    // ---------------------------------------------------------------- pull и незафиксированные транзакции

    [Fact]
    public async Task Изменение_зафиксированное_после_pull_приходит_в_следующий_pull()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var r = await NewRequest(RequestStatus.Assigned, NowSec().AddHours(-1));
        var first = await server.Pull(client);
        const string changed = "Описание уточнено диспетчером";

        var during = await server.WithDb(async db =>
        {
            // изменение записано (updated_at уже стоит), но транзакция ещё не зафиксирована
            await using var tx = await db.Database.BeginTransactionAsync();
            (await db.Requests.FindAsync(r.Id))!.Description = changed;
            await db.SaveChangesAsync();

            var pullDuring = await server.Pull(client, first.ServerTime);
            await tx.CommitAsync();
            return pullDuring;
        });
        Assert.DoesNotContain(during.Request, x => x.Description == changed); // до COMMIT изменения не видно

        // следующий pull — с server_time прошлого, который уже позже updated_at этой записи
        var after = await server.Pull(client, during.ServerTime);
        Assert.Contains(after.Request, x => x.Id == r.Id && x.Description == changed);
    }

    [Fact]
    public void Граница_отбора_pull_на_5_минут_раньше_since()
    {
        var since = new DateTimeOffset(2026, 9, 27, 12, 0, 0, TimeSpan.FromHours(3));
        Assert.Equal(since.AddMinutes(-5), SyncController.LowerBound(since));
        Assert.Equal(DateTimeOffset.MinValue, SyncController.LowerBound(DateTimeOffset.MinValue));
    }
}
