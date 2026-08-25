<?php

/**
 * Клиент API разбора cron-выражений Atlorium — валидация расписания и ближайшие запуски.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   php main.php
 *   php main.php "* 9 * * *"
 *
 * Программа задумана как ПРОВЕРКА для CI, а не как «печаталка JSON»: она возвращает
 * ненулевой код выхода, если выражение невалидно или расписание подозрительно частое.
 *
 *   0 — OK: реже, чем раз в 60 минут
 *   1 — ВНИМАНИЕ: чаще раза в час — убедитесь, что так и задумано
 *   2 — ОПАСНО: чаще раза в 5 минут — почти наверняка опечатка
 *   3 — выражение невалидно или проверку выполнить не удалось (ошибка API или сети)
 *
 * Часовой пояс берётся из переменной окружения CRON_TZ (по умолчанию Europe/Moscow).
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

declare(strict_types=1);

/**
 * Публичный демо-ключ. ВАЖНО: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок.
 * Разбор cron — чистая локальная логика, внешних источников нет,
 * и мокать тут просто нечего. Ответ помечен заголовком X-Atlorium-Sandbox: true, но
 * расписание в нём подлинное.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const TIMEOUT = 30;

// ── Пороги вердикта ──────────────────────────────────────────────────────────
// Сервер честно считает расписание, но НЕ оценивает его разумность: на «* 9 * * *»
// он возвращает isValid=true и пустой warnings. Значит, вердикт выносит клиент.
//
// 5 минут — граница «задача практически не прекращает работать»: типовая опечатка
// `* 9 * * *` вместо `0 9 * * *` даёт 60 запусков в час вместо одного.
// 60 минут — граница «чаще раза в час»: бывает нужно, но стоит перепроверить.
const DANGER_MINUTES = 5;
const WARNING_MINUTES = 60;

/**
 * Сколько ближайших запусков запрашивать. Двух хватило бы для интервала, но пять
 * наглядно показывают человеку, что расписание действительно то, которое он задумал.
 */
const TAKE = 5;

/** Расписание по умолчанию: каждый будний день в 9 утра. */
const DEFAULT_EXPRESSION = '0 9 * * 1-5';

/** 429 — повтор один раз с паузой. */
const RETRY_DELAY = 20;
const MAX_RETRIES = 1;

/**
 * Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно просит подождать десятки
 * минут — и клиент, слепо доверяющий Retry-After, зависнет на всё это время (а в CI
 * просто съест бюджет джоба). Дольше потолка не ждём.
 */
const MAX_RETRY_DELAY = 120;

/** Уровни вердикта. Значение уровня — это и есть код выхода программы. */
const LEVEL_OK = 0;
const LEVEL_WARNING = 1;
const LEVEL_DANGER = 2;
const LEVEL_FAILED = 3;

const WEEKDAYS_RU = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];

// Шаблоны конструктора: в OpenAPI-спеке это целое число без расшифровки.
// Значения подтверждены живыми запросами к /api/Cron/build.
const TEMPLATE_EVERY_MINUTE = 0;     // * * * * *
const TEMPLATE_EVERY_N_MINUTES = 1;  // */{interval} * * * *
const TEMPLATE_HOURLY = 2;           // 0 * * * *
const TEMPLATE_EVERY_N_HOURS = 3;    // 0 */{interval} * * *
const TEMPLATE_DAILY = 4;            // {mm} {hh} * * *
const TEMPLATE_WEEKLY = 5;           // {mm} {hh} * * MON,WED,FRI
const TEMPLATE_MONTHLY = 6;          // {mm} {hh} {dayOfMonth} * *

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
final class AtloriumError extends RuntimeException
{
    private const REASONS = [
        400 => 'Cron-выражение не передано или тело запроса пустое',
        401 => 'API-ключ отсутствует, просрочен или недействителен',
        402 => 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
        429 => 'Превышен лимит запросов — повторите позже',
        500 => 'Внутренняя ошибка при разборе выражения',
    ];

    public function __construct(public readonly int $status, string $body)
    {
        $reason = self::REASONS[$status] ?? 'Неизвестная ошибка';
        parent::__construct(sprintf('HTTP %d: %s. Ответ сервера: %s', $status, $reason, mb_substr($body, 0, 200)));
    }
}

final class CronClient
{
    private string $apiKey;
    private string $baseUrl;

