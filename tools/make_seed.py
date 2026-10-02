"""Генератор тестовых данных приложения «Выездной инженер».

Все организации, адреса, люди и телефоны вымышленные. Структура записей — по проекту
системы (раздел 2.1), уровни SLA и направления работ — по легенде предприятия (6.1).

Сроки заявок считаются тем же алгоритмом, что и SlaCalculator (раздел 8 проекта), с
учётом производственного календаря. Даты заявок отсчитываются от «базовой пятницы» —
дня регистрации контрольной заявки (SLA-2, пятница 16:30), её можно сдвинуть ключом --база.

Запуск:  python make_seed.py [--база ГГГГ-ММ-ДД] [--съёмка ГГГГ-ММ-ДД]
         (база по умолчанию — последняя прошедшая пятница; --съёмка переносит «сегодняшние»
         заявки 6 и 7 с открытыми интервалами на утро дня съёмки, чтобы в показателях не
         набегали десятки часов от пятницы)
Результат: server/ServicePult/SeedData/seed.json — сервер заливает его в пустую базу при старте,
приложение получает данные с сервера синхронизацией.
"""
import argparse
import hashlib
import json
import uuid
from datetime import date, datetime, time, timedelta
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "server" / "ServicePult" / "SeedData" / "seed.json"
MSK = "+03:00"

# ---------------------------------------------------------------- календарь

# Нерабочие праздничные дни по ст. 112 ТК РФ (месяц, день).
HOLIDAYS = [(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8),
            (2, 23), (3, 8), (5, 1), (5, 9), (6, 12), (11, 4)]

# Переносы выходных постановлениями Правительства РФ:
# 2026 — от 24.09.2025 № 1466; 2027 — от 17.09.2026 № 1187.
TRANSFERS = {
    2026: [(date(2026, 1, 3), date(2026, 1, 9)), (date(2026, 1, 4), date(2026, 12, 31))],
    2027: [(date(2027, 1, 2), date(2027, 11, 5)), (date(2027, 1, 3), date(2027, 12, 31)),
           (date(2027, 2, 20), date(2027, 2, 22))],
}


def calendar_exceptions(year):
    """Дни, которые отличаются от правила «пн–пт рабочие»: {дата: (is_working, note)}."""
    ex = {}
    transfers = TRANSFERS[year]
    moved_from = {src for src, _ in transfers}
    for m, d in HOLIDAYS:
        day = date(year, m, d)
        if day.weekday() < 5:
            ex[day] = (False, "Нерабочий праздничный день")
        elif m != 1 and day not in moved_from:
            # ч. 2 ст. 112 ТК РФ: праздник в выходной переносится на следующий рабочий день
            nxt = day + timedelta(days=1)
            while nxt.weekday() >= 5 or nxt in ex:
                nxt += timedelta(days=1)
            ex[nxt] = (False, "Перенос праздника с выходного дня")
    for src, dst in transfers:
        ex[dst] = (False, "Перенос выходного дня")
        if src.weekday() == 5 and (src.month, src.day) not in HOLIDAYS:
            ex[src] = (True, "Рабочая суббота (перенос)")
    return ex


CAL = {}
for y in TRANSFERS:
    CAL.update(calendar_exceptions(y))


def is_working(day):
    if day in CAL:
        return CAL[day][0]
    return day.weekday() < 5


# ---------------------------------------------------------------- расчёт срока

WORK_START, WORK_END = time(9, 0), time(18, 0)


def due(t0, minutes, mode):
    """Срок по алгоритму раздела 8 проекта. mode 1 — 24×7, mode 2 — рабочее время."""
    if minutes <= 0:
        raise ValueError("D <= 0")
    if mode == 1:
        return t0 + timedelta(minutes=minutes)
    t, r = t0, minutes
    while True:
        while not is_working(t.date()) or t.time() >= WORK_END:
            t = datetime.combine(t.date() + timedelta(days=1), WORK_START)
        if t.time() < WORK_START:
            t = datetime.combine(t.date(), WORK_START)
        avail = (datetime.combine(t.date(), WORK_END) - t).seconds // 60
        if r <= avail:
            return t + timedelta(minutes=r)
        r -= avail
        t = datetime.combine(t.date() + timedelta(days=1), WORK_START)


