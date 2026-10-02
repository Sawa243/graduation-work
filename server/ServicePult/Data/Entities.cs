using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;
using System.Text.Json.Serialization;
using Microsoft.EntityFrameworkCore;

namespace ServicePult.Data;

// 14 таблиц информационной модели (проект системы, раздел 2.1).
// Имена таблиц и столбцов — как в проекте и в базе приложения (Room).
// Во всех таблицах есть служебный updated_at: его ставит AppDbContext при каждом сохранении.
// Ссылочные свойства (навигации) помечены [JsonIgnore]: в JSON уходят только столбцы.

/// <summary>Запись со служебным временем изменения.</summary>
public interface IHasUpdatedAt
{
    DateTimeOffset UpdatedAt { get; set; }
}

// ---------------------------------------------------------------- НСИ

[Table("customer"), Index(nameof(Code), IsUnique = true)]
public class Customer : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("code", TypeName = "char(4)")] public string Code { get; set; } = "";
    [Column("name"), MaxLength(200)] public string Name { get; set; } = "";
    [Column("inn"), MaxLength(12)] public string Inn { get; set; } = "";
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }
}

[Table("contract"), Index(nameof(Number), IsUnique = true)]
public class Contract : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("number", TypeName = "char(11)")] public string Number { get; set; } = "";
    [Column("customer_id")] public int CustomerId { get; set; }
    [Column("date_from")] public DateOnly DateFrom { get; set; }
    [Column("date_to")] public DateOnly? DateTo { get; set; }
    /// <summary>Самый высокий уровень SLA по договору: 1 — все уровни, 2 — SLA-2 и SLA-3, 3 — только SLA-3.</summary>
    [Column("max_sla")] public short MaxSla { get; set; }
    /// <summary>Есть соглашение о простой ЭП — акт можно подписывать на экране.</summary>
    [Column("e_sign_allowed")] public bool ESignAllowed { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(CustomerId))] public Customer? Customer { get; set; }
}

[Table("sla_level"), Index(nameof(Code), IsUnique = true)]
public class SlaLevel : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("code", TypeName = "char(5)")] public string Code { get; set; } = "";
    [Column("name"), MaxLength(40)] public string Name { get; set; } = "";
    [Column("reaction_min")] public int ReactionMin { get; set; }
    [Column("resolution_min")] public int ResolutionMin { get; set; }
    /// <summary>Режим отсчёта: 1 — круглосуточно (24×7), 2 — рабочее время.</summary>
    [Column("mode")] public short Mode { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }
}

[Table("site"), Index(nameof(Code), IsUnique = true)]
public class Site : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("code", TypeName = "char(7)")] public string Code { get; set; } = "";
    [Column("customer_id")] public int CustomerId { get; set; }
    [Column("address"), MaxLength(300)] public string Address { get; set; } = "";
    [Column("contact_name"), MaxLength(100)] public string? ContactName { get; set; }
    [Column("contact_phone"), MaxLength(20)] public string? ContactPhone { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(CustomerId))] public Customer? Customer { get; set; }
}

[Table("equipment"), Index(nameof(InvCode), IsUnique = true)]
public class Equipment : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("inv_code", TypeName = "char(12)")] public string InvCode { get; set; } = "";
    [Column("site_id")] public int SiteId { get; set; }
    [Column("vendor"), MaxLength(60)] public string Vendor { get; set; } = "";
    [Column("model"), MaxLength(100)] public string Model { get; set; } = "";
    [Column("serial_no"), MaxLength(60)] public string? SerialNo { get; set; }
    [Column("warranty_to")] public DateOnly? WarrantyTo { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(SiteId))] public Site? Site { get; set; }
}

[Table("work_type"), Index(nameof(Code), IsUnique = true)]
public class WorkType : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("code", TypeName = "char(3)")] public string Code { get; set; } = "";
    [Column("name"), MaxLength(120)] public string Name { get; set; } = "";
    [Column("norm_min")] public int NormMin { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }
}

[Table("checklist_item")]
public class ChecklistItem : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("work_type_id")] public int WorkTypeId { get; set; }
    [Column("pos")] public short Pos { get; set; }
    [Column("text"), MaxLength(200)] public string Text { get; set; } = "";
    /// <summary>Обязательный: без отметки нельзя перейти в «Работы выполнены».</summary>
    [Column("required")] public bool Required { get; set; }
    [Column("need_photo")] public bool NeedPhoto { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(WorkTypeId))] public WorkType? WorkType { get; set; }
}

