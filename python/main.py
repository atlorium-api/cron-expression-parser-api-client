"""
Клиент API разбора cron-выражений Atlorium — валидация расписания и ближайшие запуски.

Запуск (работает сразу, без регистрации — на демо-ключе):
    pip install -r requirements.txt
    python main.py
    python main.py "* 9 * * *"

Программа задумана как ПРОВЕРКА для CI, а не как «печаталка JSON»: она возвращает
ненулевой код выхода, если выражение невалидно или расписание подозрительно частое.

    0 — OK: реже, чем раз в 60 минут
    1 — ВНИМАНИЕ: чаще раза в час — убедитесь, что так и задумано
    2 — ОПАСНО: чаще раза в 5 минут — почти наверняка опечатка
    3 — выражение невалидно или проверку выполнить не удалось (ошибка API или сети)

Часовой пояс берётся из переменной окружения CRON_TZ (по умолчанию Europe/Moscow).

Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
ATLORIUM_API_KEY. Код при этом не меняется.
"""

import os
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime

import requests

# Публичный демо-ключ. ВАЖНО: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок.
# Разбор cron — чистая локальная логика, внешних источников нет,
# и мокать тут просто нечего. Ответ помечен заголовком X-Atlorium-Sandbox: true, но
# расписание в нём подлинное — примерам из README можно верить как есть.
SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1"

API_KEY = os.environ.get("ATLORIUM_API_KEY", SANDBOX_KEY)
BASE_URL = os.environ.get("ATLORIUM_BASE_URL", "https://atlorium.com")

# Часовой пояс расписания. IANA-идентификатор: Europe/Moscow, America/New_York, UTC.
TIME_ZONE = os.environ.get("CRON_TZ", "Europe/Moscow")

TIMEOUT = 30

# ── Пороги вердикта ──────────────────────────────────────────────────────────
# Сервер честно считает расписание, но НЕ оценивает его разумность: на «* 9 * * *»
# он возвращает isValid=true и пустой warnings. Значит, вердикт выносит клиент.
#
# 5 минут — граница «задача практически не прекращает работать»: типовая опечатка
# `* 9 * * *` вместо `0 9 * * *` даёт 60 запусков в час вместо одного.
# 60 минут — граница «чаще раза в час»: бывает нужно, но стоит перепроверить.
DANGER_MINUTES = 5
WARNING_MINUTES = 60

# Сколько ближайших запусков запрашивать. Двух хватило бы для интервала, но пять
# наглядно показывают человеку, что расписание действительно то, которое он задумал.
TAKE = 5

# Расписание по умолчанию: каждый будний день в 9 утра.
DEFAULT_EXPRESSION = "0 9 * * 1-5"

# 429 — повтор один раз с паузой.
RETRY_DELAY = 20
MAX_RETRIES = 1

# Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно просит подождать десятки
# минут — и клиент, слепо доверяющий Retry-After, зависнет на всё это время (а в CI
# просто съест бюджет джоба). Дольше потолка не ждём: честно сообщаем, что квота
# исчерпана, и выходим.
MAX_RETRY_DELAY = 120

# Уровни вердикта. Значение уровня — это и есть код выхода программы.
OK, WARNING, DANGER, FAILED = 0, 1, 2, 3
LEVEL_NAMES = {OK: "OK", WARNING: "ВНИМАНИЕ", DANGER: "ОПАСНО", FAILED: "ОШИБКА"}

WEEKDAYS_RU = ("вс", "пн", "вт", "ср", "чт", "пт", "сб")


class AtloriumError(RuntimeError):
    """Ошибка API. Код HTTP разложен в человекочитаемую причину."""

    REASONS = {
        400: "Cron-выражение не передано или тело запроса пустое",
        401: "API-ключ отсутствует, просрочен или недействителен",
        402: "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429: "Превышен лимит запросов — повторите позже",
        500: "Внутренняя ошибка при разборе выражения",
    }

    def __init__(self, status: int, body: str):
        reason = self.REASONS.get(status, "Неизвестная ошибка")
        super().__init__(f"HTTP {status}: {reason}. Ответ сервера: {body[:200]}")
        self.status = status


def _retry_after(response: requests.Response) -> int:
    """Сколько ждать после 429. Мусор и слишком большие значения не берём на веру.

    Значение 0 означало бы «повторяй немедленно» — клиент ушёл бы в busy-loop.
    Значение в десятки минут (так сервер отвечает на исчерпанный часовой лимит)
    означало бы «спи почти час». Возвращаем 0, если ждать бессмысленно долго:
    вызывающий сдастся и честно скажет, что квота исчерпана.
    """
    try:
        seconds = int(response.headers.get("Retry-After", ""))
    except ValueError:
        seconds = 0

    if seconds <= 0:
        return RETRY_DELAY
    return seconds if seconds <= MAX_RETRY_DELAY else 0