def self_check():
    """Контрольный пример проекта: SLA-2, пятница 16:30, праздников нет."""
    fri = datetime(2026, 10, 2, 16, 30)
    assert due(fri, 240, 2) == datetime(2026, 10, 5, 11, 30)
    assert due(fri, 1440, 2) == datetime(2026, 10, 7, 13, 30)
    assert due(fri, 120, 1) == datetime(2026, 10, 2, 18, 30)
    assert due(fri, 480, 1) == datetime(2026, 10, 3, 0, 30)
    # праздник в понедельник: пятница 08.05.2026, понедельник 11.05 — перенесённый День Победы
    fri_may = datetime(2026, 5, 8, 16, 30)
    assert due(fri_may, 240, 2) == datetime(2026, 5, 12, 11, 30)
    assert due(fri_may, 1440, 2) == datetime(2026, 5, 14, 13, 30)


# ---------------------------------------------------------------- справочники

SLA = [
    {"id": 1, "code": "SLA-1", "name": "Критичный", "reaction_min": 120, "resolution_min": 480, "mode": 1},
    {"id": 2, "code": "SLA-2", "name": "Стандартный", "reaction_min": 240, "resolution_min": 1440, "mode": 2},
    {"id": 3, "code": "SLA-3", "name": "Плановый", "reaction_min": 1080, "resolution_min": 2700, "mode": 2},
]

CUSTOMERS = [
    {"id": 1, "code": "0001", "name": "АО «Энергосеть-Север»", "inn": "7700000011"},
    {"id": 2, "code": "0002", "name": "ООО «Технопарк „Пример“»", "inn": "7700000022"},
    {"id": 3, "code": "0003", "name": "ГБУ «Тестовый центр учёта»", "inn": "7700000033"},
]

CONTRACTS = [
    {"id": 1, "number": "СО-2026-014", "customer_id": 1, "date_from": "2026-01-15", "date_to": "2026-12-31",
     "max_sla": 1, "e_sign_allowed": True},
    {"id": 2, "number": "СО-2026-021", "customer_id": 2, "date_from": "2026-03-01", "date_to": None,
     "max_sla": 2, "e_sign_allowed": True},
    {"id": 3, "number": "СО-2025-087", "customer_id": 3, "date_from": "2025-09-01", "date_to": "2027-08-31",
     "max_sla": 3, "e_sign_allowed": False},
]

SITES = [
    {"id": 1, "code": "0001-01", "customer_id": 1, "address": "г. Москва, Тестовый пр-д, д. 5, стр. 2",
     "contact_name": "Петров Пётр Тестович", "contact_phone": "+7 000 000-00-01"},
    {"id": 2, "code": "0001-02", "customer_id": 1, "address": "Московская обл., г. Условный, ул. Опытная, д. 14",
     "contact_name": "Семёнова Ольга Примеровна", "contact_phone": "+7 000 000-00-02"},
    {"id": 3, "code": "0002-01", "customer_id": 2, "address": "г. Москва, ул. Демонстрационная, д. 21",
     "contact_name": "Кузнецов Андрей Тестович", "contact_phone": "+7 000 000-00-03"},
    {"id": 4, "code": "0002-02", "customer_id": 2, "address": "г. Москва, Проектный б-р, д. 8, корп. 1",
     "contact_name": "Лебедева Мария Условная", "contact_phone": "+7 000 000-00-04"},
    {"id": 5, "code": "0003-01", "customer_id": 3, "address": "г. Москва, пер. Макетный, д. 3",
     "contact_name": "Орлов Игорь Примерович", "contact_phone": "+7 000 000-00-05"},
]

