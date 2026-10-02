using System.ComponentModel.DataAnnotations;
using ServicePult.Data;

namespace ServicePult.Controllers;

// Формы запросов и ответов API (проект, раздел 10). Имена полей в JSON — snake_case,
// их делает JsonNamingPolicy.SnakeCaseLower из имён свойств: AccessToken → access_token.

// ---------------------------------------------------------------- вход

public record LoginRequest([Required] string Login, [Required] string Password);

public record RefreshRequest([Required] string RefreshToken);

/// <summary>Сотрудник без хеша пароля. Логин есть только у самого инженера.</summary>
public record EmployeeDto(
    int Id, string TabNo, string FullName, string Position, short Role, string? Phone,
    string? Login, bool Blocked, DateTimeOffset UpdatedAt)
{
    public static EmployeeDto From(Employee e, bool withLogin) =>
        new(e.Id, e.TabNo, e.FullName, e.Position, e.Role, e.Phone, withLogin ? e.Login : null, e.Blocked, e.UpdatedAt);
}

public record TokenResponse(
    string AccessToken, DateTimeOffset AccessExpiresAt,
    string RefreshToken, DateTimeOffset RefreshExpiresAt,
    EmployeeDto Employee, DateTimeOffset ServerTime);

// ---------------------------------------------------------------- pull

public class PullResponse
{
    public DateTimeOffset ServerTime { get; set; }
    public List<Customer> Customer { get; set; } = [];
    public List<Contract> Contract { get; set; } = [];
    public List<SlaLevel> SlaLevel { get; set; } = [];
    public List<Site> Site { get; set; } = [];
    public List<Equipment> Equipment { get; set; } = [];
    public List<WorkType> WorkType { get; set; } = [];
    public List<ChecklistItem> ChecklistItem { get; set; } = [];
    public List<EmployeeDto> Employee { get; set; } = [];
    public List<CalendarDay> CalendarDay { get; set; } = [];
    public List<Request> Request { get; set; } = [];
    public List<ChecklistResult> ChecklistResult { get; set; } = [];
    public List<Photo> Photo { get; set; } = [];
    public List<WorkLog> WorkLog { get; set; } = [];
    public List<Act> Act { get; set; } = [];
    /// <summary>Все незакрытые заявки инженера: остальные приложение у себя удаляет.</summary>
    public List<int> AssignedRequestIds { get; set; } = [];
}

// ---------------------------------------------------------------- push
// Записи push — отдельные классы без ограничений длины ([MaxLength] сущностей): иначе одна
// слишком длинная строка давала бы ответ 400 на весь пакет, и телефон не мог бы отправить
// остальное. Длины и обязательность полей проверяет PushService по каждой записи отдельно.
// Строки — string?: пустое значение (null) тоже отклоняет только свою запись.

/// <summary>Из заявки приложение передаёт только поля, которые меняет инженер.</summary>
public class RequestPush
{
    public int Id { get; set; }
    public short Status { get; set; }
    public DateTimeOffset? ArrivedAt { get; set; }
    public DateTimeOffset? CompletedAt { get; set; }
    public DateTimeOffset? ClosedAt { get; set; }
    public string? ScannedCode { get; set; }
    public string? ResultText { get; set; }
    public int? EquipmentId { get; set; }
    public bool TimeTrusted { get; set; }
}

public class ChecklistResultPush
{
    public Guid Id { get; set; }
    public int RequestId { get; set; }
    public int ChecklistItemId { get; set; }
    public bool Done { get; set; }
    public string? Comment { get; set; }
    public DateTimeOffset? DoneAt { get; set; }
}

/// <summary>Фото; deleted = true — удалено на телефоне (sync_state = 2).</summary>
public class PhotoPush
{
    public Guid Id { get; set; }
    public int RequestId { get; set; }
    public int? ChecklistItemId { get; set; }
    public short Kind { get; set; }
    public string? FileName { get; set; }
    public DateTimeOffset TakenAt { get; set; }
    public string? Sha256 { get; set; }
    public bool Deleted { get; set; }
}

public class WorkLogPush
{
    public Guid Id { get; set; }
    public int RequestId { get; set; }
    public int EmployeeId { get; set; }
    public short Kind { get; set; }
    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset? EndedAt { get; set; }
    public bool TimeTrusted { get; set; }
}

public class ActPush
{
    public Guid Id { get; set; }
    public int RequestId { get; set; }
    public string? Number { get; set; }
    public DateOnly ActDate { get; set; }
    public int EngineerId { get; set; }
    public short SignMode { get; set; }
    public string? SignerName { get; set; }
    public string? SignerPosition { get; set; }
    public string? SignatureFile { get; set; }
    public string? PdfFile { get; set; }
    public DateTimeOffset? SignedAt { get; set; }
    public string? Sha256 { get; set; }
    public int WorkMin { get; set; }
}

public class PushRequest
{
    public List<RequestPush> Request { get; set; } = [];
    public List<ChecklistResultPush> ChecklistResult { get; set; } = [];
    public List<PhotoPush> Photo { get; set; } = [];
    public List<WorkLogPush> WorkLog { get; set; } = [];
    public List<ActPush> Act { get; set; } = [];
}

public class AcceptedIds
{
    public List<int> Request { get; set; } = [];
    public List<Guid> ChecklistResult { get; set; } = [];
    public List<Guid> Photo { get; set; } = [];
    public List<Guid> WorkLog { get; set; } = [];
    public List<Guid> Act { get; set; } = [];
}

/// <summary>Отклонённая запись: таблица, id (строкой — у заявки число, у остальных UUID) и причина.</summary>
public record RejectedRecord(string Table, string Id, string Reason);

public class PushResponse
{
    public DateTimeOffset ServerTime { get; set; }
    public AcceptedIds Accepted { get; set; } = new();
    public List<RejectedRecord> Rejected { get; set; } = [];
}

// ---------------------------------------------------------------- файлы

public record FileResponse(string Name, string Sha256, long Size);