    public function __construct(?string $apiKey = null, ?string $baseUrl = null)
    {
        $this->apiKey = $apiKey ?? (getenv('ATLORIUM_API_KEY') ?: SANDBOX_KEY);
        $this->baseUrl = $baseUrl ?? (getenv('ATLORIUM_BASE_URL') ?: 'https://atlorium.com');
    }

    public function isSandbox(): bool
    {
        return $this->apiKey === SANDBOX_KEY;
    }

    /**
     * Сколько ждать после 429. Мусор и слишком большие значения не берём на веру:
     * 0 означало бы busy-loop, десятки минут — «спи почти час». Возвращаем 0, если
     * ждать бессмысленно долго: вызывающий сдастся.
     */
    private function retryAfter(string $headers): int
    {
        if (preg_match('/^Retry-After:\s*(\d+)/mi', $headers, $match) !== 1) {
            return RETRY_DELAY;
        }
        $seconds = (int) $match[1];
        if ($seconds <= 0) {
            return RETRY_DELAY;
        }

        return $seconds <= MAX_RETRY_DELAY ? $seconds : 0;
    }

    /**
     * Оба эндпоинта cron принимают только POST с JSON-телом. GET-варианта нет.
     *
     * @param array<string, mixed> $payload
     * @return array<string, mixed>
     */
    private function post(string $path, array $payload): array
    {
        $json = json_encode($payload, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);

        for ($attempt = 0; $attempt <= MAX_RETRIES; $attempt++) {
            $curl = curl_init($this->baseUrl . $path);
            curl_setopt_array($curl, [
                CURLOPT_POST => true,
                CURLOPT_POSTFIELDS => $json,
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_HEADER => true,
                CURLOPT_TIMEOUT => TIMEOUT,
                CURLOPT_HTTPHEADER => [
                    'Authorization: Bearer ' . $this->apiKey,
                    'Accept: application/json',
                    'Content-Type: application/json',
                ],
            ]);

            $raw = curl_exec($curl);
            if ($raw === false) {
                $error = curl_error($curl);
                curl_close($curl);
                throw new RuntimeException("Сетевая ошибка: {$error}");
            }

            $status = (int) curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
            $headerSize = (int) curl_getinfo($curl, CURLINFO_HEADER_SIZE);
            curl_close($curl);

            $raw = (string) $raw;
            $headers = substr($raw, 0, $headerSize);
            $body = substr($raw, $headerSize);

            if ($status === 429 && $attempt < MAX_RETRIES) {
                $delay = $this->retryAfter($headers);
                if ($delay === 0) {
                    break; // ждать пришлось бы дольше потолка — не ждём
                }
                fwrite(STDERR, "429: лимит запросов. Повтор через {$delay} с…\n");
                sleep($delay);
                continue;
            }

            if ($status !== 200) {
                throw new AtloriumError($status, $body);
            }

            return json_decode($body, true, 512, JSON_THROW_ON_ERROR);
        }

        throw new AtloriumError(429, 'Квота исчерпана, повтор бессмыслен');
    }

    // ── Эндпоинты ────────────────────────────────────────────────────────────

    /**
     * Разбор и валидация выражения: POST /api/Cron/evaluate.
     *
     * ВНИМАНИЕ на имена полей — сервер не ругается на лишние, он их молча игнорирует
     * и подставляет свои умолчания (UTC и 10 запусков). Опечатка в имени поля не даст
     * ни 400, ни предупреждения — просто тихо не тот результат:
     * timeZoneId (НЕ timeZone), take (НЕ count).
     *
     * $fromUtc — точка отсчёта. null — сервер возьмёт «сейчас».
     *
     * @return array<string, mixed>
     */
    public function evaluate(string $expression, string $timeZoneId = 'UTC', int $take = 10, ?string $fromUtc = null): array
    {
        $payload = [
            'expression' => $expression,
            'timeZoneId' => $timeZoneId,
            'take' => $take,
        ];
        if ($fromUtc !== null) {
            $payload['fromUtc'] = $fromUtc;
        }

        return $this->post('/api/Cron/evaluate', $payload);
    }

    /**
     * Сборка выражения из шаблона: POST /api/Cron/build.
     *
     * $weekDays — дни недели числами: 0 = воскресенье … 6 = суббота.
     * $includeSeconds = true добавляет шестое поле секунд (формат Quartz).
     *
     * @param list<int> $weekDays
     * @return array<string, mixed>
     */
    public function build(
        int $template,
        int $interval = 1,
        string $timeOfDay = '09:00:00',
        int $dayOfMonth = 1,
        array $weekDays = [1],
        bool $includeSeconds = false
    ): array {
        return $this->post('/api/Cron/build', [
            'template' => $template,
            'interval' => $interval,
            'timeOfDay' => $timeOfDay,
            'dayOfMonth' => $dayOfMonth,
            'weekDays' => $weekDays,
            'includeSeconds' => $includeSeconds,
        ]);
    }
}