[Table("employee"), Index(nameof(TabNo), IsUnique = true), Index(nameof(Login), IsUnique = true)]
public class Employee : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("tab_no", TypeName = "char(5)")] public string TabNo { get; set; } = "";
    [Column("full_name"), MaxLength(100)] public string FullName { get; set; } = "";
    [Column("position"), MaxLength(60)] public string Position { get; set; } = "";
    /// <summary>Роль доступа: 1 — инженер, 2 — диспетчер, 3 — руководитель службы.</summary>
    [Column("role")] public short Role { get; set; }
    [Column("phone"), MaxLength(20)] public string? Phone { get; set; }
    [Column("login"), MaxLength(40)] public string Login { get; set; } = "";
    /// <summary>
    /// Хеш пароля pbkdf2-sha256$итерации$соль$хеш. Наружу не отдаётся (см. EmployeeDto).
    /// В проекте указан varchar(100), но строка этого формата занимает 118 знаков — поэтому varchar(128).
    /// </summary>
    [Column("pass_hash"), MaxLength(128)] public string PassHash { get; set; } = "";
    [Column("blocked")] public bool Blocked { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }
}

/// <summary>День-исключение производственного календаря; связей с другими таблицами нет.</summary>
[Table("calendar_day")]
public class CalendarDay : IHasUpdatedAt
{
    [Key, Column("day")] public DateOnly Day { get; set; }
    /// <summary>false — праздник или перенесённый выходной; true — рабочая суббота.</summary>
    [Column("is_working")] public bool IsWorking { get; set; }
    [Column("note"), MaxLength(60)] public string? Note { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }
}

// ---------------------------------------------------------------- оперативные

/// <summary>Статусы заявки (проект, раздел 3).</summary>
public static class RequestStatus
{
    public const short New = 1;
    public const short Assigned = 2;
    public const short OnTheWay = 3;
    public const short OnSite = 4;
    public const short WorkDone = 5;
    public const short Closed = 6;
    public const short Cancelled = 7;
    public const short Suspended = 8;
}

[Table("request"), Index(nameof(Number), IsUnique = true)]
public class Request : IHasUpdatedAt
{
    [Column("id")] public int Id { get; set; }
    [Column("number", TypeName = "char(11)")] public string Number { get; set; } = "";
    [Column("contract_id")] public int ContractId { get; set; }
    [Column("site_id")] public int SiteId { get; set; }
    [Column("equipment_id")] public int? EquipmentId { get; set; }
    [Column("work_type_id")] public int WorkTypeId { get; set; }
    [Column("sla_level_id")] public int SlaLevelId { get; set; }
    [Column("dispatcher_id")] public int DispatcherId { get; set; }
    [Column("engineer_id")] public int? EngineerId { get; set; }
    [Column("parent_id")] public int? ParentId { get; set; }
    [Column("description")] public string Description { get; set; } = "";
    [Column("registered_at")] public DateTimeOffset RegisteredAt { get; set; }
    [Column("reaction_due")] public DateTimeOffset ReactionDue { get; set; }
    [Column("resolution_due")] public DateTimeOffset ResolutionDue { get; set; }
    [Column("arrived_at")] public DateTimeOffset? ArrivedAt { get; set; }
    [Column("completed_at")] public DateTimeOffset? CompletedAt { get; set; }
    [Column("closed_at")] public DateTimeOffset? ClosedAt { get; set; }
    [Column("status")] public short Status { get; set; }
    [Column("scanned_code"), MaxLength(100)] public string? ScannedCode { get; set; }
    [Column("result_text")] public string? ResultText { get; set; }
    [Column("cancel_reason"), MaxLength(200)] public string? CancelReason { get; set; }
    [Column("time_trusted")] public bool TimeTrusted { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(ContractId))] public Contract? Contract { get; set; }
    [JsonIgnore, ForeignKey(nameof(SiteId))] public Site? Site { get; set; }
    [JsonIgnore, ForeignKey(nameof(EquipmentId))] public Equipment? Equipment { get; set; }
    [JsonIgnore, ForeignKey(nameof(WorkTypeId))] public WorkType? WorkType { get; set; }
    [JsonIgnore, ForeignKey(nameof(SlaLevelId))] public SlaLevel? SlaLevel { get; set; }
    [JsonIgnore, ForeignKey(nameof(DispatcherId))] public Employee? Dispatcher { get; set; }
    [JsonIgnore, ForeignKey(nameof(EngineerId))] public Employee? Engineer { get; set; }
    [JsonIgnore, ForeignKey(nameof(ParentId))] public Request? Parent { get; set; }
}