def _post(path: str, payload: dict) -> dict:
    """Оба эндпоинта cron — POST с JSON-телом. GET-варианта у них нет."""
    for attempt in range(MAX_RETRIES + 1):
        response = requests.post(
            f"{BASE_URL}{path}",
            json=payload,
            headers={
                "Authorization": f"Bearer {API_KEY}",
                "Accept": "application/json",
            },
            timeout=TIMEOUT,
        )

        if response.status_code == 429 and attempt < MAX_RETRIES:
            delay = _retry_after(response)
            if delay == 0:
                break  # ждать пришлось бы дольше потолка — не ждём
            print(f"429: лимит запросов. Повтор через {delay} с…", file=sys.stderr)
            time.sleep(delay)
            continue

        if not response.ok:
            raise AtloriumError(response.status_code, response.text)
        return response.json()

    raise AtloriumError(429, "Квота исчерпана, повтор бессмыслен")


# ── Эндпоинты ────────────────────────────────────────────────────────────────


def evaluate(expression: str, time_zone_id: str = "UTC", take: int = 10,
             from_utc: str | None = None) -> dict:
    """Разбор и валидация выражения: POST /api/Cron/evaluate.

    ВНИМАНИЕ на имена полей — сервер не ругается на лишние, он их молча игнорирует
    и подставляет свои умолчания (UTC и 10 запусков). Опечатка в имени поля не даст
    ни 400, ни предупреждения — просто тихо не тот результат.

        timeZoneId (НЕ timeZone), take (НЕ count).
    """
    payload: dict = {
        "expression": expression,
        "timeZoneId": time_zone_id,
        "take": take,
    }
    # fromUtc — точка отсчёта. Не задана — сервер берёт «сейчас».
    if from_utc is not None:
        payload["fromUtc"] = from_utc

    return _post("/api/Cron/evaluate", payload)


# Шаблоны конструктора: в OpenAPI-спеке это целое число без расшифровки.
# Значения подтверждены живыми запросами к /api/Cron/build.
TEMPLATE_EVERY_MINUTE = 0    # * * * * *
TEMPLATE_EVERY_N_MINUTES = 1  # */{interval} * * * *
TEMPLATE_HOURLY = 2          # 0 * * * *
TEMPLATE_EVERY_N_HOURS = 3   # 0 */{interval} * * *
TEMPLATE_DAILY = 4           # {mm} {hh} * * *
TEMPLATE_WEEKLY = 5          # {mm} {hh} * * MON,WED,FRI
TEMPLATE_MONTHLY = 6         # {mm} {hh} {dayOfMonth} * *


def build(template: int, interval: int = 1, time_of_day: str = "09:00:00",
          day_of_month: int = 1, week_days: list[int] | None = None,
          include_seconds: bool = False) -> dict:
    """Сборка выражения из шаблона: POST /api/Cron/build.

    week_days — дни недели числами: 0 = воскресенье … 6 = суббота.
    include_seconds=True добавляет шестое поле секунд (формат Quartz).
    """
    return _post("/api/Cron/build", {
        "template": template,
        "interval": interval,
        "timeOfDay": time_of_day,
        "dayOfMonth": day_of_month,
        "weekDays": week_days if week_days is not None else [1],
        "includeSeconds": include_seconds,
    })


# ── Применение данных: валидация расписания перед деплоем ────────────────────
# Ответ API сам по себе — просто JSON. Ценность появляется, когда из него делают
# вывод. Ключевой факт: сервер НЕ предупреждает о слишком частом расписании — на
# «* 9 * * *» приходит isValid=true и пустой warnings. Он честно сообщает ФАКТЫ
# (ближайшие запуски), а РЕШЕНИЕ принимает клиент. Ниже — ровно это решение.


def _parse_instant(iso: str) -> datetime:
    """ISO-8601 со смещением: «2026-07-14T09:00:00+03:00».

    Полученный datetime сохраняет смещение из строки, поэтому .hour — это местное
    время запуска, а разность двух таких дат — корректный интервал в реальном времени.
    """
    return datetime.fromisoformat(iso.replace("Z", "+00:00"))


def format_occurrence(iso: str) -> str:
    """«вт 2026-07-14 09:00 (+03:00)» — местное время запуска в его же таймзоне."""
    moment = _parse_instant(iso)
    weekday = WEEKDAYS_RU[moment.isoweekday() % 7]
    offset = moment.strftime("%z")  # «+0300»
    return f"{weekday} {moment:%Y-%m-%d %H:%M} ({offset[:3]}:{offset[3:]})"


def min_interval_minutes(occurrences: list[str]) -> float | None:
    """Минимальный интервал между соседними запусками, в минутах.

    Именно минимальный, а не средний: расписание вида «0 9 * * 1-5» даёт разрыв в
    трое суток на выходных, и среднее его размажет. Опасность — в самом плотном месте.

    None, если запусков меньше двух: интервал не из чего вычислить (например,
    у «0 0 29 2 *» — раз в четыре года, но пяти запусков хватит и там).
    """
    if len(occurrences) < 2:
        return None

    moments = [_parse_instant(value) for value in occurrences]
    return min(
        (later - earlier).total_seconds() / 60
        for earlier, later in zip(moments, moments[1:])
    )


