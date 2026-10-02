using System.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Controllers;

/// <summary>Синхронизация с приложением инженера: pull и push (проект, раздел 10).</summary>
[ApiController]
[Route("api/sync")]
[Authorize(Policy = Program.EngineerPolicy)]
public class SyncController(AppDbContext db, PushService pushService) : ControllerBase
{
    /// <summary>
    /// Запас при отборе изменений: pull отдаёт записи с updated_at &gt; since − 5 минут.
    /// updated_at ставится в момент SaveChanges, а видна запись другим только после COMMIT; если pull
    /// прошёл между ними, его server_time уже позже updated_at, и без запаса запись не пришла бы
    /// в телефон никогда. Push держит транзакцию доли секунды, 5 минут — с большим запасом.
    /// Повторно пришедшие записи безвредны: приложение вставляет или обновляет их по id.
    /// </summary>
    public static readonly TimeSpan SinceMargin = TimeSpan.FromMinutes(5);

    /// <summary>
    /// Изменения с момента since (пусто — всё): справочники, заявки инженера и их записи.
    /// since — server_time прошлого pull.
    /// </summary>
    [HttpGet("pull")]
    public async Task<ActionResult<PullResponse>> Pull([FromQuery] string? since)
    {
        DateTimeOffset? from = null;
        if (!string.IsNullOrWhiteSpace(since))
        {
            // «+» в неэкранированном адресе превращается в пробел — возвращаем его
            var text = since.Trim().Replace(' ', '+');
            // без смещения время неоднозначно (Москва или UTC — разница 3 часа), поэтому не принимается
            if (!MoscowTimeJsonConverter.HasOffset(text) || !DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture,
                    DateTimeStyles.None, out var parsed))
                return Problem(statusCode: 400, title: "Неверный формат since: нужно время ISO 8601 со смещением");
            from = LowerBound(parsed);
        }

        // Время сервера берётся до чтения и отдаётся как есть (по нему телефон сверяет свои часы):
        // запись, изменённая во время pull, придёт в следующий раз — за этим следит SinceMargin.
        var serverTime = DateTimeOffset.UtcNow;
        var me = User.EmployeeId();

        // заявки инженера и исходные заявки его повторных выездов (на них ссылается parent_id)
        var myRequests = db.Requests.Where(r => r.EngineerId == me);
        var visibleRequests = db.Requests.Where(r =>
            r.EngineerId == me || db.Requests.Any(c => c.EngineerId == me && c.ParentId == r.Id));
        var myIds = myRequests.Select(r => r.Id);

        var response = new PullResponse
        {
            ServerTime = serverTime,
            Customer = await Changed(db.Customers, from).OrderBy(x => x.Id).ToListAsync(),
            Contract = await Changed(db.Contracts, from).OrderBy(x => x.Id).ToListAsync(),
            SlaLevel = await Changed(db.SlaLevels, from).OrderBy(x => x.Id).ToListAsync(),
            Site = await Changed(db.Sites, from).OrderBy(x => x.Id).ToListAsync(),
            Equipment = await Changed(db.Equipment, from).OrderBy(x => x.Id).ToListAsync(),
            WorkType = await Changed(db.WorkTypes, from).OrderBy(x => x.Id).ToListAsync(),
            ChecklistItem = await Changed(db.ChecklistItems, from).OrderBy(x => x.Id).ToListAsync(),
            CalendarDay = await Changed(db.CalendarDays, from).OrderBy(x => x.Day).ToListAsync(),
            Request = await Changed(visibleRequests, from).OrderBy(x => x.Id).ToListAsync(),
            ChecklistResult = await Changed(db.ChecklistResults.Where(x => myIds.Contains(x.RequestId)), from).ToListAsync(),
            Photo = await Changed(db.Photos.Where(x => myIds.Contains(x.RequestId)), from).ToListAsync(),
            WorkLog = await Changed(db.WorkLogs.Where(x => myIds.Contains(x.RequestId)), from).ToListAsync(),
            Act = await Changed(db.Acts.Where(x => myIds.Contains(x.RequestId)), from).ToListAsync(),
            AssignedRequestIds = await myRequests
                .Where(r => r.Status != RequestStatus.Closed && r.Status != RequestStatus.Cancelled)
                .OrderBy(r => r.Id).Select(r => r.Id).ToListAsync(),
        };

        // Сотрудники: сам инженер и те, на кого ссылаются его заявки и их записи. Их немного, поэтому
        // они передаются всегда целиком — иначе новая заявка могла бы сослаться на диспетчера,
        // которого нет в телефоне. Хеша пароля нет, логин — только у самого инженера.
        var employeeIds = await visibleRequests.Select(r => r.DispatcherId)
            .Union(visibleRequests.Where(r => r.EngineerId != null).Select(r => r.EngineerId!.Value))
            .Union(db.WorkLogs.Where(w => myIds.Contains(w.RequestId)).Select(w => w.EmployeeId))
            .Union(db.Acts.Where(a => myIds.Contains(a.RequestId)).Select(a => a.EngineerId))
            .ToListAsync();
        employeeIds.Add(me);
        var employees = await db.Employees.Where(e => employeeIds.Contains(e.Id)).OrderBy(e => e.Id).ToListAsync();
        response.Employee = employees.Select(e => EmployeeDto.From(e, withLogin: e.Id == me)).ToList();

        return response;
    }

    /// <summary>Изменения с телефона. Отклонённые записи возвращаются с причиной, остальные принимаются.</summary>
    [HttpPost("push")]
    public async Task<ActionResult<PushResponse>> Push(PushRequest body) =>
        await pushService.ProcessAsync(User.EmployeeId(), body);

    /// <summary>Нижняя граница отбора: since минус запас (см. SinceMargin).</summary>
    public static DateTimeOffset LowerBound(DateTimeOffset since) =>
        since < DateTimeOffset.MinValue + SinceMargin ? DateTimeOffset.MinValue : since - SinceMargin;

    private static IQueryable<T> Changed<T>(IQueryable<T> query, DateTimeOffset? since) where T : class, IHasUpdatedAt =>
        since == null ? query : query.Where(x => x.UpdatedAt > since.Value);
}
