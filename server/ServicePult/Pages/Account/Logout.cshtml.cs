using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using ServicePult.Services;

namespace ServicePult.Pages.Account;

/// <summary>Выход с пульта (только POST — с защитой от подделки запроса).</summary>
public class LogoutModel(ILogger<LogoutModel> log) : PageModel
{
    public IActionResult OnGet() => Redirect("/Dispatcher");

    public async Task<IActionResult> OnPostAsync()
    {
        log.LogInformation("[Безопасность] Выход с пульта: сотрудник {Id}", User.FindFirst("sub")?.Value);
        await HttpContext.SignOutAsync(Program.PultScheme);
        return Redirect("/Account/Login");
    }
}
