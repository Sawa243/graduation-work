using Microsoft.EntityFrameworkCore;
using ServicePult.Controllers;
using ServicePult.Data;

namespace ServicePult.Services;

/// <summary>
/// Приём изменений с телефона — push (проект, раздел 10). Вся отправка — одна транзакция,
/// но каждая запись проверяется и сохраняется отдельно: отклонённая запись не мешает остальным.
///
/// Порядок обработки:
/// 1) отметки чек-листа, фото, интервалы — от них зависят проверки перехода в «Работы выполнены»;
/// 2) заявки — статус только вперёд; переход в 6 «Закрыта» откладывается до шага 4;
/// 3) акты — принимаются, когда заявка уже в статусе 5;
/// 4) закрытие заявок (5 → 6) — только при готовом акте.
///
/// Повтор той же отправки ничего не ломает: записи с UUID вставляются или обновляются по id,
/// а запись, которая не отличается от сохранённой, принимается без изменений даже у закрытой заявки.
/// </summary>
public class PushService(AppDbContext db, FileStorage files, ILogger<PushService> log)
{
    private int _engineerId;
    private PushResponse _result = new();
    private readonly List<(string Kind, string Name)> _filesToDelete = [];

    public async Task<PushResponse> ProcessAsync(int engineerId, PushRequest push)
    {
        _engineerId = engineerId;
        _result = new PushResponse();

        await using (var tx = await db.Database.BeginTransactionAsync())
        {
            await LockRequests(push);

            foreach (var x in push.ChecklistResult)
                await TryApply("checklist_result", x.Id.ToString(), () => ApplyChecklistResult(x),
                    () => _result.Accepted.ChecklistResult.Add(x.Id));
            foreach (var x in push.Photo)
                await TryApply("photo", x.Id.ToString(), () => ApplyPhoto(x), () => _result.Accepted.Photo.Add(x.Id));
            foreach (var x in push.WorkLog)
                await TryApply("work_log", x.Id.ToString(), () => ApplyWorkLog(x), () => _result.Accepted.WorkLog.Add(x.Id));

            var toClose = new List<RequestPush>();
            foreach (var x in push.Request)
            {
                // Заявка, которую приложение закрыло, будет принята только после проверки акта (шаг 4).
                // В очередь закрытия она попадает только после успешного сохранения: если база отвергла
                // запись, заявка отклонена и закрываться не должна.
                var closeLater = false;
                await TryApply("request", x.Id.ToString(), () => ApplyRequest(x, () => closeLater = true),
                    () => { if (closeLater) toClose.Add(x); else _result.Accepted.Request.Add(x.Id); });
            }

            foreach (var x in push.Act)
                await TryApply("act", x.Id.ToString(), () => ApplyAct(x), () => _result.Accepted.Act.Add(x.Id));

            foreach (var x in toClose)
                await TryApply("request", x.Id.ToString(), () => CloseRequest(x), () => _result.Accepted.Request.Add(x.Id));

            await tx.CommitAsync();
        }

        // файлы удалённых фото убираются только после успешной записи в базу
        foreach (var (kind, name) in _filesToDelete)
            files.Delete(kind, name);

        _result.ServerTime = DateTimeOffset.UtcNow;
        return _result;
    }

    /// <summary>
    /// Блокирует строки всех заявок, которых касается отправка (SELECT … FOR UPDATE, как при
    /// отмене и назначении диспетчером), до конца транзакции: диспетчер не отменит заявку
    /// в ту же секунду, когда телефон переводит её в «В пути». Порядок по id — без взаимных
    /// блокировок двух транзакций.
    /// </summary>
    private async Task LockRequests(PushRequest push)
    {
        var ids = push.Request.Select(x => x.Id)
            .Concat(push.ChecklistResult.Select(x => x.RequestId))
            .Concat(push.Photo.Select(x => x.RequestId))
            .Concat(push.WorkLog.Select(x => x.RequestId))
            .Concat(push.Act.Select(x => x.RequestId))
            .Distinct().ToArray();
        if (ids.Length == 0) return;
        await db.Database.SqlQuery<int>(
            $"SELECT id AS \"Value\" FROM request WHERE id = ANY({ids}) ORDER BY id FOR UPDATE").ToListAsync();
    }