// ── Применение данных: валидация расписания перед деплоем ────────────────────
// Ответ API сам по себе — просто JSON. Ценность появляется, когда из него делают
// вывод. Ключевой факт: сервер НЕ предупреждает о слишком частом расписании — на
// «* 9 * * *» приходит isValid=true и пустой warnings. Он честно сообщает ФАКТЫ
// (ближайшие запуски), а РЕШЕНИЕ принимает клиент. Ниже — ровно это решение.

/**
 * «вт 2026-07-14 09:00 (+03:00)» — местное время запуска в его же таймзоне.
 *
 * DateTimeImmutable сохраняет смещение из строки, поэтому format() даёт МЕСТНОЕ время
 * запуска, а не время в таймзоне машины, где запущен пример.
 */
function formatOccurrence(string $iso): string
{
    $moment = new DateTimeImmutable($iso);
    $weekday = WEEKDAYS_RU[(int) $moment->format('w')]; // 'w': воскресенье = 0

    return $weekday . ' ' . $moment->format('Y-m-d H:i') . ' (' . $moment->format('P') . ')';
}

/**
 * Минимальный интервал между соседними запусками, в минутах.
 * null, если запусков меньше двух: интервал не из чего вычислить.
 *
 * Именно минимальный, а не средний: расписание вида «0 9 * * 1-5» даёт разрыв в трое
 * суток на выходных, и среднее его размажет. Опасность — в самом плотном месте.
 *
 * @param list<string> $occurrences
 */
function minIntervalMinutes(array $occurrences): ?float
{
    if (count($occurrences) < 2) {
        return null;
    }

    // getTimestamp() — момент времени в UTC, поэтому разность корректна независимо
    // от смещений (и остаётся корректной на переходе летнего времени).
    $stamps = array_map(static fn (string $iso): int => (new DateTimeImmutable($iso))->getTimestamp(), $occurrences);

    $smallest = INF;
    for ($index = 1, $count = count($stamps); $index < $count; $index++) {
        $gap = ($stamps[$index] - $stamps[$index - 1]) / 60;
        $smallest = min($smallest, $gap);
    }

    return (float) $smallest;
}

/** 1 → «1 мин», 90 → «90 мин (1 ч 30 мин)», 1440 → «1440 мин (1 д 0 ч)». */
function describeInterval(float $minutes): string
{
    $total = (int) round($minutes);
    if ($total < 60) {
        return "{$total} мин";
    }
    if ($total < 1440) {
        return $total . ' мин (' . intdiv($total, 60) . ' ч ' . ($total % 60) . " мин)";
    }

    return $total . ' мин (' . intdiv($total, 1440) . ' д ' . intdiv($total % 1440, 60) . ' ч)';
}

/**
 * Вердикт по разобранному выражению. Уровень отчёта = код выхода программы.
 *
 * @param array<string, mixed> $result
 * @return array{level: int, problems: list<string>, notes: list<string>}
 */
