using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.IdentityModel.JsonWebTokens;
using ServicePult.Data;

namespace ServicePult.Services;

/// <summary>
/// Вход на страницы пульта по cookie (проект, раздел 10): диспетчер (2) и руководитель службы (3).
/// В cookie те же утверждения, что в токене приложения: sub — id сотрудника, role — роль;
/// плюс ФИО для шапки страниц.
/// </summary>
public static class PultAuth
{
    public const string NameClaim = "name";

    /// <summary>Роли, которых пускают на страницы пульта.</summary>
    public static bool CanUsePult(short role) => role is 2 or 3;

    public static ClaimsPrincipal CreatePrincipal(Employee employee) =>
        new(new ClaimsIdentity(
        [
            new Claim(JwtRegisteredClaimNames.Sub, employee.Id.ToString()),
            new Claim(NameClaim, employee.FullName),
            new Claim("role", employee.Role.ToString()),
        ], Program.PultScheme, NameClaim, "role"));

    /// <summary>
    /// Проверка при каждом запросе: если сотрудника заблокировали или сменили ему роль,
    /// cookie перестаёт действовать сразу (аналог отзыва токена обновления в API).
    /// </summary>
    public static async Task ValidateAsync(CookieValidatePrincipalContext ctx)
    {
        var db = ctx.HttpContext.RequestServices.GetRequiredService<AppDbContext>();
        var idText = ctx.Principal?.FindFirst(JwtRegisteredClaimNames.Sub)?.Value;
        var employee = int.TryParse(idText, out var id) ? await db.Employees.FindAsync(id) : null;
        if (employee != null && !employee.Blocked && CanUsePult(employee.Role)
            && employee.Role.ToString() == ctx.Principal!.FindFirst("role")?.Value)
            return;

        ctx.RejectPrincipal();
        await ctx.HttpContext.SignOutAsync(Program.PultScheme);
        ctx.HttpContext.RequestServices.GetRequiredService<ILogger<CookieAuthenticationHandler>>()
            .LogWarning("[Безопасность] Сеанс пульта прекращён: сотрудник {Id} заблокирован или сменил роль", idText);
    }
}
