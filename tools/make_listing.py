"""Листинг основных расчётных модулей для приложения 3 ВКР.

Считает операторы в расчётных модулях приложения (Kotlin) и сервера (C#), печатает
таблицу и пишет tools/listing/листинг.txt — текст модулей для вставки в Word
(Courier New 10 на 170 мм — 80 символов в строке, проверено печатью в Word).

Правило подсчёта операторов (формулировка для защиты):
    Оператор — это законченная инструкция языка: объявление класса, функции, свойства
    (в том числе каждое val/var в скобках конструктора), переменной или константы,
    присваивание, вызов, return/throw, ветка when и заголовок if/else if/for/while, а тело
    короткого if/else/цикла, записанное без фигурных скобок, — отдельный оператор. Инструкция, растянутая на несколько строк (параметры
    в скобках, цепочка вызовов, длинное выражение), — один оператор, несколько инструкций
    через «;» — несколько; пустые строки, комментарии, import/package/using/namespace и
    строки из одних скобок не считаются.

Как это реализовано:
    1. Из текста убираются комментарии (//, /* */, ///, KDoc), а содержимое строковых
       литералов заменяется на "" — поэтому // и ; внутри строк не мешают подсчёту.
       Учитываются шаблоны Kotlin "${...}", строки C# $"...{...}",
       @"..." и строки в тройных кавычках.
    2. Строки склеиваются в инструкции: пока открыта круглая или квадратная скобка;
       в Kotlin — ещё когда строка кончается знаком операции, запятой, «=», «->» или
       заголовком if (...) без тела, либо следующая начинается с «.», «?:», «&&», «else»
       и т. п.; в C# инструкция идёт до «;».
    3. Фигурная скобка, открытая и закрытая в той же строке (лямбда { it.x }, enum в одну
       строку), — часть инструкции. Скобка в конце строки открывает блок: текст перед ней
       (заголовок класса, функции, if, цикла) — один оператор, содержимое блока считается
       по тем же правилам.
    4. Каждая инструкция = 1 оператор; если она начинается с if/for/while (...) и тело
       записано сразу после условия, тело считается отдельно (+1, и +1 за ветку else).
       Одиночное «else» перед блоком оператором не считается — это часть if.
    5. К объявлению класса добавляется по оператору на каждое свойство из скобок
       основного конструктора: val/var в Kotlin, позиционный параметр record в C#.
       Параметры функций и конструктора C#-класса свойствами не являются и не считаются.
       Константы enum, перечисленные через запятую, — один оператор.

Запуск:
    python make_listing.py                      — таблица и tools/listing/листинг.txt
    python make_listing.py --проверка ФАЙЛ      — построчная разметка: сколько операторов
                                                  насчитано на каждой строке (счёт приписан
                                                  строке, где инструкция начинается)
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = Path(__file__).resolve().parent / "listing"
OUT_FILE = OUT_DIR / "листинг.txt"
LINE_WIDTH = 80

DOMAIN = "android/app/src/main/java/ru/fieldengineer/app/domain/"
MODULES = [
    (DOMAIN + "SlaCalculator.kt", "расчёт сроков по SLA"),
    (DOMAIN + "WorkTimeCalculator.kt", "расчёт трудозатрат по интервалам работы"),
    (DOMAIN + "RequestStatusMachine.kt", "машина статусов заявки"),
    (DOMAIN + "ActBuilder.kt", "формирование акта выполненных работ"),
    (DOMAIN + "BarcodeAnalyzer.kt", "разбор кода оборудования"),
    ("server/ServicePult/Services/SlaService.cs", "расчёт сроков по SLA на сервере"),
    (DOMAIN + "PeriodStats.kt", "показатели за период"),
    # Запас, если нужно добрать до 400 операторов:
    (DOMAIN + "TrustedClock.kt", "доверенное время фактов реакции и устранения"),
    (DOMAIN + "AppLockTimer.kt", "таймер блокировки приложения"),
]

# Строки, которые не считаются и не попадают в листинг.
HEADER_LINE = re.compile(
    r"^\s*(import\s|package\s|namespace\s|global\s+using\s|using\s+(static\s+)?[\w.]+\s*(=\s*[\w.<>, ]+)?\s*;)"
)


def language(path: Path) -> str:
    return "cs" if path.suffix == ".cs" else "kt"


# ---------------------------------------------------------------- 1. очистка текста

def strip_code(text: str, lang: str) -> list[str]:
    """Возвращает строки файла без комментариев и с пустыми строковыми литералами ("").

    Число строк сохраняется, чтобы счёт можно было привязать к номеру строки.
    """
    out = []
    n = len(text)
    i = 0
    # Стек режимов: ("code", глубина {}) — код (на вершине — основной код или код внутри
    # шаблона строки); ("str", вид) — внутри строкового литерала.
    stack = [("code", 0)]

    def top_level() -> bool:
        return len(stack) == 1

    while i < n:
        c = text[i]
        mode, info = stack[-1]
        if mode == "code":
            nxt = text[i + 1] if i + 1 < n else ""
            if c == "/" and nxt == "/":                       # комментарий до конца строки
                while i < n and text[i] != "\n":
                    i += 1
                continue
            if c == "/" and nxt == "*":                       # блочный комментарий
                end = text.find("*/", i + 2)
                end = n if end < 0 else end + 2
                out.append("\n" * text.count("\n", i, end))
                i = end
                continue
            kind = None
            if text.startswith('"""', i):
                kind, width = "raw", 3
            elif lang == "cs" and text.startswith(('$@"', '@$"'), i):
                kind, width = "verbatim_interp", 3
            elif lang == "cs" and text.startswith('@"', i):
                kind, width = "verbatim", 2
            elif lang == "cs" and text.startswith('$"', i):
                kind, width = "interp", 2
            elif c == '"':
                kind, width = "plain", 1
            if kind:
                if top_level():
                    out.append('""')         # литерал целиком заменяется на ""
                stack.append(("str", kind))
                i += width
                continue
            if c == "'":                                      # символьный литерал
                j = i + 1
                while j < n and text[j] != "'" and text[j] != "\n":
                    j += 2 if text[j] == "\\" else 1
                if top_level():
                    out.append("''")
                i = j + 1
                continue
            if not top_level():                               # код внутри шаблона строки
                if c == "{":
                    stack[-1] = ("code", info + 1)
                elif c == "}":
                    if info == 1:
                        stack.pop()
                    else:
                        stack[-1] = ("code", info - 1)
                i += 1
                continue
            out.append(c)
            i += 1
            continue

        # внутри строкового литерала
        kind = info
        if c == "\n":
            out.append("\n")
            i += 1
            continue
        if kind == "raw":
            if text.startswith('"""', i):
                j = i + 3
                while j < n and text[j] == '"':               # """ может кончаться на """"
                    j += 1
                stack.pop()
                i = j
                continue
        elif kind in ("verbatim", "verbatim_interp"):
            if text.startswith('""', i):
                i += 2
                continue
            if c == '"':
                stack.pop()
                i += 1
                continue
        else:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                stack.pop()
                i += 1
                continue
        if lang == "kt" and text.startswith("${", i):          # шаблон Kotlin
            stack.append(("code", 1))
            i += 2
            continue
        if lang == "cs" and kind in ("interp", "verbatim_interp") and c == "{":
            if text.startswith("{{", i):
                i += 2
                continue
            stack.append(("code", 1))
            i += 1
            continue
        i += 1
    return "".join(out).split("\n")


