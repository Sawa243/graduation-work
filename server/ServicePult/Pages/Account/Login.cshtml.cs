using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Pages.Account;

/// <summary>
/// Вход на пульт: та же проверка пароля и тот же счётчик ошибок, что в API (AuthController).
/// Пускают диспетчера (2) и руководителя службы (3); инженеры работают в мобильном приложении.
/// </summary>
public class LoginModel(AppDbContext db, LoginAttempts attempts, ILogger<LoginModel> log) : PageModel
{
    private static readonly string DummyHash = PasswordHasher.Hash("-");

    [BindProperty] public string? Login { get; set; }
    [BindProperty] public string? Password { get; set; }
    [BindProperty(SupportsGet = true)] public string? ReturnUrl { get; set; }

    public string? Error { get; private set; }

    public void OnGet()
    {
    }

    public async Task<IActionResult> OnPostAsync()
    {
        // пустое поле формы приходит как null
        var login = (Login ?? "").Trim();
        var password = Password ?? "";
        var ip = HttpContext.Connection.RemoteIpAddress?.ToString();

        if (login.Length == 0 || password.Length == 0)
        {
            Error = "Введите логин и пароль";
            return Page();
        }

        // попытка занимается до проверки пароля: параллельные запросы не обойдут счётчик
        if (!attempts.TryStart(login))
        {
            log.LogWarning("[Безопасность] Вход на пульт отклонён: логин {Login} заблокирован после неудачных попыток, IP {Ip}", login, ip);
            Error = "Слишком много неудачных попыток входа. Повторите через 15 минут";
            return Page();
        }

        var employee = await db.Employees.AsNoTracking().FirstOrDefaultAsync(e => e.Login == login);
        var passwordOk = PasswordHasher.Verify(password, employee?.PassHash ?? DummyHash) && employee != null;
        if (!passwordOk)
        {
            var lockedNow = attempts.RegisterFailure(login);
            log.LogWarning("[Безопасность] Неудачный вход на пульт: логин {Login}, IP {Ip}", login, ip);
            if (lockedNow)
                log.LogWarning("[Безопасность] Блокировка входа на 15 минут: логин {Login}, IP {Ip}", login, ip);
            Error = "Неверный логин или пароль";
            return Page();
        }
        attempts.Reset(login);

        if (employee!.Blocked)
        {
            log.LogWarning("[Безопасность] Вход на пульт отклонён: учётная запись {Login} заблокирована, IP {Ip}", login, ip);
            Error = "Учётная запись заблокирована";
            return Page();
        }
        if (!PultAuth.CanUsePult(employee.Role))
        {
            log.LogWarning("[Безопасность] Вход на пульт отклонён: {Login} — роль {Role}, IP {Ip}", login, employee.Role, ip);
            Error = "На пульт входят только диспетчеры и руководитель службы. Инженеры работают в мобильном приложении";
            return Page();
        }

        await HttpContext.SignInAsync(Program.PultScheme, PultAuth.CreatePrincipal(employee));
        log.LogInformation("[Безопасность] Успешный вход на пульт: {Login} (id {Id}, роль {Role}), IP {Ip}",
            login, employee.Id, employee.Role, ip);

        // возврат только на адрес этого же сайта
        return LocalRedirect(Url.IsLocalUrl(ReturnUrl) ? ReturnUrl! : "/Dispatcher");
    }
}
