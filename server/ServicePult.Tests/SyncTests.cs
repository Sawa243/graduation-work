using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;
using ServicePult.Controllers;
using ServicePult.Data;

namespace ServicePult.Tests;

/// <summary>
/// Синхронизация и загрузка файлов (проект, раздел 10) на тестовых данных seed.json.
/// Заявки seed (все назначены инженеру engineer, id 1): 1, 2 — закрыты; 3 — отменена; 4 — приостановлена;
/// 5 — «Работы выполнены»; 6 — «На объекте»; 7 — «В пути»; 8 (контрольная), 9, 10 — «Назначена».
/// Каждый тест работает со своей заявкой, чтобы тесты не зависели друг от друга.
/// </summary>
[Collection(ServerCollection.Name)]
public class SyncTests(ServerFixture server)
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Now;

    private static object Req(Request r, short status, DateTimeOffset? arrivedAt = null, DateTimeOffset? completedAt = null,
        DateTimeOffset? closedAt = null, string? resultText = null) => new
    {
        r.Id, Status = status,
        ArrivedAt = arrivedAt ?? r.ArrivedAt, CompletedAt = completedAt ?? r.CompletedAt, ClosedAt = closedAt ?? r.ClosedAt,
        r.ScannedCode, ResultText = resultText ?? r.ResultText, r.EquipmentId, r.TimeTrusted,
    };

    private static short StatusOf(PullResponse pull, int id) => pull.Request.Single(r => r.Id == id).Status;

    // ---------------------------------------------------------------- pull

    [Fact]
    public async Task Полный_pull_содержит_контрольную_заявку_и_не_содержит_хешей_паролей()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();

        var raw = await client.GetStringAsync("/api/sync/pull", TestContext.Current.CancellationToken);
        Assert.DoesNotContain("pass_hash", raw);
        var pull = JsonSerializer.Deserialize<PullResponse>(raw, server.Json)!;

        // контрольная заявка: SLA-2, пятница 16:30, сроки — как в seed.json
        var control = pull.Request.Single(r => Regex.IsMatch(r.Number, @"^З-\d{4}-0137$"));
        Assert.Equal(2, control.SlaLevelId);
        using var seed = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "SeedData", "seed.json"), TestContext.Current.CancellationToken));
        var seedControl = seed.RootElement.GetProperty("request").EnumerateArray()
            .Single(r => r.GetProperty("number").GetString() == control.Number);
        Assert.Equal(seedControl.GetProperty("reaction_due").GetDateTimeOffset(), control.ReactionDue);
        Assert.Equal(seedControl.GetProperty("resolution_due").GetDateTimeOffset(), control.ResolutionDue);
        Assert.Equal(DayOfWeek.Friday, control.RegisteredAt.DayOfWeek);

        // справочники целиком, сотрудники — только нужные, логин — только у самого инженера
        Assert.Equal(3, pull.SlaLevel.Count);
        Assert.Equal(30, pull.CalendarDay.Count);
        Assert.Equal("engineer", pull.Employee.Single(e => e.Id == 1).Login);
        Assert.All(pull.Employee.Where(e => e.Id != 1), e => Assert.Null(e.Login));

        // закрытые (1, 2) и отменённая (3) заявки в список назначенных не входят
        Assert.DoesNotContain(1, pull.AssignedRequestIds);
        Assert.DoesNotContain(3, pull.AssignedRequestIds);
        Assert.Contains(control.Id, pull.AssignedRequestIds);
    }

    [Fact]
    public async Task Инкрементальный_pull_отдаёт_только_изменённое()
    {
        server.RequireDatabase();
        await server.AgeAllRowsAsync(); // иначе всё свежее попало бы в запас SinceMargin
        using var client = await server.EngineerClient();
        var first = await server.Pull(client);
        var r6 = first.Request.Single(r => r.Id == 6);

        var push = await server.Push(client, new { Request = new[] { Req(r6, r6.Status, resultText: "Проверены правила, ищем причину") } });
        Assert.Empty(push.Rejected);

        var next = await server.Pull(client, first.ServerTime);
        Assert.Equal(new[] { 6 }, next.Request.Select(r => r.Id));
        Assert.Empty(next.Customer);
        Assert.Empty(next.ChecklistItem);
        Assert.Empty(next.CalendarDay);
        Assert.NotEmpty(next.AssignedRequestIds);
    }

    // ---------------------------------------------------------------- статусы

    [Fact]
    public async Task Статус_меняется_вперёд_2_3_4()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var r9 = (await server.Pull(client)).Request.Single(r => r.Id == 9);
        var road = Guid.NewGuid();
        var work = Guid.NewGuid();
        var left = Now.AddMinutes(-40);
        var arrived = Now.AddMinutes(-5);

        var toWay = await server.Push(client, new
        {
            Request = new[] { Req(r9, 3) },
            WorkLog = new[] { new { Id = road, RequestId = 9, EmployeeId = 1, Kind = 1, StartedAt = left, TimeTrusted = true } },
        });
        Assert.Empty(toWay.Rejected);
        Assert.Equal(new[] { 9 }, toWay.Accepted.Request);

        var onSite = await server.Push(client, new
        {
            Request = new[] { Req(r9, 4, arrivedAt: arrived) },
            WorkLog = new object[]
            {
                new { Id = road, RequestId = 9, EmployeeId = 1, Kind = 1, StartedAt = left, EndedAt = arrived, TimeTrusted = true },
                new { Id = work, RequestId = 9, EmployeeId = 1, Kind = 2, StartedAt = arrived, TimeTrusted = true },
            },
        });
        Assert.Empty(onSite.Rejected);
        Assert.Equal(new[] { 9 }, onSite.Accepted.Request);

        var pull = await server.Pull(client);
        var after = pull.Request.Single(r => r.Id == 9);
        Assert.Equal(4, after.Status);
        Assert.Equal(arrived, after.ArrivedAt!.Value, TimeSpan.FromMilliseconds(1));
        Assert.Equal(2, pull.WorkLog.Count(w => w.RequestId == 9));
    }

    [Fact]
    public async Task Перескок_назад_и_отмена_инженером_отклоняются()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var pull = await server.Pull(client);
        var r10 = pull.Request.Single(r => r.Id == 10);
        var r6 = pull.Request.Single(r => r.Id == 6);

        // 2 → 5 без отметок прибытия и выполнения — перескок через «На объекте»
        var jump = await server.Push(client, new { Request = new[] { Req(r10, 5, resultText: "Сделано") } });
        var rejected = Assert.Single(jump.Rejected);
        Assert.Equal("request", rejected.Table);
        Assert.Equal("10", rejected.Id);
        Assert.Contains("прибытия", rejected.Reason);

        var cancel = await server.Push(client, new { Request = new[] { Req(r10, 7) } });
        Assert.Contains("диспетчер", Assert.Single(cancel.Rejected).Reason);

        var back = await server.Push(client, new { Request = new[] { Req(r6, 3) } });
        Assert.Contains("только вперёд", Assert.Single(back.Rejected).Reason);

        var after = await server.Pull(client);
        Assert.Equal(2, StatusOf(after, 10));
        Assert.Equal(4, StatusOf(after, 6));
    }

    [Fact]
    public async Task Повтор_той_же_отправки_ничего_не_ломает()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var pull = await server.Pull(client);
        var r7 = pull.Request.Single(r => r.Id == 7);
        var road = pull.WorkLog.Single(w => w.RequestId == 7 && w.EndedAt == null);
        var arrived = Now.AddMinutes(-3);
        var body = new
        {
            Request = new[] { Req(r7, 4, arrivedAt: arrived) },
            WorkLog = new object[]
            {
                new { road.Id, road.RequestId, road.EmployeeId, road.Kind, road.StartedAt, EndedAt = arrived, road.TimeTrusted },
                new { Id = Guid.NewGuid(), RequestId = 7, EmployeeId = 1, Kind = 2, StartedAt = arrived, TimeTrusted = true },
            },
        };

        var first = await server.Push(client, body);
        var second = await server.Push(client, body);

        Assert.Empty(first.Rejected);
        Assert.Empty(second.Rejected);
        Assert.Equal(first.Accepted.WorkLog, second.Accepted.WorkLog);
        Assert.Equal(new[] { 7 }, second.Accepted.Request);
        var count = await server.WithDb(db => db.WorkLogs.CountAsync(w => w.RequestId == 7));
        Assert.Equal(2, count);
    }

    [Fact]
    public async Task Изменения_по_закрытой_заявке_отклоняются_а_повтор_без_изменений_принимается()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var pull = await server.Pull(client);
        var r1 = pull.Request.Single(r => r.Id == 1);
        var item = pull.ChecklistItem.First(i => i.WorkTypeId == r1.WorkTypeId && !i.Required);

        var result = await server.Push(client, new
        {
            ChecklistResult = new[] { new { Id = Guid.NewGuid(), RequestId = 1, ChecklistItemId = item.Id, Done = true, DoneAt = Now } },
            Request = new[] { Req(r1, r1.Status, resultText: "Исправленный текст") },
        });
        Assert.Equal(2, result.Rejected.Count);
        Assert.All(result.Rejected, x => Assert.Contains("закрыта", x.Reason));

        var same = await server.Push(client, new { Request = new[] { Req(r1, r1.Status) } });
        Assert.Empty(same.Rejected);
        Assert.Equal(new[] { 1 }, same.Accepted.Request);
    }

    // ---------------------------------------------------------------- фото и файлы

    [Fact]
    public async Task Фото_принимается_только_с_загруженным_файлом_и_верной_контрольной_суммой()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var r6 = (await server.Pull(client)).Request.Single(r => r.Id == 6);
        var content = Encoding.UTF8.GetBytes("снимок индикации — тестовые байты");
        const string name = "photo-r6-test.jpg";

        object Photo(Guid id, string fileName, string sha) => new
        {
            Id = id, RequestId = r6.Id, ChecklistItemId = (int?)null, Kind = 1, FileName = fileName, TakenAt = Now, Sha256 = sha,
        };

        // файла ещё нет на сервере
        var notUploaded = await server.Push(client, new { Photo = new[] { Photo(Guid.NewGuid(), name, ServerFixture.Sha256(content)) } });
        Assert.Contains("не загружен", Assert.Single(notUploaded.Rejected).Reason);

        var upload = await server.Upload(client, "photo", name, content);
        Assert.Equal(HttpStatusCode.OK, upload.StatusCode);
        var uploaded = await server.Read<FileResponse>(upload);
        Assert.Equal(ServerFixture.Sha256(content), uploaded.Sha256);
        Assert.Equal(content.Length, uploaded.Size);

        // контрольная сумма не совпадает с файлом
        var wrongSha = await server.Push(client, new { Photo = new[] { Photo(Guid.NewGuid(), name, new string('0', 64)) } });
        Assert.Contains("Контрольная сумма", Assert.Single(wrongSha.Rejected).Reason);

        var photoId = Guid.NewGuid();
        var ok = await server.Push(client, new { Photo = new[] { Photo(photoId, name, uploaded.Sha256) } });
        Assert.Empty(ok.Rejected);
        Assert.Equal(new[] { photoId }, ok.Accepted.Photo);

        // повторная загрузка того же файла — не ошибка; другой файл под принятым именем — 409
        Assert.Equal(HttpStatusCode.OK, (await server.Upload(client, "photo", name, content)).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await server.Upload(client, "photo", name, [1, 2, 3])).StatusCode);

        // удалённое на телефоне фото удаляется и на сервере
        var deleted = await server.Push(client, new { Photo = new[] { new { Id = photoId, RequestId = r6.Id, Deleted = true } } });
        Assert.Empty(deleted.Rejected);
        Assert.False(await server.WithDb(db => db.Photos.AnyAsync(p => p.Id == photoId)));
    }

    [Fact]
    public async Task Загрузка_с_недопустимым_именем_или_видом_отклоняется()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        Assert.Equal(HttpStatusCode.BadRequest, (await server.Upload(client, "photo", "../evil.jpg", [1])).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await server.Upload(client, "photo", "..", [1])).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await server.Upload(client, "other", "a.jpg", [1])).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await server.Upload(client, "act", ".hidden.pdf", [1])).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await server.Upload(client, "act", "А 1.pdf", [1])).StatusCode);
    }

    [Fact]
    public async Task Акт_с_русской_буквой_в_имени_принимается()
    {
        // номер акта «А-ГГММ-NNNN» начинается с русской «А» — так называется и файл PDF в телефоне
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var response = await server.Upload(client, "act", "А-2609-9999.pdf", [1, 2, 3]);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    // ---------------------------------------------------------------- акт и закрытие

    [Fact]
    public async Task Акт_на_экране_закрывает_заявку_5_в_6()
    {
        server.RequireDatabase();
        using var client = await server.EngineerClient();
        var r5 = (await server.Pull(client)).Request.Single(r => r.Id == 5);
        Assert.Equal(5, r5.Status);
        var closedAt = Now;

        // без акта закрыть нельзя
        var noAct = await server.Push(client, new { Request = new[] { Req(r5, 6, closedAt: closedAt) } });
        Assert.Contains("без акта", Assert.Single(noAct.Rejected).Reason);

        var pdf = Encoding.UTF8.GetBytes("%PDF-1.4 тестовый акт");
        var sign = Encoding.UTF8.GetBytes("PNG подпись");
        Assert.Equal(HttpStatusCode.OK, (await server.Upload(client, "act", "act-r5.pdf", pdf)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await server.Upload(client, "signature", "sign-r5.png", sign)).StatusCode);

        var actId = Guid.NewGuid();
        var push = await server.Push(client, new
        {
            Request = new[] { Req(r5, 6, closedAt: closedAt) },
            Act = new[]
            {
                new
                {
                    Id = actId, RequestId = 5, Number = "А" + r5.Number[1..], ActDate = DateOnly.FromDateTime(closedAt.Date),
                    EngineerId = 1, SignMode = 1, SignerName = "Кузнецов Андрей Тестович", SignerPosition = "Инженер АХО",
                    SignatureFile = "sign-r5.png", PdfFile = "act-r5.pdf", SignedAt = closedAt,
                    Sha256 = ServerFixture.Sha256(pdf), WorkMin = 50,
                },
            },
        });
        Assert.Empty(push.Rejected);
        Assert.Equal(new[] { actId }, push.Accepted.Act);
        Assert.Equal(new[] { 5 }, push.Accepted.Request);

        var after = (await server.Pull(client)).Request.Single(r => r.Id == 5);
        Assert.Equal(6, after.Status);
        Assert.Equal(closedAt, after.ClosedAt!.Value, TimeSpan.FromMilliseconds(1));
    }
}
