using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace ServicePult.Services;

/// <summary>
/// Файлы с телефонов (фото, подписи, акты PDF) в папке Files:Root/&lt;вид&gt;/&lt;имя&gt;.
/// Имя — только латиница, цифры, точка, дефис и подчёркивание, без путей.
/// </summary>
public class FileStorage
{
    public const string Photo = "photo";
    public const string Signature = "signature";
    public const string Act = "act";
    public static readonly string[] Kinds = [Photo, Signature, Act];

    /// <summary>Наибольший размер файла — 15 МБ.</summary>
    public const long MaxSize = 15 * 1024 * 1024;

    public string Root { get; }

    public FileStorage(IConfiguration config, IWebHostEnvironment env)
    {
        var root = config["Files:Root"];
        if (string.IsNullOrWhiteSpace(root)) root = Path.Combine("App_Data", "files");
        Root = Path.GetFullPath(Path.Combine(env.ContentRootPath, root));
    }

    // Буквы любого алфавита (имя акта «А-2609-0143.pdf» начинается с русской «А»), цифры, «.», «-», «_».
    // Разделителей пути, пробелов и управляющих символов нет. \z — конец строки без допуска перевода строки.
    private static readonly Regex NamePattern = new(@"^[\p{L}\p{Nd}._-]{1,100}\z");

    /// <summary>
    /// Имя допустимо: разрешённые символы, не начинается с точки (так отсекаются «.», «..» и скрытые файлы)
    /// и не кончается точкой — Windows отбрасывает точку в конце, и «a.jpg.» открыл бы файл «a.jpg».
    /// Пробелов шаблон не допускает вовсе, в том числе в конце имени.
    /// </summary>
    public static bool IsValidName(string? name) =>
        name != null && NamePattern.IsMatch(name) && !name.StartsWith('.') && !name.EndsWith('.');

    public string PathOf(string kind, string name) => Path.Combine(Root, kind, name);

    /// <summary>
    /// Имя, под которым файл уже лежит на диске, или null, если файла нет. В Windows регистр в именах
    /// не различается: для «CASE.JPG» вернётся «case.jpg», если так записан существующий файл.
    /// </summary>
    public string? ExistingName(string kind, string name)
    {
        var dir = Path.Combine(Root, kind);
        if (!IsValidName(name) || !Directory.Exists(dir)) return null;
        var found = Directory.GetFiles(dir, name); // в имени нет «*» и «?» — это поиск ровно одного файла
        return found.Length == 0 ? null : Path.GetFileName(found[0]);
    }

    /// <summary>SHA-256 файла строчными hex-цифрами или null, если файла нет.</summary>
    public async Task<string?> Sha256OfAsync(string kind, string name)
    {
        if (!IsValidName(name)) return null;
        var path = PathOf(kind, name);
        if (!File.Exists(path)) return null;
        await using var stream = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream)).ToLowerInvariant();
    }

    /// <summary>
    /// Сохраняет поток во временный файл рядом с целевым и считает SHA-256.
    /// Целевой файл не трогается, пока вызывающий не решит, что делать (см. FilesController).
    /// </summary>
    public async Task<(string TempPath, string Sha256, long Size)> SaveTempAsync(string kind, string name, Stream content)
    {
        var dir = Path.Combine(Root, kind);
        Directory.CreateDirectory(dir);
        var temp = Path.Combine(dir, $"{name}.{Guid.NewGuid():N}.upload");
        using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        long size = 0;
        await using (var output = File.Create(temp))
        {
            var buffer = new byte[81920];
            int read;
            while ((read = await content.ReadAsync(buffer)) > 0)
            {
                sha.AppendData(buffer, 0, read);
                await output.WriteAsync(buffer.AsMemory(0, read));
                size += read;
            }
        }
        return (temp, Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant(), size);
    }

    public void Delete(string kind, string name)
    {
        if (IsValidName(name)) File.Delete(PathOf(kind, name));
    }
}