def describe_interval(minutes: float) -> str:
    """1 → «1 мин», 90 → «90 мин (1 ч 30 мин)», 1440 → «1440 мин (1 д 0 ч)»."""
    total = int(round(minutes))
    if total < 60:
        return f"{total} мин"
    if total < 1440:
        return f"{total} мин ({total // 60} ч {total % 60} мин)"
    return f"{total} мин ({total // 1440} д {(total % 1440) // 60} ч)"


@dataclass
class Report:
    level: int = OK
    problems: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def fail(self, level: int, problem: str) -> None:
        self.level = max(self.level, level)
        self.problems.append(problem)


def validate_schedule(result: dict) -> Report:
    """Вердикт по разобранному выражению. Уровень отчёта = код выхода программы."""
    report = Report()

    # 1. Синтаксис. Сервер не бросает исключение на кривом выражении — он возвращает
    #    isValid=false и текст ошибки. Молча проигнорировать это — значит выкатить
    #    задачу, которая никогда не запустится.
    if not result.get("isValid"):
        report.fail(FAILED, result.get("error") or "Выражение невалидно")
        return report

    occurrences = result.get("occurrences") or []
    interval = min_interval_minutes(occurrences)

    if interval is None:
        report.notes.append("Запусков меньше двух — интервал вычислить не из чего.")
        return report

    report.notes.append(
        f"Минимальный интервал между запусками: {describe_interval(interval)}."
    )

    # 2. Главная проверка, которой НЕТ на сервере: слишком частое расписание.
    #    Классическая катастрофа — `* 9 * * *` вместо `0 9 * * *`: звёздочка в поле
    #    минут означает «каждую минуту часа», то есть 60 запусков в час вместо одного.
    #    Выражение при этом абсолютно валидно, и никто, кроме вас, не возразит.
    if interval < DANGER_MINUTES:
        report.fail(
            DANGER,
            f"Задача запускается чаще, чем раз в {DANGER_MINUTES} мин — "
            f"это почти наверняка опечатка (например, «*» вместо «0» в поле минут)",
        )
    elif interval < WARNING_MINUTES:
        report.fail(
            WARNING,
            f"Задача запускается чаще раза в час (интервал {int(round(interval))} мин) — "
            f"убедитесь, что так и задумано",
        )

    # 3. Предупреждения самого сервера. Их немного, и на частоту они не реагируют,
    #    но, например, замену Quartz-символа «?» на «*» или отброшенное поле года
    #    сервер сообщает — это стоит показать.
    for warning in result.get("warnings") or []:
        report.notes.append(f"Предупреждение API: {warning}")

    return report


def main() -> int:
    if API_KEY == SANDBOX_KEY:
        print("Демо-ключ: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок —")
        print("cron считается локально, мокать нечего.\n")

    expression = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_EXPRESSION

    try:
        result = evaluate(expression, time_zone_id=TIME_ZONE, take=TAKE)
    except AtloriumError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return FAILED  # проверка не выполнена — это не «всё хорошо»

    report = validate_schedule(result)

    if report.level == FAILED:
        print(f"Выражение:      {expression}")
        for problem in report.problems:
            print(f"  [!] {problem}")
        print("\nВердикт: ОШИБКА — выражение невалидно.")
        print(f"Код выхода: {FAILED}")
        return FAILED

    zone = result.get("timeZone") or {}

    print(f"Выражение:      {result.get('rawExpression')}")
    print(f"Нормализовано:  {result.get('normalizedExpression')}")
    print(f"Сегменты:       {' | '.join(result.get('segments') or [])}")
    print(f"Таймзона:       {zone.get('id')} — {zone.get('displayName')}")
    if zone.get("supportsDaylightSavingTime"):
        # Расчёт идёт по базе часовых поясов ОС, поэтому переход на летнее время
        # (там, где он есть) учитывается сам — вручную сдвигать часы не нужно.
        print(f"                переход на летнее время учитывается ({zone.get('daylightName')})")

    print(f"\nБлижайшие {len(result.get('occurrences') or [])} запусков:")
    for index, iso in enumerate(result.get("occurrences") or [], start=1):
        print(f"  {index}. {format_occurrence(iso)}")

    print()
    for note in report.notes:
        print(f"  [i] {note}")
    for problem in report.problems:
        print(f"  [!] {problem}")

    print()
    if report.level == OK:
        print(f"Вердикт: OK — расписание не чаще, чем раз в {WARNING_MINUTES} мин.")
    elif report.level == WARNING:
        print("Вердикт: ВНИМАНИЕ — расписание частое, перепроверьте.")
    else:
        print("Вердикт: ОПАСНО — расписание почти непрерывное, деплой стоит остановить.")

    # Ключевая деталь: код выхода. Именно он делает из примера рабочую проверку
    # для CI — шаг workflow покраснеет сам, без внешнего парсинга вывода.
    print(f"Код выхода: {report.level}")
    return report.level


if __name__ == "__main__":
    raise SystemExit(main())
