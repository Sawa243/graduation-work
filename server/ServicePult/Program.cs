using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.WebEncoders;
using Serilog;
using System.Text.Encodings.Web;
using System.Text.Unicode;
using ServicePult.Data;
using ServicePult.SeedData;
using ServicePult.Services;

var builder = WebApplication.CreateBuilder(args);

// ---------------------------------------------------------------- журнал (Serilog)
// Консоль + файл logs/servicepult-ГГГГММДД.log (новый файл каждый день). События безопасности
// (входы, блокировки, отклонённые записи push, загрузки файлов) пишутся с пометкой [Безопасность].
builder.Services.AddSerilog((services, log) => log
    .ReadFrom.Configuration(builder.Configuration)
    .Enrich.FromLogContext()
    .WriteTo.Console()
    .WriteTo.File(Path.Combine(builder.Environment.ContentRootPath, "logs", "servicepult-.log"),
        rollingInterval: RollingInterval.Day, retainedFileCountLimit: 90, shared: true));

// ---------------------------------------------------------------- база данных
var connectionString = builder.Configuration.GetConnectionString("Default");
if (string.IsNullOrWhiteSpace(connectionString))
    throw new InvalidOperationException(
        "Не задана строка подключения ConnectionStrings:Default. " +
        "Укажите её в appsettings.Development.json или в переменной окружения ConnectionStrings__Default.");
builder.Services.AddDbContext<AppDbContext>(o => o.UseNpgsql(connectionString));

// ---------------------------------------------------------------- вход по JWT
// Без ключа Jwt:Key сервер не стартует: TokenService бросает исключение с понятным текстом.
var tokenService = new TokenService(builder.Configuration);
builder.Services.AddSingleton(tokenService);
builder.Services.AddMemoryCache();
builder.Services.AddSingleton<LoginAttempts>();

builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(o =>
    {
        o.MapInboundClaims = false; // имена утверждений как в токене: sub, role, typ
        o.TokenValidationParameters = tokenService.ValidationParameters;
        o.Events = new JwtBearerEvents
        {
            // токен обновления вместо токена доступа не принимается
            OnTokenValidated = ctx =>
            {
                if (ctx.Principal?.FindFirst(TokenService.TypeClaim)?.Value != TokenService.AccessType)
                    ctx.Fail("Передан не токен доступа");
                return Task.CompletedTask;
            },
            OnChallenge = ctx =>
            {
                ctx.HandleResponse();
                return Program.WriteProblem(ctx.HttpContext, StatusCodes.Status401Unauthorized,
                    "Требуется вход: токен доступа отсутствует, недействителен или истёк");
            },
            OnForbidden = ctx => Program.WriteProblem(ctx.HttpContext, StatusCodes.Status403Forbidden,
                "Недостаточно прав: API доступен только инженерам"),
        };
    })
    // ------------------------------------------------------------ вход на страницы пульта (cookie)
    // Отдельная схема: API принимает только JWT, страницы — только cookie.
    .AddCookie(Program.PultScheme, o =>
    {
        o.LoginPath = "/Account/Login";
        o.LogoutPath = "/Account/Logout";
        o.AccessDeniedPath = "/Account/AccessDenied";
        o.Cookie.Name = "ServicePult.Auth";
        o.Cookie.HttpOnly = true;
        o.Cookie.SameSite = SameSiteMode.Strict;
        o.Cookie.SecurePolicy = CookieSecurePolicy.SameAsRequest; // в эксплуатации — только HTTPS
        o.ExpireTimeSpan = TimeSpan.FromHours(8);                 // рабочая смена
        o.SlidingExpiration = true;
        // Заблокированный сотрудник или сменивший роль теряет доступ сразу, а не по истечении cookie.
        o.Events.OnValidatePrincipal = PultAuth.ValidateAsync;
    });

builder.Services.AddAuthorization(o =>
{
    o.AddPolicy(Program.EngineerPolicy, p => p
        .AddAuthenticationSchemes(JwtBearerDefaults.AuthenticationScheme)
        .RequireAuthenticatedUser()
        .RequireClaim("role", "1"));
    // страницы пульта: диспетчер (2) и руководитель службы (3)
    o.AddPolicy(Program.PultPolicy, p => p
        .AddAuthenticationSchemes(Program.PultScheme)
        .RequireAuthenticatedUser()
        .RequireClaim("role", "2", "3"));
    // отчёты: только руководитель службы
    o.AddPolicy(Program.HeadPolicy, p => p
        .AddAuthenticationSchemes(Program.PultScheme)
        .RequireAuthenticatedUser()
        .RequireClaim("role", "3"));
});