EQUIPMENT = [
    {"id": 1, "inv_code": "BK-EQ-000101", "site_id": 1, "vendor": "Eltex", "model": "MES2324", "serial_no": "TST0000A0101", "warranty_to": "2027-06-30"},
    {"id": 2, "inv_code": "BK-EQ-000102", "site_id": 1, "vendor": "Ippon", "model": "Innova RT 3000", "serial_no": "TST0000B0102", "warranty_to": None},
    {"id": 3, "inv_code": "BK-EQ-000117", "site_id": 2, "vendor": "Aquarius", "model": "T50 D213", "serial_no": "TST0000C0117", "warranty_to": "2026-12-31"},
    {"id": 4, "inv_code": "BK-EQ-000123", "site_id": 4, "vendor": "Eltex", "model": "MES2428", "serial_no": "TST0000D0123", "warranty_to": "2027-03-31"},
    {"id": 5, "inv_code": "BK-EQ-000124", "site_id": 4, "vendor": "Eltex", "model": "ESR-20", "serial_no": "TST0000E0124", "warranty_to": "2027-03-31"},
    {"id": 6, "inv_code": "BK-EQ-000131", "site_id": 3, "vendor": "Aquarius", "model": "Pro T50 S44", "serial_no": "TST0000F0131", "warranty_to": None},
    {"id": 7, "inv_code": "BK-EQ-000140", "site_id": 5, "vendor": "Ippon", "model": "Smart Winner II 1500", "serial_no": "TST0000G0140", "warranty_to": "2026-11-30"},
    {"id": 8, "inv_code": "BK-EQ-000141", "site_id": 5, "vendor": "Yealink", "model": "MeetingBar A20", "serial_no": "TST0000H0141", "warranty_to": "2027-09-30"},
]

# Виды работ: первая цифра кода — направление (легенда 6.1), пункты чек-листа:
# (текст, обязательный, нужно фото)
WORK_TYPES = [
    (1, "101", "Настройка межсетевого экрана", 120, [
        ("Сохранить текущую конфигурацию", True, False),
        ("Применить правила по заявке", True, False),
        ("Проверить доступность сервисов заказчика", True, False),
        ("Сфотографировать индикацию устройства", False, True)]),
    (2, "201", "Диагностика сервера", 90, [
        ("Проверить журналы событий и индикацию", True, False),
        ("Проверить состояние дисков и блоков питания", True, True),
        ("Записать результаты диагностики", True, False)]),
    (3, "302", "Замена коммутатора доступа", 120, [
        ("Сохранить конфигурацию старого коммутатора", True, False),
        ("Установить новый коммутатор в стойку", True, True),
        ("Перенести коммутацию по схеме портов", True, False),
        ("Проверить индикацию портов", True, True),
        ("Наклеить QR-метку на новое устройство", False, False)]),
    (4, "305", "Диагностика линии связи", 60, [
        ("Проверить линию тестером", True, False),
        ("Проверить патч-корды и розетки", True, False),
        ("Сфотографировать место повреждения", False, True)]),
    (5, "401", "Плановое ТО источника бесперебойного питания", 90, [
        ("Внешний осмотр и очистка", True, True),
        ("Проверить состояние батарей", True, False),
        ("Проверить переход на батареи", True, False),
        ("Записать показания в журнал", False, False)]),
    (6, "501", "Настройка системы видеоконференцсвязи", 90, [
        ("Проверить подключение камеры и микрофонов", True, False),
        ("Провести тестовый сеанс связи", True, False),
        ("Сфотографировать установленное оборудование", False, True)]),
]