function validateSchedule(array $result): array
{
    $level = LEVEL_OK;
    $problems = [];
    $notes = [];

    $fail = static function (int $next, string $problem) use (&$level, &$problems): void {
        $level = max($level, $next);
        $problems[] = $problem;
    };

    // 1. Синтаксис. Сервер не бросает исключение на кривом выражении — он возвращает
    //    isValid=false и текст ошибки. Молча проигнорировать это — значит выкатить
    //    задачу, которая никогда не запустится.
    if (!($result['isValid'] ?? false)) {
        $error = (string) ($result['error'] ?? '');
        $fail(LEVEL_FAILED, $error === '' ? 'Выражение невалидно' : $error);

        return ['level' => $level, 'problems' => $problems, 'notes' => $notes];
    }

    $interval = minIntervalMinutes($result['occurrences'] ?? []);
    if ($interval === null) {
        $notes[] = 'Запусков меньше двух — интервал вычислить не из чего.';

        return ['level' => $level, 'problems' => $problems, 'notes' => $notes];
    }

    $notes[] = 'Минимальный интервал между запусками: ' . describeInterval($interval) . '.';

    // 2. Главная проверка, которой НЕТ на сервере: слишком частое расписание.
    //    Классическая катастрофа — `* 9 * * *` вместо `0 9 * * *`: звёздочка в поле
    //    минут означает «каждую минуту часа», то есть 60 запусков в час вместо одного.
    //    Выражение при этом абсолютно валидно, и никто, кроме вас, не возразит.
    if ($interval < DANGER_MINUTES) {
        $fail(LEVEL_DANGER, 'Задача запускается чаще, чем раз в ' . DANGER_MINUTES
            . ' мин — это почти наверняка опечатка (например, «*» вместо «0» в поле минут)');
    } elseif ($interval < WARNING_MINUTES) {
        $fail(LEVEL_WARNING, 'Задача запускается чаще раза в час (интервал '
            . (int) round($interval) . ' мин) — убедитесь, что так и задумано');
    }

    // 3. Предупреждения самого сервера. Их немного, и на частоту они не реагируют,
    //    но, например, замену Quartz-символа «?» на «*» или отброшенное поле года
    //    сервер сообщает — это стоит показать.
    foreach ($result['warnings'] ?? [] as $warning) {
        $notes[] = "Предупреждение API: {$warning}";
    }

    return ['level' => $level, 'problems' => $problems, 'notes' => $notes];
}

// ── Демонстрация ─────────────────────────────────────────────────────────────

$client = new CronClient();

if ($client->isSandbox()) {
    echo "Демо-ключ: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок —\n";
    echo "cron считается локально, мокать нечего.\n\n";
}

$expression = $argv[1] ?? DEFAULT_EXPRESSION;

// Часовой пояс расписания. IANA-идентификатор: Europe/Moscow, America/New_York, UTC.
$timeZone = getenv('CRON_TZ') ?: 'Europe/Moscow';

try {
    $result = $client->evaluate($expression, $timeZone, TAKE);
} catch (RuntimeException $error) { // AtloriumError и сетевая ошибка — обе отсюда
    fwrite(STDERR, "Ошибка: {$error->getMessage()}\n");
    exit(LEVEL_FAILED); // проверка не выполнена — это не «всё хорошо»
}

$report = validateSchedule($result);

if ($report['level'] === LEVEL_FAILED) {
    echo "Выражение:      {$expression}\n";
    foreach ($report['problems'] as $problem) {
        echo "  [!] {$problem}\n";
    }
    echo "\nВердикт: ОШИБКА — выражение невалидно.\n";
    echo 'Код выхода: ' . LEVEL_FAILED . "\n";
    exit(LEVEL_FAILED);
}

$zone = $result['timeZone'] ?? [];
$occurrences = $result['occurrences'] ?? [];

echo "Выражение:      {$result['rawExpression']}\n";
echo "Нормализовано:  {$result['normalizedExpression']}\n";
echo 'Сегменты:       ' . implode(' | ', $result['segments'] ?? []) . "\n";
echo "Таймзона:       {$zone['id']} — {$zone['displayName']}\n";
if ($zone['supportsDaylightSavingTime'] ?? false) {
    // Расчёт идёт по базе часовых поясов ОС, поэтому переход на летнее время
    // (там, где он есть) учитывается сам — вручную сдвигать часы не нужно.
    echo "                переход на летнее время учитывается ({$zone['daylightName']})\n";
}

echo "\nБлижайшие " . count($occurrences) . " запусков:\n";
foreach ($occurrences as $index => $iso) {
    echo '  ' . ($index + 1) . '. ' . formatOccurrence($iso) . "\n";
}

echo "\n";
foreach ($report['notes'] as $note) {
    echo "  [i] {$note}\n";
}
foreach ($report['problems'] as $problem) {
    echo "  [!] {$problem}\n";
}

echo "\n";
if ($report['level'] === LEVEL_OK) {
    echo 'Вердикт: OK — расписание не чаще, чем раз в ' . WARNING_MINUTES . " мин.\n";
} elseif ($report['level'] === LEVEL_WARNING) {
    echo "Вердикт: ВНИМАНИЕ — расписание частое, перепроверьте.\n";
} else {
    echo "Вердикт: ОПАСНО — расписание почти непрерывное, деплой стоит остановить.\n";
}

// Ключевая деталь: код выхода. Именно он делает из примера рабочую проверку
// для CI — шаг workflow покраснеет сам, без внешнего парсинга вывода.
echo "Код выхода: {$report['level']}\n";
exit($report['level']);