# ---------------------------------------------------------------- 2. разбиение на инструкции

KT_CONT_END = ("=", "+", "-", "*", "/", "%", "&&", "||", "?:", ".", ",", "(", "[", "->", ":", "!", "?")
KT_CONT_START = (".", "?.", "?:", "&&", "||", "+", "*", "/", "%", "=", "!=", ":", "?", ")", "]")


def matching_paren(s: str, start: int) -> int:
    """Индекс скобки, закрывающей s[start] == '(' (или -1)."""
    depth = 0
    for k in range(start, len(s)):
        if s[k] in "([":
            depth += 1
        elif s[k] in ")]":
            depth -= 1
            if depth == 0:
                return k
    return -1


CONTROL = re.compile(r"(?:^|(?<=[^\w.]))(if|while|for|foreach)\s*\(")


def ends_with_control_header(s: str) -> bool:
    """Kotlin: строка кончается заголовком if (...) / while (...) без тела."""
    for m in CONTROL.finditer(s):
        close = matching_paren(s, m.end() - 1)
        if close == len(s) - 1:
            return True
    return s.endswith("else") and (len(s) == 4 or not (s[-5].isalnum() or s[-5] == "_"))


def kt_continues(prev: str, line: str) -> bool:
    if prev.endswith(KT_CONT_END) and not prev.endswith(("++", "--")):
        return True
    if ends_with_control_header(prev):
        return True
    if line.startswith(KT_CONT_START):
        return True
    if line.startswith("-") and not line.startswith(("->", "--")):
        return True
    if re.match(r"else\b(?!\s*->)", line) or re.match(r"as\??\s", line):
        return True
    return False


