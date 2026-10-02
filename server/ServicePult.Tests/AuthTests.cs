using System.Net;
using System.Net.Http.Headers;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Caching.Memory;
using ServicePult.Controllers;
using ServicePult.Data;
using ServicePult.Services;

namespace ServicePult.Tests;

/// <summary>Вход и обновление токенов (проект, раздел 10).</summary>
[Collection(ServerCollection.Name)]
public class AuthTests(ServerFixture server)
{
    [Fact]
    public async Task Инженер_входит_с_верным_паролем()
    {
        server.RequireDatabase();
        var tokens = await server.Login("engineer");

        Assert.False(string.IsNullOrEmpty(tokens.AccessToken));
        Assert.False(string.IsNullOrEmpty(tokens.RefreshToken));
        Assert.Equal("engineer", tokens.Employee.Login);
        Assert.Equal(1, tokens.Employee.Role);
        Assert.InRange(tokens.AccessExpiresAt - tokens.ServerTime, TimeSpan.FromMinutes(29), TimeSpan.FromMinutes(31));
        Assert.InRange(tokens.RefreshExpiresAt - tokens.ServerTime, TimeSpan.FromDays(29.9), TimeSpan.FromDays(30.1));
        Assert.Equal(TimeSpan.FromHours(3), tokens.ServerTime.Offset); // время — по Москве со смещением
    }

    [Fact]
    public async Task Неверный_пароль_и_неизвестный_логин_дают_одно_сообщение()
    {
        server.RequireDatabase();
        using var client = server.Client();

        var wrongPassword = await server.PostJson(client, "/api/auth/login", new { Login = "engineer", Password = "не тот" });
        var unknownLogin = await server.PostJson(client, "/api/auth/login", new { Login = "nobody", Password = "не тот" });

        Assert.Equal(HttpStatusCode.Unauthorized, wrongPassword.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, unknownLogin.StatusCode);
        var a = await server.Read<ProblemDetails>(wrongPassword);
        var b = await server.Read<ProblemDetails>(unknownLogin);
        Assert.Equal("Неверный логин или пароль", a.Title);
        Assert.Equal(a.Title, b.Title);
    }

    [Fact]
    public async Task Диспетчера_в_мобильный_API_не_пускают()
    {
        server.RequireDatabase();
        using var client = server.Client();
        var response = await server.PostJson(client, "/api/auth/login", new { Login = "dispatcher", Password = ServerFixture.Password });
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task После_5_ошибок_подряд_вход_закрыт_даже_с_верным_паролем()
    {
        server.RequireDatabase();
        using var client = server.Client();
        for (var i = 0; i < LoginAttempts.MaxFailures; i++)
        {
            var bad = await server.PostJson(client, "/api/auth/login", new { Login = "head", Password = "ошибка" + i });
            Assert.Equal(HttpStatusCode.Unauthorized, bad.StatusCode);
        }

        var locked = await server.PostJson(client, "/api/auth/login", new { Login = "head", Password = ServerFixture.Password });
        Assert.Equal(HttpStatusCode.TooManyRequests, locked.StatusCode);
    }

    [Fact]
    public async Task Параллельные_неверные_входы_не_обходят_счётчик()
    {
        server.RequireDatabase();
        await server.EnsureEmployee("engineer-flood", 1, "00081");
        using var client = server.Client();

        var responses = await Task.WhenAll(Enumerable.Range(0, 30).Select(i =>
            server.PostJson(client, "/api/auth/login", new { Login = "engineer-flood", Password = "ошибка" + i })));
        var codes = responses.Select(r => r.StatusCode).ToList();

        // пароль проверен не больше 5 раз, остальные запросы отклонены без проверки
        Assert.InRange(codes.Count(c => c == HttpStatusCode.Unauthorized), 1, LoginAttempts.MaxFailures);
        Assert.Equal(30, codes.Count(c => c is HttpStatusCode.Unauthorized or HttpStatusCode.TooManyRequests));
        var locked = await server.PostJson(client, "/api/auth/login", new { Login = "engineer-flood", Password = ServerFixture.Password });
        Assert.Equal(HttpStatusCode.TooManyRequests, locked.StatusCode);
    }

    [Fact]
    public void Счётчик_занимает_попытку_до_проверки_пароля()
    {
        using var cache = new MemoryCache(new MemoryCacheOptions());
        var attempts = new LoginAttempts(cache);

        var started = 0;
        Parallel.For(0, 30, _ => { if (attempts.TryStart("user")) Interlocked.Increment(ref started); });
        Assert.Equal(LoginAttempts.MaxFailures, started);

        for (var i = 0; i < started; i++) attempts.RegisterFailure("user");
        Assert.False(attempts.TryStart("user")); // вход закрыт на 15 минут

        // успешный вход сбрасывает счётчик
        var other = new LoginAttempts(cache);
        Assert.True(other.TryStart("user2"));
        other.Reset("user2");
        for (var i = 0; i < LoginAttempts.MaxFailures; i++) Assert.True(other.TryStart("user2"));
    }

    [Fact]
    public async Task Токен_обновления_выдаёт_новую_пару()
    {
        server.RequireDatabase();
        var tokens = await server.Login("engineer");
        using var client = server.Client();

        var response = await server.PostJson(client, "/api/auth/refresh", new { tokens.RefreshToken });
        response.EnsureSuccessStatusCode();
        var renewed = await server.Read<TokenResponse>(response);
        Assert.False(string.IsNullOrEmpty(renewed.AccessToken));
        Assert.NotEqual(tokens.RefreshToken, renewed.RefreshToken);
    }

    [Fact]
    public async Task Токены_нельзя_подставить_один_вместо_другого()
    {
        server.RequireDatabase();
        var tokens = await server.Login("engineer");
        using var client = server.Client();

        // токен доступа вместо токена обновления
        var refresh = await server.PostJson(client, "/api/auth/refresh", new { RefreshToken = tokens.AccessToken });
        Assert.Equal(HttpStatusCode.Unauthorized, refresh.StatusCode);

        // токен обновления вместо токена доступа
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", tokens.RefreshToken);
        var pull = await client.GetAsync("/api/sync/pull", TestContext.Current.CancellationToken);
        Assert.Equal(HttpStatusCode.Unauthorized, pull.StatusCode);
    }

    [Fact]
    public async Task Заблокированному_сотруднику_токены_не_обновляются()
    {
        server.RequireDatabase();
        // отдельный инженер, чтобы не мешать другим тестам
        await server.WithDb(async db =>
        {
            db.Employees.Add(new Employee
            {
                TabNo = "00077", FullName = "Проверочный Пётр Тестович", Position = "Инженер выездной службы",
                Role = 1, Login = "engineer-blocked", PassHash = PasswordHasher.Hash(ServerFixture.Password),
            });
            return await db.SaveChangesAsync();
        });
        var tokens = await server.Login("engineer-blocked");

        await server.WithDb(async db =>
        {
            var e = db.Employees.Single(x => x.Login == "engineer-blocked");
            e.Blocked = true;
            return await db.SaveChangesAsync();
        });

        using var client = server.Client();
        var refresh = await server.PostJson(client, "/api/auth/refresh", new { tokens.RefreshToken });
        Assert.Equal(HttpStatusCode.Unauthorized, refresh.StatusCode);
        var login = await server.PostJson(client, "/api/auth/login", new { Login = "engineer-blocked", Password = ServerFixture.Password });
        Assert.Equal(HttpStatusCode.Forbidden, login.StatusCode);
    }
}
