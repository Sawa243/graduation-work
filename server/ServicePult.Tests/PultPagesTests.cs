using System.Net;
using ServicePult.Services;

namespace ServicePult.Tests;

/// <summary>
/// Страницы пульта и роли (проект, раздел 10): диспетчер и руководитель — по cookie,
/// инженер на пульт не допускается, отчёт по SLA — только руководителю.
/// Руководитель в этих тестах — отдельная учётная запись: «head» блокирует тест счётчика ошибок входа.
/// </summary>
[Collection(ServerCollection.Name)]
public class PultPagesTests(ServerFixture server)
{
    private const short Head = 3;

    [Fact]
    public async Task Диспетчер_входит_и_видит_пульт()
    {
        server.RequireDatabase();
        var (client, login) = await server.PultLogin("dispatcher");
        using (client)
        {
            Assert.Equal(HttpStatusCode.Redirect, login.StatusCode);
            Assert.Equal("/Dispatcher", login.Headers.Location?.OriginalString);

            var page = await client.GetAsync("/Dispatcher", TestContext.Current.CancellationToken);
            Assert.Equal(HttpStatusCode.OK, page.StatusCode);
            var html = await page.Content.ReadAsStringAsync(TestContext.Current.CancellationToken);
            Assert.Contains("З-2609-0137", html);          // заявка из тестовых данных
            Assert.Contains("http-equiv=\"refresh\"", html); // автообновление

            Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/Dispatcher/New", TestContext.Current.CancellationToken)).StatusCode);
            Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/Directories/Calendar", TestContext.Current.CancellationToken)).StatusCode);
        }
    }

    [Fact]
    public async Task Без_входа_пульт_отправляет_на_страницу_входа()
    {
        server.RequireDatabase();
        using var client = server.BrowserClient();
        var response = await client.GetAsync("/Dispatcher", TestContext.Current.CancellationToken);
        Assert.Equal(HttpStatusCode.Redirect, response.StatusCode);
        Assert.StartsWith("http://localhost/Account/Login", response.Headers.Location?.ToString());
    }

    [Fact]
    public async Task Инженера_на_пульт_не_пускают()
    {
        server.RequireDatabase();
        var (client, login) = await server.PultLogin("engineer");
        using (client)
        {
            Assert.Equal(HttpStatusCode.OK, login.StatusCode); // форма входа с сообщением, cookie не выдан
            Assert.Contains("Инженеры работают в мобильном приложении",
                await login.Content.ReadAsStringAsync(TestContext.Current.CancellationToken));

            var page = await client.GetAsync("/Dispatcher", TestContext.Current.CancellationToken);
            Assert.Equal(HttpStatusCode.Redirect, page.StatusCode);
            Assert.Contains("/Account/Login", page.Headers.Location?.ToString());
        }
    }

    [Fact]
    public async Task Неверный_пароль_на_пульте_одно_сообщение()
    {
        server.RequireDatabase();
        var (a, wrongPassword) = await server.PultLogin("dispatcher", "не тот");
        var (b, unknownLogin) = await server.PultLogin("nobody-pult", "не тот");
        a.Dispose();
        b.Dispose();
        Assert.Contains("Неверный логин или пароль", await wrongPassword.Content.ReadAsStringAsync(TestContext.Current.CancellationToken));
        Assert.Contains("Неверный логин или пароль", await unknownLogin.Content.ReadAsStringAsync(TestContext.Current.CancellationToken));
        // верный вход сбрасывает счётчик ошибок диспетчера
        (await server.PultLogin("dispatcher")).Client.Dispose();
    }