def statements(stripped: list[str], lang: str) -> list[tuple[int, str]]:
    """Список инструкций (номер строки начала, текст инструкции)."""
    result = []
    cur, cur_start = [], 0
    pd = 0             # глубина круглых и квадратных скобок
    inline = 0         # глубина фигурных скобок внутри инструкции (лямбды, enum в строку)

    def finish():
        nonlocal cur
        text = "".join(cur).strip()
        if text and re.search(r"[\w\"']", text):
            result.append((cur_start, text))
        cur = []

    for num, raw in enumerate(stripped, 1):
        line = raw.strip()
        if not line or HEADER_LINE.match(raw):
            continue
        prev = "".join(cur).strip()
        if prev and pd == 0 and inline == 0:
            # C#: инструкция идёт до «;»; исключение — объявление, целиком записанное
            # в строку с фигурными скобками (enum { A, B }), после него «;» нет.
            if lang == "cs":
                joined = not prev.endswith("}")
            else:
                joined = kt_continues(prev, line)
            if not joined:
                finish()
        for j, c in enumerate(line):
            if c in "([":
                pd += 1
            elif c in ")]":
                pd = max(0, pd - 1)
            elif c == "{":
                if pd > 0 or inline > 0 or closes_on_line(line, j):
                    inline += 1
                else:                       # скобка открывает блок: заголовок — оператор
                    finish()
                    continue
            elif c == "}":
                if inline > 0:
                    inline -= 1
                else:                       # конец блока
                    finish()
                    continue
            elif c == ";" and pd == 0 and inline == 0:
                finish()
                continue
            if not cur and c.isspace():
                continue
            if not cur:
                cur_start = num
            cur.append(c)
        if cur:
            cur.append(" ")
    finish()
    return result


def closes_on_line(line: str, j: int) -> bool:
    depth = 0
    for k in range(j, len(line)):
        if line[k] == "{":
            depth += 1
        elif line[k] == "}":
            depth -= 1
            if depth == 0:
                return True
    return False


# ---------------------------------------------------------------- 3. счёт операторов

def split_else(body: str) -> tuple[str, str | None]:
    """Делит «тело else ветка» по else верхнего уровня."""
    depth = 0
    for m in re.finditer(r"[()\[\]{}]|\belse\b", body):
        t = m.group()
        if t in "([{":
            depth += 1
        elif t in ")]}":
            depth -= 1
        elif depth == 0:
            return body[:m.start()], body[m.end():]
    return body, None


def count_statement(s: str) -> int:
    s = s.strip()
    if not s or not re.search(r"[\w\"']", s):
        return 0
    if re.match(r"else\s*->", s):                   # ветка when «else ->»
        return 1
    m = re.match(r"else\b", s)
    if m:                                           # одиночное else — часть if
        return count_statement(s[m.end():])
    m = re.match(r"(if|while|for|foreach)\s*\(", s)
    if m:
        close = matching_paren(s, m.end() - 1)
        if close < 0:
            return 1
        rest = s[close + 1:]
        if m.group(1) == "if":
            body, other = split_else(rest)
            return 1 + count_statement(body) + (count_statement(other) if other is not None else 0)
        return 1 + count_statement(rest)
    return 1 + constructor_properties(s)


KT_CLASS = re.compile(r"(?:[a-z]+\s+)*class\s+\w+\s*(?:<[^>]*>\s*)?\(")
CS_RECORD = re.compile(r"(?:[a-z]+\s+)*record\s+(?:class\s+|struct\s+)?\w+\s*(?:<[^>]*>\s*)?\(")


