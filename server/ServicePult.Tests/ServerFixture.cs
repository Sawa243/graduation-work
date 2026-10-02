using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;
using ServicePult.Controllers;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Tests;

/// <summary>
/// Сервер для интеграционных тестов: настоящий конвейер ASP.NET Core в памяти процесса
/// (WebApplicationFactory) и база servicepult_tests, которая очищается перед прогоном и заново
/// заполняется тестовыми данными seed.json.
///
/// Строка подключения — ConnectionStrings:Tests из файла appsettings.Development.json проекта
/// тестов (он не в git) или переменной окружения ConnectionStrings__Tests. Если её нет,
/// интеграционные тесты пропускаются с сообщением, а тесты SlaService работают как обычно.
/// </summary>
public class ServerFixture : IAsyncLifetime
{
    public const string Password = "Test2026!";

    private WebApplicationFactory<Program>? _factory;
    private string? _filesRoot;

    /// <summary>Причина пропуска интеграционных тестов или null, если база доступна.</summary>
    public string? SkipReason { get; private set; }

    public JsonSerializerOptions Json { get; } = JsonSetup.Create();

    public async ValueTask InitializeAsync()
    {
        var config = new ConfigurationBuilder()
            .AddJsonFile(Path.Combine(AppContext.BaseDirectory, "tests.appsettings.json"), optional: true)
            .AddEnvironmentVariables()
            .Build();
        var connectionString = config.GetConnectionString("Tests");
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            SkipReason = "Не задана строка подключения к тестовой базе: укажите ConnectionStrings:Tests " +
                         "в ServicePult.Tests/appsettings.Development.json или в переменной окружения ConnectionStrings__Tests";
            return;
        }

        try
        {
            await ResetDatabaseAsync(connectionString);
        }
        catch (NpgsqlException ex)
        {
            SkipReason = "Тестовая база недоступна (запущен ли PostgreSQL?): " + ex.Message;
            return;
        }

        // Настройки сервера — через переменные окружения: их читает WebApplication.CreateBuilder.
        _filesRoot = Path.Combine(Path.GetTempPath(), "servicepult-tests-" + Guid.NewGuid().ToString("N"));
        Environment.SetEnvironmentVariable("ConnectionStrings__Default", connectionString);
        Environment.SetEnvironmentVariable("Jwt__Key", Convert.ToBase64String(RandomNumberGenerator.GetBytes(48)));
        Environment.SetEnvironmentVariable("Files__Root", _filesRoot);