EMPLOYEES = [
    {"id": 1, "tab_no": "00042", "full_name": "Тестов Иван Петрович", "position": "Инженер выездной службы",
     "role": 1, "phone": "+7 000 000-10-42", "login": "engineer", "blocked": False},
    {"id": 2, "tab_no": "00017", "full_name": "Примерова Анна Сергеевна", "position": "Диспетчер сервисной службы",
     "role": 2, "phone": "+7 000 000-10-17", "login": "dispatcher", "blocked": False},
    {"id": 3, "tab_no": "00003", "full_name": "Условный Олег Викторович", "position": "Руководитель сервисной службы",
     "role": 3, "phone": "+7 000 000-10-03", "login": "head", "blocked": False},
]

# Пароль тестовых учётных записей. В seed.json хранится только хеш: у сотрудника — поле
# pass_hash (его заливает сервер, в телефон оно не передаётся), в разделе accounts — те же
# данные по частям (им пользовалась заглушка входа приложения до появления сервера).
TEST_PASSWORD = "Test2026!"
PBKDF2_ITER = 120_000

# ---------------------------------------------------------------- заявки

# (смещение дня от базовой пятницы, время, sla, договор, объект, оборудование, вид работ,
#  статус, описание, отметки: (смещение, время) прибытия / завершения / закрытия, причина отмены)
REQUESTS = [
    (-9, "10:15", 3, 3, 5, 7, 5, 6, "Плановое ТО ИБП в серверной, 3-й этаж.",
     ((-8, "11:00"), (-8, "13:10"), (-8, "13:25")), None,
     "Проведено ТО, батареи в норме, время автономной работы 14 мин."),
    (-8, "09:40", 2, 2, 3, 6, 2, 6, "Сервер учёта периодически перезагружается.",
     ((-8, "12:05"), (-8, "14:30"), (-8, "14:40")), None,
     "Заменён блок питания из ЗИП заказчика, сервер работает стабильно."),
    (-8, "15:00", 1, 1, 1, 1, 3, 7, "Нет связи на этаже, не работает коммутатор.",
     (None, None, None), "Заказчик восстановил питание коммутатора сам", None),
    (-4, "11:20", 3, 3, 5, 8, 6, 8, "Не работает камера в переговорной.",
     ((-3, "10:00"), None, None), None, "Требуется замена камеры, ожидание поставки."),
    (-1, "14:10", 2, 2, 3, None, 4, 5, "Пропадает сеть в бухгалтерии, 2 кабинета.",
     ((0, "09:40"), (0, "10:30"), None), None, "Переобжат патч-корд, заменена розетка в каб. 204."),
    # 6-я и 7-я — заявки «сегодняшнего» дня (С — день съёмки, ключ --съёмка; по умолчанию — база)
    ("С", "07:40", 1, 1, 2, 3, 1, 4, "После обновления межсетевого экрана недоступен сайт заказчика.",
     (("С", "08:35"), None, None), None, None),
    ("С", "08:10", 2, 2, 3, None, 4, 3, "Медленная сеть в конференц-зале.",
     (None, None, None), None, None),
    # контрольная заявка (раздел 8 проекта)
    (0, "16:30", 2, 2, 4, 4, 3, 2, "Вышел из строя коммутатор доступа в серверной, часть рабочих мест без сети.",
     (None, None, None), None, None),
    (0, "17:10", 3, 3, 5, 7, 5, 2, "Плановое ТО второго ИБП.",
     (None, None, None), None, None),
    (0, "17:40", 1, 1, 1, 2, 5, 2, "ИБП подаёт звуковой сигнал, работает от батарей.",
     (None, None, None), None, None),
]
CONTROL_INDEX = 7
CONTROL_NUMBER = 137


SHOOT = None  # день съёмки (ключ --съёмка); None — совпадает с базой


def at(base, offset, hhmm):
    h, m = map(int, hhmm.split(":"))
    day = (SHOOT or base) if offset == "С" else base + timedelta(days=offset)
    return datetime.combine(day, time(h, m))


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:00") + MSK if dt else None