    /// <summary>
    /// Проверяет и сохраняет одну запись. apply возвращает причину отказа или null.
    /// EF Core внутри транзакции сам ставит точку сохранения перед SaveChanges,
    /// поэтому ошибка базы на одной записи не портит уже принятые.
    /// </summary>
    private async Task TryApply(string table, string id, Func<Task<string?>> apply, Action onAccepted)
    {
        string? reason;
        try
        {
            reason = await apply();
            if (reason == null) await db.SaveChangesAsync();
        }
        catch (DbUpdateException ex)
        {
            reason = "Запись не сохранена: " + (ex.InnerException?.Message ?? ex.Message);
        }

        if (reason == null)
        {
            onAccepted();
            return;
        }
        db.ChangeTracker.Clear(); // отбрасываем несохранённые изменения этой записи
        _result.Rejected.Add(new RejectedRecord(table, id, reason));
        log.LogWarning("[Безопасность] Push: отклонена запись {Table} {Id} инженера {Engineer}: {Reason}",
            table, id, _engineerId, reason);
    }

    // ---------------------------------------------------------------- общие проверки

    /// <summary>
    /// Допуск на спешащие часы телефона: отметка времени может быть позже «сейчас» сервера не больше
    /// чем на 10 минут. Без доверенного времени (time_trusted = false) телефон берёт время из своих часов,
    /// а они могут немного спешить; отметка из далёкого будущего — ошибка часов или подделка.
    /// </summary>
    public static readonly TimeSpan ClockTolerance = TimeSpan.FromMinutes(10);

    /// <summary>Длины строк — как у столбцов таблиц (проект, раздел 2.1); длиннее — отказ только этой записи.</summary>
    private static string? TooLong(string? value, int max, string field) =>
        value != null && value.Length > max ? $"{field} длиннее {max} знаков" : null;

    private static string? InFuture(DateTimeOffset? value, string what) =>
        value > DateTimeOffset.UtcNow + ClockTolerance ? $"Время {what} позже текущего времени сервера" : null;

    /// <summary>Заявка существует и назначена этому инженеру.</summary>
    private async Task<(Request? Request, string? Reason)> MyRequest(int requestId)
    {
        var r = await db.Requests.FindAsync(requestId);
        if (r == null) return (null, $"Заявка {requestId} не найдена");
        if (r.EngineerId != _engineerId) return (null, "Заявка не назначена этому инженеру");
        return (r, null);
    }

    /// <summary>Заявка этого инженера, которую ещё можно менять (не закрыта и не отменена).</summary>
    private async Task<(Request? Request, string? Reason)> MyOpenRequest(int requestId)
    {
        var (r, reason) = await MyRequest(requestId);
        if (reason != null) return (null, reason);
        return (r, LockReason(r!));
    }

    private static string? LockReason(Request r) => r.Status switch
    {
        RequestStatus.Closed => "Заявка закрыта (акт подписан) — изменения не принимаются",
        RequestStatus.Cancelled => "Заявка отменена — изменения не принимаются",
        _ => null,
    };

    private async Task<string?> CheckItemOfRequest(int? checklistItemId, Request r)
    {
        if (checklistItemId == null) return null;
        var item = await db.ChecklistItems.FindAsync(checklistItemId.Value);
        return item == null || item.WorkTypeId != r.WorkTypeId
            ? "Пункт чек-листа не относится к виду работ заявки"
            : null;
    }

    /// <summary>Файл загружен, и его SHA-256 совпадает с указанной в записи.</summary>
    private async Task<string?> CheckFile(string kind, string? name, string? sha256, string what)
    {
        if (!FileStorage.IsValidName(name)) return $"Недопустимое имя файла {what}";
        var reason = CheckExactName(kind, name!, what);
        if (reason != null) return reason;
        var actual = await files.Sha256OfAsync(kind, name!);
        if (actual == null) return $"Файл {what} не загружен на сервер";
        if (!string.Equals(actual, (sha256 ?? "").Trim(), StringComparison.OrdinalIgnoreCase))
            return $"Контрольная сумма файла {what} не совпадает с записью";
        return null;
    }

