using System.Security.Claims;
using System.Text;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using ServicePult.Data;

namespace ServicePult.Services;

/// <summary>Пара токенов для приложения и сроки их действия.</summary>
public record TokenPair(string AccessToken, DateTimeOffset AccessExpiresAt, string RefreshToken, DateTimeOffset RefreshExpiresAt);

/// <summary>
/// Выпуск и проверка JWT (HMAC-SHA256). Токен доступа живёт 30 минут, токен обновления — 30 дней;
/// назначение токена — в утверждении typ (access / refresh), чтобы один нельзя было подставить вместо другого.
/// Таблиц для токенов нет: «отзыв» — это проверка employee.blocked при каждом обновлении (раздел 10).
/// </summary>
public class TokenService
{
    public const string Issuer = "ServicePult";
    public const string TypeClaim = "typ";
    public const string AccessType = "access";
    public const string RefreshType = "refresh";
    public static readonly TimeSpan AccessLifetime = TimeSpan.FromMinutes(30);
    public static readonly TimeSpan RefreshLifetime = TimeSpan.FromDays(30);

    private readonly SigningCredentials _credentials;
    private readonly JsonWebTokenHandler _handler = new();

    /// <summary>Параметры проверки токена — общие для JwtBearer и для обновления.</summary>
    public TokenValidationParameters ValidationParameters { get; }

    public TokenService(IConfiguration config)
    {
        var key = config["Jwt:Key"];
        if (string.IsNullOrWhiteSpace(key) || Encoding.UTF8.GetByteCount(key) < 32)
            throw new InvalidOperationException(
                "Не задан ключ подписи токенов Jwt:Key (не короче 32 байт). " +
                "Укажите его в appsettings.Development.json или в переменной окружения Jwt__Key.");

        var signingKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(key));
        _credentials = new SigningCredentials(signingKey, SecurityAlgorithms.HmacSha256);
        ValidationParameters = new TokenValidationParameters
        {
            ValidIssuer = Issuer,
            ValidAudience = Issuer,
            IssuerSigningKey = signingKey,
            ValidAlgorithms = [SecurityAlgorithms.HmacSha256],
            ClockSkew = TimeSpan.FromSeconds(30),
            NameClaimType = JwtRegisteredClaimNames.Sub,
            RoleClaimType = "role",
        };
    }

    public TokenPair CreatePair(Employee employee)
    {
        var now = DateTimeOffset.UtcNow;
        var accessExp = now + AccessLifetime;
        var refreshExp = now + RefreshLifetime;
        return new TokenPair(
            Create(employee, AccessType, now, accessExp), accessExp,
            Create(employee, RefreshType, now, refreshExp), refreshExp);
    }

    /// <summary>Проверяет токен обновления; возвращает id сотрудника или null.</summary>
    public async Task<int?> ValidateRefreshAsync(string token)
    {
        var result = await _handler.ValidateTokenAsync(token, ValidationParameters);
        if (!result.IsValid) return null;
        if (result.ClaimsIdentity.FindFirst(TypeClaim)?.Value != RefreshType) return null;
        return int.TryParse(result.ClaimsIdentity.FindFirst(JwtRegisteredClaimNames.Sub)?.Value, out var id) ? id : null;
    }

    private string Create(Employee employee, string type, DateTimeOffset now, DateTimeOffset expires) =>
        _handler.CreateToken(new SecurityTokenDescriptor
        {
            Issuer = Issuer,
            Audience = Issuer,
            IssuedAt = now.UtcDateTime,
            NotBefore = now.UtcDateTime,
            Expires = expires.UtcDateTime,
            SigningCredentials = _credentials,
            Subject = new ClaimsIdentity(
            [
                new Claim(JwtRegisteredClaimNames.Sub, employee.Id.ToString()),
                new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
                new Claim("role", employee.Role.ToString()),
                new Claim(TypeClaim, type),
            ]),
        });
}