def build(base):
    sla_by_id = {s["id"]: s for s in SLA}
    control_month = at(base, REQUESTS[CONTROL_INDEX][0], "00:00").strftime("%y%m")
    # номера по порядку регистрации; в месяце контрольной заявки подогнаны так, чтобы она была № 0137
    regs = [at(base, r[0], r[1]) for r in REQUESTS]
    control_reg = regs[CONTROL_INDEX]
    counters, numbers = {}, {}
    for i in sorted(range(len(REQUESTS)), key=lambda k: regs[k]):
        month = regs[i].strftime("%y%m")
        if month not in counters:
            before = sum(1 for t in regs if t < control_reg and t.strftime("%y%m") == month)
            counters[month] = CONTROL_NUMBER - before if month == control_month else 101
        numbers[i] = counters[month]
        counters[month] += 1
    requests = []
    for i, r in enumerate(REQUESTS):
        (off, hhmm, sla_id, contract_id, site_id, eq_id, wt_id, status, descr,
         marks, cancel, result) = r
        reg = regs[i]
        sla = sla_by_id[sla_id]
        month = reg.strftime("%y%m")
        num = numbers[i]
        arrived, completed, closed = (at(base, *m) if m else None for m in marks)
        requests.append({
            "id": i + 1, "number": f"З-{month}-{num:04d}", "contract_id": contract_id, "site_id": site_id,
            "equipment_id": eq_id, "work_type_id": wt_id, "sla_level_id": sla_id,
            "dispatcher_id": 2, "engineer_id": 1, "parent_id": None, "description": descr,
            "registered_at": iso(reg),
            "reaction_due": iso(due(reg, sla["reaction_min"], sla["mode"])),
            "resolution_due": iso(due(reg, sla["resolution_min"], sla["mode"])),
            "arrived_at": iso(arrived), "completed_at": iso(completed), "closed_at": iso(closed),
            "status": status, "scanned_code": None, "result_text": result, "cancel_reason": cancel,
            "time_trusted": True,
        })
    # заявки проверяются на соответствие договору
    contracts = {c["id"]: c for c in CONTRACTS}
    sites = {s["id"]: s for s in SITES}
    eq = {e["id"]: e for e in EQUIPMENT}
    for rq in requests:
        c = contracts[rq["contract_id"]]
        assert sites[rq["site_id"]]["customer_id"] == c["customer_id"], rq["number"]
        assert rq["sla_level_id"] >= c["max_sla"], rq["number"]
        if rq["equipment_id"]:
            assert eq[rq["equipment_id"]]["site_id"] == rq["site_id"], rq["number"]
    return requests


def last_friday(today):
    return today - timedelta(days=(today.weekday() - 4) % 7)


def uid(*parts):
    # UUID для записей, которые в жизни создал бы телефон; стабильный между запусками
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "field-engineer-seed/" + "/".join(map(str, parts))))


# Интервалы работы по начатым и закрытым заявкам: (номер заявки в REQUESTS с 1, вид, начало, конец)
# вид 1 — дорога, 2 — работа; конец None — интервал идёт
WORK_LOG = [
    (1, 1, (-8, "10:15"), (-8, "11:00")), (1, 2, (-8, "11:00"), (-8, "13:10")),
    (2, 1, (-8, "11:20"), (-8, "12:05")), (2, 2, (-8, "12:05"), (-8, "14:30")),
    (4, 1, (-3, "09:15"), (-3, "10:00")), (4, 2, (-3, "10:00"), (-3, "11:30")),
    (5, 1, (0, "09:00"), (0, "09:40")), (5, 2, (0, "09:40"), (0, "10:30")),
    (6, 1, ("С", "07:50"), ("С", "08:35")), (6, 2, ("С", "08:35"), None),
    (7, 1, ("С", "09:50"), None),
]


