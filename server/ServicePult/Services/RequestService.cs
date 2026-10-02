using System.Globalization;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using ServicePult.Data;

namespace ServicePult.Services;

/// <summary>Данные формы «Новая заявка».</summary>
public record RegistrationInput(
    int ContractId, int SiteId, int? EquipmentId, int WorkTypeId, int SlaLevelId, string Description, int? EngineerId);

/// <summary>Результат регистрации: заявка или список причин отказа.</summary>
public record RegistrationResult(Request? Request, IReadOnlyList<string> Errors)
{
    public bool Ok => Errors.Count == 0;
}

/// <summary>
/// Действия диспетчера с заявками (функции 7 и 8 проекта): регистрация с расчётом сроков SLA,
/// назначение инженера и отмена. Каждое действие пишется в журнал событий с пометкой [Заявка].
/// </summary>
public class RequestService(AppDbContext db, ILogger<RequestService> log)
{
    public const int MaxDescription = 2000;
    public const int MaxCancelReason = 200;
    private const int NumberAttempts = 5;

    /// <summary>Номер уровня SLA из кода: «SLA-2» → 2.</summary>
    public static int LevelNumber(SlaLevel level) => int.Parse(level.Code.Trim()[4..], CultureInfo.InvariantCulture);

    /// <summary>Уровень допустим договором, если его номер не меньше max_sla (SLA-1 — самый высокий).</summary>
    public static bool LevelAllowed(SlaLevel level, Contract contract) => LevelNumber(level) >= contract.MaxSla;

    /// <summary>Договор действует на дату (включительно по обеим границам).</summary>
    public static bool ContractActive(Contract contract, DateOnly day) =>
        contract.DateFrom <= day && (contract.DateTo == null || contract.DateTo >= day);

    /// <summary>
    /// Регистрация заявки (проект, разделы 2, 3, 10): проверки целостности, сроки SLA по календарю,
    /// номер З-ГГММ-NNNN — следующий в месяце регистрации по Москве.
    /// </summary>
    public async Task<RegistrationResult> RegisterAsync(RegistrationInput input, int dispatcherId, DateTimeOffset now)
    {
        var errors = new List<string>();
        var today = CalendarLoader.MoscowToday(now);

        var contract = await db.Contracts.FindAsync(input.ContractId);
        var site = await db.Sites.FindAsync(input.SiteId);
        var equipment = input.EquipmentId == null ? null : await db.Equipment.FindAsync(input.EquipmentId.Value);
        var workType = await db.WorkTypes.FindAsync(input.WorkTypeId);
        var level = await db.SlaLevels.FindAsync(input.SlaLevelId);
        var engineer = input.EngineerId == null ? null : await db.Employees.FindAsync(input.EngineerId.Value);

        if (contract == null) errors.Add("Договор не найден");
        else if (!ContractActive(contract, today)) errors.Add($"Договор {contract.Number} не действует на {today:dd.MM.yyyy}");

        if (site == null) errors.Add("Объект не найден");
        else if (contract != null && site.CustomerId != contract.CustomerId)
            errors.Add("Объект принадлежит другому заказчику, не заказчику договора");

        if (input.EquipmentId != null && equipment == null) errors.Add("Оборудование не найдено");
        else if (equipment != null && site != null && equipment.SiteId != site.Id)
            errors.Add("Оборудование установлено на другом объекте");

        if (workType == null) errors.Add("Вид работ не найден");

        if (level == null) errors.Add("Уровень SLA не найден");
        else if (contract != null && !LevelAllowed(level, contract))
            errors.Add($"Уровень {level.Code.Trim()} выше разрешённого договором (не выше SLA-{contract.MaxSla})");

        var description = input.Description?.Trim() ?? "";
        if (description.Length == 0) errors.Add("Не заполнено описание неисправности");
        else if (description.Length > MaxDescription) errors.Add($"Описание длиннее {MaxDescription} знаков");

        if (input.EngineerId != null && (engineer == null || engineer.Role != 1 || engineer.Blocked))
            errors.Add("Выбранный сотрудник не является действующим инженером");

        if (errors.Count > 0) return new RegistrationResult(null, errors);

        // Сроки фиксируются при регистрации (раздел 2: «срок не ездит» при правке календаря).
        var sla = await CalendarLoader.SlaServiceAsync(db);
        var registeredAt = SlaService.ToMoscow(now.AddTicks(-(now.Ticks % TimeSpan.TicksPerSecond)));
        var request = new Request
        {
            ContractId = contract!.Id,
            SiteId = site!.Id,
            EquipmentId = equipment?.Id,
            WorkTypeId = workType!.Id,
            SlaLevelId = level!.Id,
            DispatcherId = dispatcherId,
            EngineerId = engineer?.Id,
            Description = description,
            RegisteredAt = registeredAt,
            ReactionDue = sla.Due(registeredAt, level.ReactionMin, level.Mode),
            ResolutionDue = sla.Due(registeredAt, level.ResolutionMin, level.Mode),
            Status = engineer == null ? RequestStatus.New : RequestStatus.Assigned,
            TimeTrusted = true, // время регистрации — часы сервера
        };
        db.Requests.Add(request);

        // Номер — следующий в месяце. Если два диспетчера сохраняют одновременно, второй получит
        // отказ уникального индекса номера и возьмёт следующий номер.
        var prefix = "З-" + registeredAt.ToString("yyMM", CultureInfo.InvariantCulture) + "-";
        for (var attempt = 1; ; attempt++)
        {
            request.Number = await NextNumberAsync(prefix);
            try
            {
                await db.SaveChangesAsync();
                break;
            }
            catch (DbUpdateException ex) when (attempt < NumberAttempts
                && ex.InnerException is PostgresException { SqlState: PostgresErrorCodes.UniqueViolation, ConstraintName: "IX_request_number" })
            {
                log.LogInformation("Номер {Number} уже занят, берётся следующий", request.Number);
            }
        }

        log.LogInformation("[Заявка] Зарегистрирована {Number} (id {Id}): договор {Contract}, объект {Site}, {Level}, " +
                           "инженер {Engineer}, сроки {ReactionDue:dd.MM.yyyy HH:mm} / {ResolutionDue:dd.MM.yyyy HH:mm}, диспетчер {Dispatcher}",
            request.Number, request.Id, contract.Number, site.Code, level.Code.Trim(), engineer?.Id, request.ReactionDue,
            request.ResolutionDue, dispatcherId);
        return new RegistrationResult(request, []);
    }

