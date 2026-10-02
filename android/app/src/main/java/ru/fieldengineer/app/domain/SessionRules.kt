package ru.fieldengineer.app.domain

/** Правила входа: что делать с данными, которые уже лежат в телефоне. */
object SessionRules {

    enum class LocalData {
        /** Вошёл тот же сотрудник — его неотправленные записи уйдут на сервер. */
        KEEP,
        /** Очистить базу и файлы перед загрузкой данных нового сотрудника. */
        CLEAR,
        /** В очереди неотправленные записи другого сотрудника — сначала спросить. */
        ASK,
    }

    /**
     * [previous] — кто работал в телефоне (null — неизвестно: выход прервался или первый вход),
     * [incoming] — кто входит, [unsent] — сколько записей ждут отправки.
     * Если владелец данных неизвестен, данные всё равно очищаются: чужое не смешается с новым.
     */
    fun beforeLogin(previous: Int?, incoming: Int, unsent: Int): LocalData = when {
        previous == incoming -> LocalData.KEEP
        previous != null && unsent > 0 -> LocalData.ASK
        else -> LocalData.CLEAR
    }
}