    /// <summary>
    /// Файл лежит на диске под тем же именем буква в букву. В Windows регистр в именах не различается,
    /// и без этой проверки запись «CASE.JPG» сослалась бы на чужой файл «case.jpg».
    /// </summary>
    private string? CheckExactName(string kind, string name, string what)
    {
        var existing = files.ExistingName(kind, name);
        if (existing == null) return $"Файл {what} не загружен на сервер";
        return existing == name ? null : $"Имя файла {what} отличается от загруженного регистром букв";
    }

    // ---------------------------------------------------------------- отметки чек-листа

    private async Task<string?> ApplyChecklistResult(ChecklistResultPush x)
    {
        if (x.Id == Guid.Empty) return "Не указан id записи";
        var tooLong = TooLong(x.Comment, 300, "Замечание (comment)");
        if (tooLong != null) return tooLong;
        var old = await db.ChecklistResults.FindAsync(x.Id);
        if (old != null && old.RequestId != x.RequestId) return "Запись относится к другой заявке";
        if (old != null && old.ChecklistItemId == x.ChecklistItemId && old.Done == x.Done
            && old.Comment == x.Comment && old.DoneAt == x.DoneAt)
        {
            var (_, notMine) = await MyRequest(x.RequestId);
            return notMine; // повтор без изменений
        }

        var (r, reason) = await MyOpenRequest(x.RequestId);
        if (reason != null) return reason;
        reason = await CheckItemOfRequest(x.ChecklistItemId, r!);
        if (reason != null) return reason;
        if (await db.ChecklistResults.AnyAsync(c =>
                c.RequestId == x.RequestId && c.ChecklistItemId == x.ChecklistItemId && c.Id != x.Id))
            return "По этому пункту чек-листа уже есть другая отметка";

        var row = old ?? db.ChecklistResults.Add(new ChecklistResult { Id = x.Id, RequestId = x.RequestId }).Entity;
        row.ChecklistItemId = x.ChecklistItemId;
        row.Done = x.Done;
        row.Comment = x.Comment;
        row.DoneAt = x.DoneAt;
        return null;
    }

    // ---------------------------------------------------------------- фото