    private async Task<string> NextNumberAsync(string prefix)
    {
        var last = await db.Requests.Where(r => r.Number.StartsWith(prefix))
            .OrderByDescending(r => r.Number).Select(r => r.Number).FirstOrDefaultAsync();
        var next = last == null ? 1 : int.Parse(last.Trim()[prefix.Length..], CultureInfo.InvariantCulture) + 1;
        if (next > 9999) throw new InvalidOperationException($"Исчерпаны номера заявок за месяц {prefix}");
        return prefix + next.ToString("D4", CultureInfo.InvariantCulture);
    }

    /// <summary>Диспетчер меняет заявку только в статусах 1–2 (разделы 1 и 3 проекта).</summary>
    public static bool DispatcherCanChange(short status) => status is RequestStatus.New or RequestStatus.Assigned;

    /// <summary>Назначение или переназначение инженера; возвращает причину отказа или null.</summary>
    public async Task<string?> AssignAsync(int requestId, int engineerId, int dispatcherId)
    {
        var engineer = await db.Employees.FindAsync(engineerId);
        if (engineer == null || engineer.Role != 1 || engineer.Blocked)
            return "Выбранный сотрудник не является действующим инженером";

        return await ChangeAsync(requestId, dispatcherId, request =>
        {
            if (request.EngineerId == engineerId) return "Этот инженер уже назначен";
            var previous = request.EngineerId;
            request.EngineerId = engineerId;
            request.Status = RequestStatus.Assigned;
            log.LogInformation("[Заявка] {Number}: назначен инженер {Engineer} (был {Previous}), диспетчер {Dispatcher}",
                request.Number, engineerId, previous, dispatcherId);
            return null;
        });
    }

    /// <summary>Отмена заявки с причиной (статус 7); возвращает причину отказа или null.</summary>
    public async Task<string?> CancelAsync(int requestId, string? reason, int dispatcherId)
    {
        var text = reason?.Trim() ?? "";
        if (text.Length == 0) return "Укажите причину отмены";
        if (text.Length > MaxCancelReason) return $"Причина отмены длиннее {MaxCancelReason} знаков";

        return await ChangeAsync(requestId, dispatcherId, request =>
        {
            request.Status = RequestStatus.Cancelled;
            request.CancelReason = text;
            log.LogInformation("[Заявка] {Number}: отменена, причина «{Reason}», диспетчер {Dispatcher}",
                request.Number, text, dispatcherId);
            return null;
        });
    }

    /// <summary>
    /// Изменение заявки под блокировкой строки (SELECT … FOR UPDATE): пока диспетчер меняет заявку,
    /// её статус не может смениться параллельно. updated_at ставит AppDbContext.
    /// </summary>
    private async Task<string?> ChangeAsync(int requestId, int dispatcherId, Func<Request, string?> change)
    {
        await using var tx = db.Database.CurrentTransaction == null ? await db.Database.BeginTransactionAsync() : null;
        var request = (await db.Requests.FromSql($"SELECT * FROM request WHERE id = {requestId} FOR UPDATE").ToListAsync())
            .SingleOrDefault();
        if (request == null) return "Заявка не найдена";
        if (!DispatcherCanChange(request.Status))
        {
            log.LogWarning("[Заявка] {Number}: изменение диспетчером {Dispatcher} отклонено — статус «{Status}»",
                request.Number, dispatcherId, RequestStatusRules.Title(request.Status));
            return request.Status is RequestStatus.OnTheWay or RequestStatus.OnSite
                ? $"Заявка в статусе «{RequestStatusRules.Title(request.Status)}»: отменить или переназначить её можно " +
                  "только с подтверждением инженера — этап развития"
                : $"Заявка в статусе «{RequestStatusRules.Title(request.Status)}» не меняется";
        }

        var error = change(request);
        if (error != null) return error;
        await db.SaveChangesAsync();
        if (tx != null) await tx.CommitAsync();
        return null;
    }
}
