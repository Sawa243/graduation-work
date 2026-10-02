using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace ServicePult.Services;

/// <summary>
/// Пароли хранятся в employee.pass_hash строкой pbkdf2-sha256$итерации$соль hex$хеш hex
/// (PBKDF2-HMAC-SHA256, 32 байта) — тот же расчёт, что в генераторе тестовых данных.
/// </summary>
public static class PasswordHasher
{
    private const string Prefix = "pbkdf2-sha256";
    private const int HashBytes = 32;
    public const int DefaultIterations = 120_000;

    /// <summary>Хеш для нового пароля со случайной солью.</summary>
    public static string Hash(string password, int iterations = DefaultIterations)
    {
        var salt = RandomNumberGenerator.GetBytes(16);
        var hash = Derive(password, salt, iterations);
        return $"{Prefix}${iterations}${Convert.ToHexString(salt).ToLowerInvariant()}${Convert.ToHexString(hash).ToLowerInvariant()}";
    }

    /// <summary>Проверка пароля; результаты сравниваются за постоянное время.</summary>
    public static bool Verify(string password, string stored)
    {
        var parts = stored.Split('$');
        if (parts.Length != 4 || parts[0] != Prefix
            || !int.TryParse(parts[1], NumberStyles.None, CultureInfo.InvariantCulture, out var iterations)
            || iterations <= 0)
            return false;
        byte[] salt, expected;
        try
        {
            salt = Convert.FromHexString(parts[2]);
            expected = Convert.FromHexString(parts[3]);
        }
        catch (FormatException)
        {
            return false;
        }
        var actual = Derive(password, salt, iterations);
        return CryptographicOperations.FixedTimeEquals(actual, expected);
    }

    private static byte[] Derive(string password, byte[] salt, int iterations) =>
        Rfc2898DeriveBytes.Pbkdf2(Encoding.UTF8.GetBytes(password), salt, iterations, HashAlgorithmName.SHA256, HashBytes);
}