    private async Task<string?> ApplyPhoto(PhotoPush x)
    {
        if (x.Id == Guid.Empty) return "Не указан id записи";
        var old = await db.Photos.FindAsync(x.Id);

        if (x.Deleted)
        {
            if (old == null) return null; // уже удалено
            var (req, notAllowed) = await MyOpenRequest(old.RequestId);
            if (notAllowed != null) return notAllowed;
            db.Photos.Remove(old);
            // файл удаляется, только если на него не ссылается другое фото
            if (!await db.Photos.AnyAsync(p => p.FileName == old.FileName && p.Id != old.Id))
                _filesToDelete.Add((FileStorage.Photo, old.FileName));
            return null;
        }

        if (old != null && old.RequestId != x.RequestId) return "Запись относится к другой заявке";
        if (old != null && old.ChecklistItemId == x.ChecklistItemId && old.Kind == x.Kind && old.FileName == x.FileName
            && old.TakenAt == x.TakenAt && string.Equals(old.Sha256, x.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            var (_, notMine) = await MyRequest(x.RequestId);
            return notMine;
        }

        var (r, reason) = await MyOpenRequest(x.RequestId);
        if (reason != null) return reason;
        if (x.Kind is not (1 or 2)) return "Вид фото должен быть 1 (работы) или 2 (скан акта)";
        reason = await CheckItemOfRequest(x.ChecklistItemId, r!)
                 ?? await CheckFile(FileStorage.Photo, x.FileName, x.Sha256, "фото");
        if (reason != null) return reason;

        var row = old ?? db.Photos.Add(new Photo { Id = x.Id, RequestId = x.RequestId }).Entity;
        row.ChecklistItemId = x.ChecklistItemId;
        row.Kind = x.Kind;
        row.FileName = x.FileName!;
        row.TakenAt = x.TakenAt;
        row.Sha256 = x.Sha256!.Trim().ToLowerInvariant();
        return null;
    }

    // ---------------------------------------------------------------- интервалы работы

    private async Task<string?> ApplyWorkLog(WorkLogPush x)
    {
        if (x.Id == Guid.Empty) return "Не указан id записи";
        var old = await db.WorkLogs.FindAsync(x.Id);
        if (old != null && old.RequestId != x.RequestId) return "Запись относится к другой заявке";
        if (old != null && old.EmployeeId == x.EmployeeId && old.Kind == x.Kind && old.StartedAt == x.StartedAt
            && old.EndedAt == x.EndedAt && old.TimeTrusted == x.TimeTrusted)
        {
            var (_, notMine) = await MyRequest(x.RequestId);
            return notMine;
        }

        var (_, reason) = await MyOpenRequest(x.RequestId);
        if (reason != null) return reason;
        if (x.EmployeeId != _engineerId) return "Интервал работы можно записать только на себя";
        if (x.Kind is not (1 or 2)) return "Вид интервала должен быть 1 (дорога) или 2 (работа)";
        if (x.EndedAt != null && x.EndedAt < x.StartedAt) return "Конец интервала раньше начала";
        var future = InFuture(x.StartedAt, "начала интервала") ?? InFuture(x.EndedAt, "конца интервала");
        if (future != null) return future;

        var row = old ?? db.WorkLogs.Add(new WorkLog { Id = x.Id, RequestId = x.RequestId }).Entity;
        row.EmployeeId = x.EmployeeId;
        row.Kind = x.Kind;
        row.StartedAt = x.StartedAt;
        row.EndedAt = x.EndedAt;
        row.TimeTrusted = x.TimeTrusted;
        return null;
    }

    // ---------------------------------------------------------------- заявки

    private async Task<string?> ApplyRequest(RequestPush x, Action closeLater)
    {
        var (r, reason) = await MyRequest(x.Id);
        if (reason != null) return reason;
        if (SameAsStored(r!, x)) return null; // повтор без изменений
        reason = LockReason(r!)
                 ?? TooLong(x.ScannedCode, 100, "Отсканированный код (scanned_code)");
        if (reason != null) return reason;

        // После «Работы выполнены» и «Приостановлена» отметки времени и «Что сделано» уже проверены
        // и попадут в акт — изменить их нельзя. Повтор тех же значений проходит (идемпотентность).
        if (r!.Status is RequestStatus.WorkDone or RequestStatus.Suspended
            && (x.ArrivedAt != r.ArrivedAt || x.CompletedAt != r.CompletedAt || x.ResultText != r.ResultText))
            return $"Заявка в статусе «{RequestStatusRules.Title(r.Status)}»: время прибытия, время выполнения " +
                   "и «Что сделано» уже не меняются";

        // «Закрыта» — отдельным шагом после приёма акта; сейчас заявка доводится до «Работы выполнены»
        var target = x.Status == RequestStatus.Closed ? RequestStatus.WorkDone : x.Status;
        reason = RequestStatusRules.CheckTransition(r.Status, target);
        if (reason != null) return reason;

        if (x.EquipmentId != null && x.EquipmentId != r.EquipmentId)
        {
            var eq = await db.Equipment.FindAsync(x.EquipmentId.Value);
            if (eq == null || eq.SiteId != r.SiteId) return "Оборудование не относится к объекту заявки";
        }

        // проверки перехода — по новым значениям полей
        var resultText = x.ResultText;
        if (target >= RequestStatus.OnSite && x.ArrivedAt == null)
            return "Нет отметки прибытия на объект (arrived_at)";
        if (target == RequestStatus.WorkDone)
        {
            if (x.CompletedAt == null) return "Нет отметки выполнения работ (completed_at)";
            if (r.Status < RequestStatus.WorkDone)
            {
                var problems = await CheckWorkDone(r, resultText);
                if (problems.Count > 0) return string.Join("; ", problems);
            }
        }
        if (target == RequestStatus.Suspended && string.IsNullOrWhiteSpace(resultText))
            return "Для приостановки заполните «Что сделано»: что выполнено и чего ждём";
        reason = CheckTimes(r, x.ArrivedAt, x.CompletedAt);
        if (reason != null) return reason;

        // остальные поля заявки — «побеждает сервер», их приложение не меняет
        r.Status = target;
        r.ArrivedAt = x.ArrivedAt;
        r.CompletedAt = x.CompletedAt;
        r.ScannedCode = x.ScannedCode;
        r.ResultText = resultText;
        r.EquipmentId = x.EquipmentId;
        r.TimeTrusted = x.TimeTrusted;
        if (x.Status == RequestStatus.Closed) closeLater();
        return null;
    }

    private static bool SameAsStored(Request r, RequestPush x) =>
        r.Status == x.Status && r.ArrivedAt == x.ArrivedAt && r.CompletedAt == x.CompletedAt
        && r.ClosedAt == x.ClosedAt && r.ScannedCode == x.ScannedCode && r.ResultText == x.ResultText
        && r.EquipmentId == x.EquipmentId && r.TimeTrusted == x.TimeTrusted;

    /// <summary>Порядок отметок: регистрация ≤ прибытие ≤ выполнение, и ни одна не позже «сейчас» (с допуском).</summary>
    private static string? CheckTimes(Request r, DateTimeOffset? arrivedAt, DateTimeOffset? completedAt)
    {
        if (arrivedAt < r.RegisteredAt) return "Время прибытия раньше регистрации заявки";
        if (completedAt < r.RegisteredAt) return "Время выполнения работ раньше регистрации заявки";
        if (completedAt < arrivedAt) return "Время выполнения работ раньше прибытия на объект";
        return InFuture(arrivedAt, "прибытия") ?? InFuture(completedAt, "выполнения работ");
    }

    /// <summary>Правила перехода в «Работы выполнены» (проект, раздел 3).</summary>
    private async Task<List<string>> CheckWorkDone(Request r, string? resultText)
    {
        var items = await db.ChecklistItems.Where(i => i.WorkTypeId == r.WorkTypeId).OrderBy(i => i.Pos).ToListAsync();
        var done = await db.ChecklistResults.Where(c => c.RequestId == r.Id && c.Done)
            .Select(c => c.ChecklistItemId).ToListAsync();
        var withPhoto = await db.Photos.Where(p => p.RequestId == r.Id && p.ChecklistItemId != null)
            .Select(p => p.ChecklistItemId!.Value).ToListAsync();

        var problems = new List<string>();
        foreach (var i in items)
        {
            if (i.Required && !done.Contains(i.Id)) problems.Add($"Не отмечен обязательный пункт: {i.Text}");
            else if (i.NeedPhoto && done.Contains(i.Id) && !withPhoto.Contains(i.Id)) problems.Add($"Нет фото к пункту: {i.Text}");
        }
        if (string.IsNullOrWhiteSpace(resultText)) problems.Add("Не заполнено «Что сделано»");
        return problems;
    }

    /// <summary>5 → 6: только когда акт готов (проект, раздел 3).</summary>
    private async Task<string?> CloseRequest(RequestPush x)
    {
        var (r, reason) = await MyRequest(x.Id);
        if (reason != null) return reason;
        if (r!.Status != RequestStatus.WorkDone) return "Закрыть можно только заявку в статусе «Работы выполнены»";
        if (x.ClosedAt == null) return "Нет времени закрытия (closed_at)";
        if (x.ClosedAt < r.CompletedAt) return "Время закрытия раньше выполнения работ";
        reason = InFuture(x.ClosedAt, "закрытия");
        if (reason != null) return reason;

        var act = await db.Acts.FirstOrDefaultAsync(a => a.RequestId == r.Id);
        if (act == null) return "Заявку нельзя закрыть без акта";
        if (act.SignMode == 2 && !await db.Photos.AnyAsync(p => p.RequestId == r.Id && p.Kind == 2))
            return "Нет фото подписанного бумажного акта";

        r.Status = RequestStatus.Closed;
        r.ClosedAt = x.ClosedAt;
        return null;
    }

    // ---------------------------------------------------------------- акты

    private async Task<string?> ApplyAct(ActPush x)
    {
        if (x.Id == Guid.Empty) return "Не указан id записи";
        if (string.IsNullOrWhiteSpace(x.Number)) return "Не указан номер акта";
        var tooLong = TooLong(x.Number, 11, "Номер акта (number)")
                      ?? TooLong(x.SignerName, 100, "ФИО подписавшего (signer_name)")
                      ?? TooLong(x.SignerPosition, 100, "Должность подписавшего (signer_position)");
        if (tooLong != null) return tooLong;
        var old = await db.Acts.FindAsync(x.Id);
        if (old != null && old.RequestId != x.RequestId) return "Акт относится к другой заявке";
        if (old != null && SameAsStored(old, x))
        {
            var (_, notMine) = await MyRequest(x.RequestId);
            return notMine;
        }

        var (r, reason) = await MyOpenRequest(x.RequestId);
        if (reason != null) return reason;
        if (r!.Status != RequestStatus.WorkDone) return "Акт принимается только по заявке в статусе «Работы выполнены»";
        if (x.EngineerId != _engineerId) return "Акт может подписать только инженер, назначенный на заявку";
        if (await db.Acts.AnyAsync(a => a.RequestId == x.RequestId && a.Id != x.Id)) return "По заявке уже есть другой акт";
        if (await db.Acts.AnyAsync(a => a.Number == x.Number && a.Id != x.Id)) return "Акт с таким номером уже есть";
        if (x.WorkMin < 0) return "Трудозатраты не могут быть отрицательными";

        if (x.SignMode == 1)
        {
            var contract = await db.Contracts.FindAsync(r.ContractId);
            if (contract is not { ESignAllowed: true })
                return "По договору нет соглашения о простой ЭП — нужен бумажный акт";
            if (string.IsNullOrWhiteSpace(x.SignerName)) return "Не указано ФИО подписавшего представителя заказчика";
            if (!FileStorage.IsValidName(x.SignatureFile)) return "Недопустимое имя файла подписи";
            reason = CheckExactName(FileStorage.Signature, x.SignatureFile!, "подписи заказчика");
            if (reason != null) return reason;
        }
        else if (x.SignMode != 2)
            return "Способ подписания должен быть 1 (экран) или 2 (бумага)";

        reason = await CheckFile(FileStorage.Act, x.PdfFile, x.Sha256, "акта");
        if (reason != null) return reason;

        var row = old ?? db.Acts.Add(new Act { Id = x.Id, RequestId = x.RequestId }).Entity;
        row.Number = x.Number!;
        row.ActDate = x.ActDate;
        row.EngineerId = x.EngineerId;
        row.SignMode = x.SignMode;
        row.SignerName = x.SignerName;
        row.SignerPosition = x.SignerPosition;
        row.SignatureFile = x.SignatureFile;
        row.PdfFile = x.PdfFile!;
        row.SignedAt = x.SignedAt;
        row.Sha256 = x.Sha256!.Trim().ToLowerInvariant();
        row.WorkMin = x.WorkMin;
        return null;
    }

    private static bool SameAsStored(Act a, ActPush x) =>
        a.Number == x.Number && a.ActDate == x.ActDate && a.EngineerId == x.EngineerId && a.SignMode == x.SignMode
        && a.SignerName == x.SignerName && a.SignerPosition == x.SignerPosition && a.SignatureFile == x.SignatureFile
        && a.PdfFile == x.PdfFile && a.SignedAt == x.SignedAt && a.WorkMin == x.WorkMin
        && string.Equals(a.Sha256, x.Sha256, StringComparison.OrdinalIgnoreCase);
}