    [Fact]
    public async Task Пустая_форма_входа_не_роняет_страницу()
    {
        server.RequireDatabase();
        using var client = server.BrowserClient();
        var form = await client.GetStringAsync("/Account/Login", TestContext.Current.CancellationToken);
        var response = await client.PostAsync("/Account/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["Login"] = "",
            ["Password"] = "",
            ["__RequestVerificationToken"] = ServerFixture.AntiforgeryToken(form),
        }), TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Contains("Введите логин и пароль", await response.Content.ReadAsStringAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task Даты_за_пределами_2000_2100_не_роняют_отчёт_и_календарь()
    {
        server.RequireDatabase();
        await server.EnsureEmployee("head-pult", Head, "00091");
        var (head, _) = await server.PultLogin("head-pult");
        using (head)
        {
            var report = await head.GetAsync("/Reports/Sla?From=2026-09-01&To=9999-12-31", TestContext.Current.CancellationToken);
            Assert.Equal(HttpStatusCode.OK, report.StatusCode);
            Assert.Contains("только с 2000 по 2100 год", await report.Content.ReadAsStringAsync(TestContext.Current.CancellationToken));

            foreach (var year in new[] { 0, 10000, 9999 })
            {
                var calendar = await head.GetAsync($"/Directories/Calendar?Year={year}", TestContext.Current.CancellationToken);
                Assert.Equal(HttpStatusCode.OK, calendar.StatusCode);
                Assert.Contains("только с 2000 по 2100", await calendar.Content.ReadAsStringAsync(TestContext.Current.CancellationToken));
            }
        }
    }

    [Fact]
    public async Task Отчёт_по_SLA_открывает_руководитель_а_диспетчер_нет()
    {
        server.RequireDatabase();
        await server.EnsureEmployee("head-pult", Head, "00091");

        var (head, _) = await server.PultLogin("head-pult");
        using (head)
        {
            var report = await head.GetAsync("/Reports/Sla", TestContext.Current.CancellationToken);
            Assert.Equal(HttpStatusCode.OK, report.StatusCode);
            Assert.Contains("Отчёт о выполнении SLA", await report.Content.ReadAsStringAsync(TestContext.Current.CancellationToken));
            // руководитель может и всё, что диспетчер
            Assert.Equal(HttpStatusCode.OK, (await head.GetAsync("/Dispatcher", TestContext.Current.CancellationToken)).StatusCode);
        }

        var (dispatcher, _) = await server.PultLogin("dispatcher");
        using (dispatcher)
        {
            var report = await dispatcher.GetAsync("/Reports/Sla", TestContext.Current.CancellationToken);
            Assert.Equal(HttpStatusCode.Redirect, report.StatusCode);
            Assert.Contains("/Account/AccessDenied", report.Headers.Location?.ToString());
            var denied = await dispatcher.GetAsync(report.Headers.Location, TestContext.Current.CancellationToken);
            Assert.Equal(HttpStatusCode.Forbidden, denied.StatusCode);
        }
    }

    [Fact]
    public async Task Cookie_пульта_не_открывает_API_приложения()
    {
        server.RequireDatabase();
        var (client, _) = await server.PultLogin("dispatcher");
        using (client)
        {
            var pull = await client.GetAsync("/api/sync/pull", TestContext.Current.CancellationToken);
            Assert.Equal(HttpStatusCode.Unauthorized, pull.StatusCode);
        }
    }

    [Fact]
    public async Task Заблокированный_сотрудник_теряет_доступ_к_пульту_сразу()
    {
        server.RequireDatabase();
        var id = await server.EnsureEmployee("dispatcher-blocked", 2, "00092");
        var (client, _) = await server.PultLogin("dispatcher-blocked");
        using (client)
        {
            Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/Dispatcher", TestContext.Current.CancellationToken)).StatusCode);

            await server.WithDb(async db =>
            {
                (await db.Employees.FindAsync(id))!.Blocked = true;
                return await db.SaveChangesAsync();
            });

            var page = await client.GetAsync("/Dispatcher", TestContext.Current.CancellationToken);
            Assert.Equal(HttpStatusCode.Redirect, page.StatusCode);
            Assert.Contains("/Account/Login", page.Headers.Location?.ToString());
        }
    }

    [Fact]
    public void Роли_пульта()
    {
        Assert.False(PultAuth.CanUsePult(1));
        Assert.True(PultAuth.CanUsePult(2));
        Assert.True(PultAuth.CanUsePult(3));
    }
}
