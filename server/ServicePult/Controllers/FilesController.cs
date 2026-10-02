using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Controllers;

/// <summary>
/// Загрузка файлов с телефона: фото, изображение подписи, акт PDF (проект, раздел 10).
/// Файлы отправляются до push: при приёме записи сервер сверяет SHA-256 файла с полем sha256.
/// </summary>
[ApiController]
[Route("api/files")]
[Authorize(Policy = Program.EngineerPolicy)]
public class FilesController(AppDbContext db, FileStorage storage, ILogger<FilesController> log) : ControllerBase
{
    [HttpPost]
    [RequestSizeLimit(FileStorage.MaxSize + 1024 * 1024)] // запас на заголовки multipart
    [RequestFormLimits(MultipartBodyLengthLimit = FileStorage.MaxSize + 1024 * 1024)]
    public async Task<ActionResult<FileResponse>> Upload([FromQuery] string? kind, [FromQuery] string? name, IFormFile? file)
    {
        if (kind == null || !FileStorage.Kinds.Contains(kind))
            return Problem(statusCode: 400, title: "Неизвестный вид файла: нужен photo, signature или act");
        if (!FileStorage.IsValidName(name))
            return Problem(statusCode: 400, title: "Недопустимое имя файла: только буквы, цифры, «.», «-», «_», до 100 знаков, не с точки и не на точку");
        if (file == null || file.Length == 0)
            return Problem(statusCode: 400, title: "Файл не передан (поле file в multipart/form-data)");
        if (file.Length > FileStorage.MaxSize)
            return Problem(statusCode: 413, title: "Файл больше 15 МБ");

        var engineerId = User.EmployeeId();
        await using var stream = file.OpenReadStream();
        var (temp, sha, size) = await storage.SaveTempAsync(kind, name!, stream);
        var target = storage.PathOf(kind, name!);
        try
        {
            // В Windows «CASE.JPG» и «case.jpg» — один файл: сравниваем с тем, что уже лежит под этим именем.
            var existing = storage.ExistingName(kind, name!);
            if (existing != null)
            {
                var oldSha = await storage.Sha256OfAsync(kind, existing);
                if (oldSha == sha)
                {
                    log.LogInformation("[Безопасность] Повторная загрузка файла {Kind}/{Name} инженером {Engineer}: содержимое совпало",
                        kind, name, engineerId);
                    return new FileResponse(name!, sha, size);
                }
                // Другое содержимое можно записать только под тем же именем (буква в букву) и только пока
                // ни одна принятая запись на файл не ссылается: так телефон заменяет подпись, переснятую
                // после отказа, но чужой или уже принятый файл (в том числе PDF закрытой заявки) не заменить.
                if (existing != name || await IsReferencedAsync(kind, existing))
                {
                    log.LogWarning("[Безопасность] Загрузка отклонена: файл {Kind}/{Name} уже есть на сервере с другим содержимым " +
                                   "(имя на диске {Existing}), инженер {Engineer}", kind, name, existing, engineerId);
                    return Problem(statusCode: 409,
                        title: "Файл с таким именем уже есть на сервере с другим содержимым и заменён быть не может");
                }
            }
            System.IO.File.Move(temp, target, overwrite: true);
            log.LogInformation("[Безопасность] Загружен файл {Kind}/{Name}, {Size} байт, SHA-256 {Sha}, инженер {Engineer}",
                kind, name, size, sha, engineerId);
            return new FileResponse(name!, sha, size);
        }
        finally
        {
            if (System.IO.File.Exists(temp)) System.IO.File.Delete(temp);
        }
    }

    /// <summary>
    /// Есть ли принятая запись (фото или акт), которая ссылается на этот файл.
    /// Без учёта регистра: в Windows «CASE.JPG» и «case.jpg» — один и тот же файл.
    /// </summary>
    private Task<bool> IsReferencedAsync(string kind, string name) => kind switch
    {
        // lower() с обеих сторон считает база — одинаково для латиницы и кириллицы
        FileStorage.Photo => db.Photos.AnyAsync(p => p.FileName.ToLower() == name.ToLower()),
        FileStorage.Act => db.Acts.AnyAsync(a => a.PdfFile.ToLower() == name.ToLower()),
        _ => db.Acts.AnyAsync(a => a.SignatureFile != null && a.SignatureFile.ToLower() == name.ToLower()),
    };
}
