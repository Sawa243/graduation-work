using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Controllers;

/// <summary>Вход в мобильное приложение и обновление токенов (проект, раздел 10).</summary>
[ApiController]
[Route("api/auth")]
public class AuthController(AppDbContext db, TokenService tokens, LoginAttempts attempts, ILogger<AuthController> log)
    : ControllerBase
{
    // Хеш-пустышка: при неизвестном логине пароль всё равно проверяется, чтобы по времени ответа
    // нельзя было узнать, есть ли такой логин.
    private static readonly string DummyHash = PasswordHasher.Hash("-");

    [HttpPost("login")]
    public async Task<ActionResult<TokenResponse>> Login(LoginRequest body)
    {
        var login = body.Login.Trim();
        var ip = HttpContext.Connection.RemoteIpAddress?.ToString();

        // попытка занимается до проверки пароля: параллельные запросы не обойдут счётчик
        if (!attempts.TryStart(login))
        {
            log.LogWarning("[Безопасность] Вход отклонён: логин {Login} заблокирован после неудачных попыток, IP {Ip}", login, ip);
            return Problem(statusCode: StatusCodes.Status429TooManyRequests,
                title: "Слишком много неудачных попыток входа. Повторите через 15 минут");
        }

        var employee = await db.Employees.FirstOrDefaultAsync(e => e.Login == login);
        var passwordOk = PasswordHasher.Verify(body.Password, employee?.PassHash ?? DummyHash) && employee != null;
        if (!passwordOk)
        {
            var lockedNow = attempts.RegisterFailure(login);
            log.LogWarning("[Безопасность] Неудачный вход: логин {Login}, IP {Ip}", login, ip);
            if (lockedNow)
                log.LogWarning("[Безопасность] Блокировка входа на 15 минут: логин {Login}, IP {Ip}", login, ip);
            return Problem(statusCode: StatusCodes.Status401Unauthorized, title: "Неверный логин или пароль");
        }
        attempts.Reset(login);

        if (employee!.Blocked)
        {
            log.LogWarning("[Безопасность] Вход отклонён: учётная запись {Login} заблокирована, IP {Ip}", login, ip);
            return Problem(statusCode: StatusCodes.Status403Forbidden, title: "Учётная запись заблокирована");
        }
        if (employee.Role != 1)
        {
            log.LogWarning("[Безопасность] Вход отклонён: {Login} не инженер (роль {Role}), IP {Ip}", login, employee.Role, ip);
            return Problem(statusCode: StatusCodes.Status403Forbidden,
                title: "В мобильное приложение входят только инженеры");
        }

        log.LogInformation("[Безопасность] Успешный вход: {Login} (id {Id}), IP {Ip}", login, employee.Id, ip);
        return Issue(employee);
    }

    [HttpPost("refresh")]
    public async Task<ActionResult<TokenResponse>> Refresh(RefreshRequest body)
    {
        var id = await tokens.ValidateRefreshAsync(body.RefreshToken);
        if (id == null)
        {
            log.LogWarning("[Безопасность] Обновление токена отклонено: токен недействителен или истёк");
            return Problem(statusCode: StatusCodes.Status401Unauthorized, title: "Токен обновления недействителен или истёк");
        }

        // «Отзыв» токена: сотрудник читается заново, заблокированному новые токены не выдаются.
        var employee = await db.Employees.FindAsync(id.Value);
        if (employee == null || employee.Blocked || employee.Role != 1)
        {
            log.LogWarning("[Безопасность] Обновление токена отклонено: сотрудник {Id} заблокирован или не инженер", id);
            return Problem(statusCode: StatusCodes.Status401Unauthorized, title: "Учётная запись заблокирована");
        }
        return Issue(employee);
    }

    private TokenResponse Issue(Employee employee)
    {
        var pair = tokens.CreatePair(employee);
        return new TokenResponse(pair.AccessToken, pair.AccessExpiresAt, pair.RefreshToken, pair.RefreshExpiresAt,
            EmployeeDto.From(employee, withLogin: true), DateTimeOffset.UtcNow);
    }
}