// Записи ниже создаются на телефоне без связи, поэтому ключ — UUID, который генерирует устройство.

[Table("checklist_result"), Index(nameof(RequestId), nameof(ChecklistItemId), IsUnique = true)]
public class ChecklistResult : IHasUpdatedAt
{
    [Column("id")] public Guid Id { get; set; }
    [Column("request_id")] public int RequestId { get; set; }
    [Column("checklist_item_id")] public int ChecklistItemId { get; set; }
    [Column("done")] public bool Done { get; set; }
    [Column("comment"), MaxLength(300)] public string? Comment { get; set; }
    [Column("done_at")] public DateTimeOffset? DoneAt { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(RequestId))] public Request? Request { get; set; }
    [JsonIgnore, ForeignKey(nameof(ChecklistItemId))] public ChecklistItem? ChecklistItem { get; set; }
}

[Table("photo")]
public class Photo : IHasUpdatedAt
{
    [Column("id")] public Guid Id { get; set; }
    [Column("request_id")] public int RequestId { get; set; }
    [Column("checklist_item_id")] public int? ChecklistItemId { get; set; }
    /// <summary>1 — фото работ, 2 — скан бумажного акта с подписью.</summary>
    [Column("kind")] public short Kind { get; set; }
    [Column("file_name"), MaxLength(100)] public string FileName { get; set; } = "";
    [Column("taken_at")] public DateTimeOffset TakenAt { get; set; }
    [Column("sha256", TypeName = "char(64)")] public string Sha256 { get; set; } = "";
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(RequestId))] public Request? Request { get; set; }
    [JsonIgnore, ForeignKey(nameof(ChecklistItemId))] public ChecklistItem? ChecklistItem { get; set; }
}

[Table("work_log")]
public class WorkLog : IHasUpdatedAt
{
    [Column("id")] public Guid Id { get; set; }
    [Column("request_id")] public int RequestId { get; set; }
    [Column("employee_id")] public int EmployeeId { get; set; }
    /// <summary>1 — дорога, 2 — работа на объекте.</summary>
    [Column("kind")] public short Kind { get; set; }
    [Column("started_at")] public DateTimeOffset StartedAt { get; set; }
    [Column("ended_at")] public DateTimeOffset? EndedAt { get; set; }
    [Column("time_trusted")] public bool TimeTrusted { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(RequestId))] public Request? Request { get; set; }
    [JsonIgnore, ForeignKey(nameof(EmployeeId))] public Employee? Employee { get; set; }
}

// ---------------------------------------------------------------- результатная

[Table("act"), Index(nameof(RequestId), IsUnique = true), Index(nameof(Number), IsUnique = true)]
public class Act : IHasUpdatedAt
{
    [Column("id")] public Guid Id { get; set; }
    [Column("request_id")] public int RequestId { get; set; }
    [Column("number", TypeName = "char(11)")] public string Number { get; set; } = "";
    [Column("act_date")] public DateOnly ActDate { get; set; }
    [Column("engineer_id")] public int EngineerId { get; set; }
    /// <summary>1 — подписан на экране (простая ЭП), 2 — на бумаге (скан — photo.kind = 2).</summary>
    [Column("sign_mode")] public short SignMode { get; set; }
    [Column("signer_name"), MaxLength(100)] public string? SignerName { get; set; }
    [Column("signer_position"), MaxLength(100)] public string? SignerPosition { get; set; }
    [Column("signature_file"), MaxLength(100)] public string? SignatureFile { get; set; }
    [Column("pdf_file"), MaxLength(100)] public string PdfFile { get; set; } = "";
    [Column("signed_at")] public DateTimeOffset? SignedAt { get; set; }
    [Column("sha256", TypeName = "char(64)")] public string Sha256 { get; set; } = "";
    [Column("work_min")] public int WorkMin { get; set; }
    [Column("updated_at")] public DateTimeOffset UpdatedAt { get; set; }

    [JsonIgnore, ForeignKey(nameof(RequestId))] public Request? Request { get; set; }
    [JsonIgnore, ForeignKey(nameof(EngineerId))] public Employee? Engineer { get; set; }
}