def work_logs(base):
    return [{"id": uid("work_log", r, n), "request_id": r, "employee_id": 1, "kind": kind,
             "started_at": iso(at(base, *start)), "ended_at": iso(at(base, *end)) if end else None,
             "time_trusted": True}
            for n, (r, kind, start, end) in enumerate(WORK_LOG, 1)]


def checklist_results(base, items):
    # у заявок с выполненными работами (статусы 5 и 6) отмечены все обязательные пункты;
    # у заявки «на объекте» (6-я) — первый пункт
    res = []
    for r_no, r in enumerate(REQUESTS, 1):
        status, marks = r[7], r[9]
        wt_items = [i for i in items if i["work_type_id"] == r[6]]
        if status in (5, 6):
            chosen, when = [i for i in wt_items if i["required"]], at(base, *marks[1])
        elif r_no == 6:
            chosen, when = wt_items[:1], at(base, "С", "08:55")
        else:
            continue
        for i in chosen:
            res.append({"id": uid("checklist_result", r_no, i["id"]), "request_id": r_no,
                        "checklist_item_id": i["id"], "done": True, "comment": None, "done_at": iso(when)})
    return res


def password_parts(login):
    """Соль и хеш PBKDF2-HMAC-SHA256 пароля; соль стабильна между запусками."""
    salt = hashlib.sha256(("seed-salt-" + login).encode()).digest()[:16]
    h = hashlib.pbkdf2_hmac("sha256", TEST_PASSWORD.encode(), salt, PBKDF2_ITER)
    return salt, h


def accounts():
    res = []
    for e in EMPLOYEES:
        salt, h = password_parts(e["login"])
        res.append({"login": e["login"], "salt": salt.hex(), "iterations": PBKDF2_ITER, "hash": h.hex()})
    return res


def employees():
    """Сотрудники с pass_hash в формате сервера: pbkdf2-sha256$<итерации>$<соль hex>$<хеш hex>."""
    res = []
    for e in EMPLOYEES:
        salt, h = password_parts(e["login"])
        res.append({**e, "pass_hash": f"pbkdf2-sha256${PBKDF2_ITER}${salt.hex()}${h.hex()}"})
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--база", dest="base", default=None, help="пятница контрольной заявки, ГГГГ-ММ-ДД")
    ap.add_argument("--съёмка", dest="shoot", default=None,
                    help="день съёмки для заявок 6 и 7 (утро этого дня), ГГГГ-ММ-ДД; не раньше базы")
    args = ap.parse_args()
    base = date.fromisoformat(args.base) if args.base else last_friday(date.today())
    if base.weekday() != 4:
        raise SystemExit("Базовая дата должна быть пятницей")
    global SHOOT
    SHOOT = date.fromisoformat(args.shoot) if args.shoot else None
    if SHOOT and SHOOT < base:
        raise SystemExit("День съёмки не может быть раньше базы")
    self_check()

    work_types, items = [], []
    for wt_id, code, name, norm, checklist in WORK_TYPES:
        work_types.append({"id": wt_id, "code": code, "name": name, "norm_min": norm})
        for pos, (text, req, photo) in enumerate(checklist, 1):
            items.append({"id": len(items) + 1, "work_type_id": wt_id, "pos": pos, "text": text,
                          "required": req, "need_photo": photo})

    seed = {
        "generated_for_base": base.isoformat(),
        "customer": CUSTOMERS, "contract": CONTRACTS, "sla_level": SLA, "site": SITES,
        "equipment": EQUIPMENT, "work_type": work_types, "checklist_item": items,
        "employee": employees(),
        "calendar_day": [{"day": d.isoformat(), "is_working": w, "note": n} for d, (w, n) in sorted(CAL.items())],
        "request": build(base),
        "checklist_result": checklist_results(base, items),
        "work_log": work_logs(base),
        "accounts": accounts(),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(seed, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{OUT} — заявок {len(seed['request'])}, дней календаря {len(seed['calendar_day'])}")


if __name__ == "__main__":
    main()