def constructor_properties(s: str) -> int:
    """Свойства, объявленные в скобках основного конструктора: val/var в Kotlin,
    позиционные параметры record в C#. Каждое — объявление свойства, т. е. оператор."""
    m = KT_CLASS.match(s) or CS_RECORD.match(s)
    if not m:
        return 0
    close = matching_paren(s, m.end() - 1)
    params = s[m.end():close if close > 0 else len(s)]
    if "record" in s[:m.end()].split():
        depth, count = 0, 1 if params.strip() else 0
        for c in params:
            depth += c in "(<[" and 1 or 0
            depth -= c in ")>]" and 1 or 0
            if c == "," and depth == 0:
                count += 1
        return count
    return len(re.findall(r"\b(?:val|var)\s", params))


# ---------------------------------------------------------------- 4. анализ файла

def analyze(path: Path) -> dict:
    text = path.read_text(encoding="utf-8-sig")
    lang = language(path)
    lines = text.splitlines()
    stripped = strip_code(text, lang)
    per_line = [0] * (len(stripped) + 1)
    for start, stmt in statements(stripped, lang):
        per_line[start] += count_statement(stmt)
    code_lines = sum(1 for s in stripped if s.strip() and not HEADER_LINE.match(s))
    return {
        "lines": lines,
        "total_lines": len(lines),
        "code_lines": code_lines,
        "per_line": per_line,
        "operators": sum(per_line),
    }


def listing_lines(lines: list[str]) -> list[str]:
    """Текст для листинга: без import/package/using/namespace, табы → 4 пробела,
    без повторных пустых строк."""
    body = []
    for line in lines:
        if HEADER_LINE.match(line):
            continue
        line = line.replace("\t", "    ").rstrip()
        if not line and (not body or not body[-1]):
            continue
        body.append(line)
    while body and not body[-1]:
        body.pop()
    return body


def check(path: Path) -> None:
    info = analyze(path)
    for num, line in enumerate(info["lines"], 1):
        mark = str(info["per_line"][num]) if info["per_line"][num] else "."
        print(f"{num:4} {mark:>2} | {line}")
    print(f"\nИтого операторов: {info['operators']}")


def main() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except AttributeError:
        pass
    parser = argparse.ArgumentParser(description="Листинг расчётных модулей для приложения 3 ВКР")
    parser.add_argument("--проверка", "--check", dest="check", metavar="ФАЙЛ",
                        help="показать для файла счёт операторов по строкам")
    args = parser.parse_args()

    if args.check:
        path = Path(args.check)
        if not path.is_absolute() and not path.exists():
            path = ROOT / args.check
        check(path)
        return

    rows, parts = [], []
    total_ops = total_lines = total_code = total_long = 0
    for n, (rel, title) in enumerate(MODULES, 1):
        path = ROOT / rel
        info = analyze(path)
        body = listing_lines(info["lines"])
        long_lines = sum(1 for line in body if len(line) > LINE_WIDTH)
        rows.append((path.name, info["total_lines"], info["code_lines"], info["operators"], long_lines))
        total_ops += info["operators"]
        total_lines += info["total_lines"]
        total_code += info["code_lines"]
        total_long += long_lines
        parts.append(f"Листинг {n} — {path.name} ({title}), операторов: {info['operators']}")
        parts.append("")
        parts.extend(body)
        parts.append("")
        parts.append("")

    OUT_DIR.mkdir(exist_ok=True)
    # байтами: write_text в текстовом режиме на Windows превратил бы \r\n в \r\r\n
    OUT_FILE.write_bytes(("\r\n".join(parts).rstrip() + "\r\n").encode("utf-8"))

    head = f"{'Файл':<26}{'строк':>7}{'кода':>7}{'операторов':>12}{'> ' + str(LINE_WIDTH) + ' симв.':>12}"
    print(head)
    print("-" * len(head))
    for name, lines, code, ops, long_lines in rows:
        print(f"{name:<26}{lines:>7}{code:>7}{ops:>12}{long_lines:>12}")
    print("-" * len(head))
    print(f"{'Итого':<26}{total_lines:>7}{total_code:>7}{total_ops:>12}{total_long:>12}")
    print(f"\nЛистинг: {OUT_FILE}")
    if total_long:
        print(f"Строк длиннее {LINE_WIDTH} символов: {total_long} — в Word они перенесутся.")


if __name__ == "__main__":
    main()