// ---------------------------------------------------------------- API, JSON, ошибки
builder.Services.AddControllers()
    .AddJsonOptions(o => JsonSetup.Apply(o.JsonSerializerOptions));
builder.Services.ConfigureHttpJsonOptions(o => JsonSetup.Apply(o.SerializerOptions));
builder.Services.AddProblemDetails(o => o.CustomizeProblemDetails = ctx =>
{
    // заголовки ошибок по умолчанию (Bad Request, Not Found…) заменяются русскими
    var status = ctx.ProblemDetails.Status ?? ctx.HttpContext.Response.StatusCode;
    if (ctx.ProblemDetails.Title == null || !Program.IsRussian(ctx.ProblemDetails.Title))
        ctx.ProblemDetails.Title = Program.RussianTitle(status);
    // Ошибка разбора запроса с русским текстом (например, время без смещения) — сразу в заголовок.
    if (ctx.ProblemDetails is HttpValidationProblemDetails v
        && v.Errors.SelectMany(e => e.Value).FirstOrDefault(Program.IsRussian) is { } russian)
        ctx.ProblemDetails.Title = russian;
});

// Страницы пульта: все закрыты политикой «диспетчер или руководитель», отчёты — только руководитель.
builder.Services.AddRazorPages(o =>
{
    o.Conventions.AuthorizeFolder("/", Program.PultPolicy);
    o.Conventions.AuthorizeFolder("/Reports", Program.HeadPolicy);
    o.Conventions.AllowAnonymousToPage("/Account/Login");
    o.Conventions.AllowAnonymousToPage("/Account/AccessDenied");
});

// кириллица в разметке — как есть, без замены на &#x...; (спецсимволы HTML экранируются по-прежнему)
builder.Services.Configure<WebEncoderOptions>(o => o.TextEncoderSettings = new TextEncoderSettings(UnicodeRanges.All));

builder.Services.AddSingleton<FileStorage>();
builder.Services.AddScoped<PushService>();
builder.Services.AddScoped<RequestService>();
builder.Services.AddScoped<ReportService>();

var app = builder.Build();

// ---------------------------------------------------------------- миграции и тестовые данные
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
    db.Database.Migrate();
    await SeedLoader.LoadIfEmptyAsync(db, Path.Combine(AppContext.BaseDirectory, "SeedData", "seed.json"),
        app.Logger);
}

// ---------------------------------------------------------------- конвейер запросов
if (app.Environment.IsProduction())
{
    // в эксплуатации — только HTTPS
    app.UseHsts();
    app.UseHttpsRedirection();
}
app.UseExceptionHandler();
app.UseStatusCodePages();
app.UseSerilogRequestLogging();

app.UseStaticFiles(); // wwwroot: css и js страниц пульта (без внешних CDN)
app.UseRouting();
app.UseAuthentication();
app.UseAuthorization();

app.MapControllers();
app.MapRazorPages();

app.Run();

public partial class Program
{
    /// <summary>Политика мобильного API: вход по токену доступа, роль 1 — инженер.</summary>
    public const string EngineerPolicy = "Engineer";

    /// <summary>Схема входа на страницы пульта (cookie).</summary>
    public const string PultScheme = "Pult";

    /// <summary>Политика страниц пульта: роль 2 — диспетчер или 3 — руководитель службы.</summary>
    public const string PultPolicy = "Pult";

    /// <summary>Политика отчётов: роль 3 — руководитель службы.</summary>
    public const string HeadPolicy = "Head";

    /// <summary>В тексте есть русские буквы — значит, сообщение уже написано для пользователя.</summary>
    public static bool IsRussian(string text) => text.Any(c => c is >= 'А' and <= 'я');

    public static string RussianTitle(int status) => status switch
    {
        400 => "Ошибка во входных данных",
        401 => "Требуется вход",
        403 => "Доступ запрещён",
        404 => "Не найдено",
        405 => "Метод не поддерживается",
        409 => "Конфликт данных",
        413 => "Слишком большой запрос",
        415 => "Неподдерживаемый формат данных",
        429 => "Слишком много запросов",
        _ when status >= 500 => "Внутренняя ошибка сервера",
        _ => "Ошибка запроса",
    };

    /// <summary>Ответ-ошибка в формате ProblemDetails (RFC 9457) вне контроллеров.</summary>
    public static async Task WriteProblem(HttpContext http, int status, string title)
    {
        http.Response.StatusCode = status;
        await http.RequestServices.GetRequiredService<IProblemDetailsService>().WriteAsync(new ProblemDetailsContext
        {
            HttpContext = http,
            ProblemDetails = new ProblemDetails { Status = status, Title = title },
        });
    }
}