        _factory = new WebApplicationFactory<Program>().WithWebHostBuilder(b => b.UseEnvironment("Testing"));
        _factory.CreateClient().Dispose(); // запуск сервера: миграции и заливка seed.json
    }

    /// <summary>Схема — миграциями, затем все 14 таблиц очищаются: сервер при старте зальёт seed заново.</summary>
    private static async Task ResetDatabaseAsync(string connectionString)
    {
        var options = new DbContextOptionsBuilder<AppDbContext>().UseNpgsql(connectionString).Options;
        await using var db = new AppDbContext(options);
        await db.Database.MigrateAsync();
        await db.Database.ExecuteSqlRawAsync(
            "TRUNCATE act, work_log, photo, checklist_result, request, calendar_day, employee, checklist_item, " +
            "work_type, equipment, site, sla_level, contract, customer RESTART IDENTITY");
    }

    public async ValueTask DisposeAsync()
    {
        if (_factory != null) await _factory.DisposeAsync();
        if (_filesRoot != null && Directory.Exists(_filesRoot)) Directory.Delete(_filesRoot, recursive: true);
        GC.SuppressFinalize(this);
    }

    /// <summary>Пропускает тест, если база не настроена.</summary>
    public void RequireDatabase()
    {
        if (SkipReason != null) Assert.Skip(SkipReason);
    }

    public HttpClient Client() => _factory!.CreateClient();

    /// <summary>Контекст базы тестового сервера — для проверок и подготовки данных.</summary>
    public async Task<T> WithDb<T>(Func<AppDbContext, Task<T>> action)
    {
        using var scope = _factory!.Services.CreateScope();
        return await action(scope.ServiceProvider.GetRequiredService<AppDbContext>());
    }

    /// <summary>Служба тестового сервера (например, FileStorage — проверить файл на диске).</summary>
    public T Service<T>() where T : notnull => _factory!.Services.GetRequiredService<T>();

    /// <summary>
    /// Сдвигает updated_at всех записей на час назад: pull отбирает изменения с запасом в 5 минут
    /// (SyncController.SinceMargin), и без сдвига свежезалитые тестовые данные попадали бы в каждый ответ.
    /// </summary>
    public Task AgeAllRowsAsync() => WithDb(async db =>
    {
        foreach (var table in new[] { "customer", "contract", "sla_level", "site", "equipment", "work_type", "checklist_item",
                     "employee", "calendar_day", "request", "checklist_result", "photo", "work_log", "act" })
            await db.Database.ExecuteSqlRawAsync("UPDATE " + table + " SET updated_at = updated_at - interval '1 hour'");
        return 0;
    });

    // ---------------------------------------------------------------- помощники запросов

    public Task<HttpResponseMessage> PostJson(HttpClient client, string url, object body) =>
        client.PostAsJsonAsync(url, body, Json);

    public async Task<T> Read<T>(HttpResponseMessage response) =>
        (await response.Content.ReadFromJsonAsync<T>(Json))!;

    public async Task<TokenResponse> Login(string login, string password = Password)
    {
        using var client = Client();
        var response = await PostJson(client, "/api/auth/login", new { Login = login, Password = password });
        response.EnsureSuccessStatusCode();
        return await Read<TokenResponse>(response);
    }

    /// <summary>Клиент инженера с токеном доступа в заголовке Authorization.</summary>
    public async Task<HttpClient> EngineerClient(string login = "engineer")
    {
        var tokens = await Login(login);
        var client = Client();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", tokens.AccessToken);
        return client;
    }

    public async Task<PullResponse> Pull(HttpClient client, DateTimeOffset? since = null)
    {
        var url = "/api/sync/pull" + (since == null ? "" : "?since=" + Uri.EscapeDataString(since.Value.ToString("O")));
        var response = await client.GetAsync(url);
        response.EnsureSuccessStatusCode();
        return await Read<PullResponse>(response);
    }

    public async Task<PushResponse> Push(HttpClient client, object body)
    {
        var response = await PostJson(client, "/api/sync/push", body);
        response.EnsureSuccessStatusCode();
        return await Read<PushResponse>(response);
    }

    /// <summary>Загружает файл; возвращает ответ сервера как есть.</summary>
    public async Task<HttpResponseMessage> Upload(HttpClient client, string kind, string name, byte[] content)
    {
        using var form = new MultipartFormDataContent();
        form.Add(new ByteArrayContent(content), "file", name);
        return await client.PostAsync($"/api/files?kind={kind}&name={Uri.EscapeDataString(name)}", form);
    }

    // ---------------------------------------------------------------- страницы пульта

    /// <summary>Клиент как браузер: хранит cookie, перенаправления не выполняет (их проверяют тесты).</summary>
    public HttpClient BrowserClient() =>
        _factory!.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, HandleCookies = true });

    /// <summary>Скрытое поле защиты от подделки запроса из HTML формы.</summary>
    public static string AntiforgeryToken(string html) =>
        Regex.Match(html, "name=\"__RequestVerificationToken\" type=\"hidden\" value=\"([^\"]+)\"").Groups[1].Value;

    /// <summary>Вход на пульт через форму /Account/Login; возвращает клиента с cookie и ответ на отправку формы.</summary>
    public async Task<(HttpClient Client, HttpResponseMessage Response)> PultLogin(string login, string password = Password)
    {
        var client = BrowserClient();
        var form = await client.GetStringAsync("/Account/Login");
        var response = await client.PostAsync("/Account/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["Login"] = login,
            ["Password"] = password,
            ["__RequestVerificationToken"] = AntiforgeryToken(form),
        }));
        return (client, response);
    }

    /// <summary>Добавляет сотрудника с паролем Test2026!, если его ещё нет (отдельные учётные записи тестов).</summary>
    public Task<int> EnsureEmployee(string login, short role, string tabNo) => WithDb(async db =>
    {
        var e = await db.Employees.FirstOrDefaultAsync(x => x.Login == login);
        if (e != null) return e.Id;
        e = new Employee
        {
            TabNo = tabNo, FullName = "Проверочный " + login, Position = "Проверка", Role = role, Login = login,
            PassHash = PasswordHasher.Hash(Password),
        };
        db.Employees.Add(e);
        await db.SaveChangesAsync();
        return e.Id;
    });

    public static string Sha256(byte[] content) => Convert.ToHexString(SHA256.HashData(content)).ToLowerInvariant();
}

[CollectionDefinition(Name)]
public class ServerCollection : ICollectionFixture<ServerFixture>
{
    public const string Name = "Сервер и тестовая база";
}
