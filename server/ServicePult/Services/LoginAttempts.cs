using Microsoft.Extensions.Caching.Memory;

namespace ServicePult.Services;

/// <summary>
/// Счётчик неудачных входов в памяти сервера: 5 ошибок подряд по одному логину закрывают вход
/// на 15 минут. При перезапуске сервера счётчик сбрасывается (раздел 10 проекта).
///
/// Попытка занимается ДО проверки пароля (TryStart) и под одной блокировкой: иначе 30 одновременных
/// запросов успели бы пройти проверку «вход не закрыт» раньше, чем первый из них отметит ошибку,
/// и пароль проверился бы 30 раз вместо 5. Успешный вход счётчик сбрасывает (Reset).
/// </summary>
public class LoginAttempts(IMemoryCache cache)
{
    public const int MaxFailures = 5;
    public static readonly TimeSpan LockTime = TimeSpan.FromMinutes(15);

    private readonly object _sync = new();

    private class Entry
    {
        /// <summary>Попытки с неверным паролем и ещё не завершённые попытки.</summary>
        public int Attempts;
        public DateTimeOffset? LockedUntil;
    }

    private static string Key(string login) => "login-fail:" + login.Trim().ToLowerInvariant();

    /// <summary>
    /// Занимает попытку входа. false — вход закрыт: либо блокировка после 5 ошибок, либо
    /// 5 попыток уже идут одновременно. Тогда пароль не проверяется вовсе.
    /// </summary>
    public bool TryStart(string login)
    {
        lock (_sync)
        {
            var now = DateTimeOffset.UtcNow;
            var entry = cache.Get<Entry>(Key(login)) ?? new Entry();
            if (entry.LockedUntil > now) return false;
            if (entry.LockedUntil != null) entry = new Entry(); // блокировка истекла — счёт заново
            if (entry.Attempts >= MaxFailures) return false;
            entry.Attempts++;
            // запись живёт, пока идёт блокировка или серия ошибок (не дольше срока блокировки)
            cache.Set(Key(login), entry, LockTime);
            return true;
        }
    }

    /// <summary>Отмечает, что занятая попытка оказалась ошибкой; возвращает true, если вход теперь закрыт.</summary>
    public bool RegisterFailure(string login)
    {
        lock (_sync)
        {
            var entry = cache.Get<Entry>(Key(login));
            if (entry == null) return false; // запись уже истекла
            if (entry.Attempts >= MaxFailures && entry.LockedUntil == null)
            {
                entry.LockedUntil = DateTimeOffset.UtcNow + LockTime;
                cache.Set(Key(login), entry, LockTime);
            }
            return entry.LockedUntil > DateTimeOffset.UtcNow;
        }
    }

    /// <summary>Успешный вход: счётчик ошибок сбрасывается.</summary>
    public void Reset(string login)
    {
        lock (_sync) cache.Remove(Key(login));
    }
}
