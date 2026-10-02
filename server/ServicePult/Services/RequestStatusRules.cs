using ServicePult.Data;

namespace ServicePult.Services;

/// <summary>
/// Смена статуса заявки инженером (проект, раздел 3): только вперёд по цепочке
/// 2 «Назначена» → 3 «В пути» → 4 «На объекте» → 5 «Работы выполнены» (или 8 «Приостановлена» из 4).
/// Приложение работает без связи, поэтому за одну отправку заявка может пройти несколько шагов
/// (например, 2 → 4): такой переход допустим, если нужные отметки времени есть в записи.
/// Отменяет заявку (7) только диспетчер; закрытие (6) проверяется отдельно — по акту.
/// </summary>
public static class RequestStatusRules
{
    public static string Title(short status) => status switch
    {
        RequestStatus.New => "Новая",
        RequestStatus.Assigned => "Назначена",
        RequestStatus.OnTheWay => "В пути",
        RequestStatus.OnSite => "На объекте",
        RequestStatus.WorkDone => "Работы выполнены",
        RequestStatus.Closed => "Закрыта",
        RequestStatus.Cancelled => "Отменена",
        RequestStatus.Suspended => "Приостановлена",
        _ => $"статус {status}",
    };

    /// <summary>Причина отказа или null, если инженер может перевести заявку из from в to.</summary>
    public static string? CheckTransition(short from, short to)
    {
        if (to == from) return null;
        var move = $"«{Title(from)}» → «{Title(to)}»";
        if (from == RequestStatus.New) return "Заявка ещё не назначена инженеру";
        if (from == RequestStatus.Suspended)
            return "Приостановленная заявка не меняется: продолжение — повторным выездом";
        if (to == RequestStatus.Cancelled) return "Отменить заявку может только диспетчер";
        if (to is < RequestStatus.OnTheWay or > RequestStatus.Suspended || to == RequestStatus.Closed)
            return $"Переход {move} недопустим";
        if (to == RequestStatus.Suspended)
            return from <= RequestStatus.OnSite ? null : $"Переход {move} недопустим: приостановить можно только на объекте";
        return to > from ? null : $"Переход {move} недопустим: статус меняется только вперёд";
    }
}
