// Клиент API разбора cron-выражений Atlorium — валидация расписания и ближайшие запуски.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//     dotnet run
//     dotnet run "* 9 * * *"
//
// Программа задумана как ПРОВЕРКА для CI, а не как «печаталка JSON»: она возвращает
// ненулевой код выхода, если выражение невалидно или расписание подозрительно частое.
//
//     0 — OK: реже, чем раз в 60 минут
//     1 — ВНИМАНИЕ: чаще раза в час — убедитесь, что так и задумано
//     2 — ОПАСНО: чаще раза в 5 минут — почти наверняка опечатка
//     3 — выражение невалидно или проверку выполнить не удалось (ошибка API или сети)
//
// Часовой пояс берётся из переменной окружения CRON_TZ (по умолчанию Europe/Moscow).
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.

using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

// Публичный демо-ключ. ВАЖНО: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок.
// Разбор cron — чистая локальная логика, внешних источников нет,
// и мокать тут просто нечего. Ответ помечен заголовком X-Atlorium-Sandbox: true, но
// расписание в нём подлинное.
const string SandboxKey = "ak_sandbox_demo_mockdata_v1";

// Расписание по умолчанию: каждый будний день в 9 утра.
const string DefaultExpression = "0 9 * * 1-5";

// Сколько ближайших запусков запрашивать. Двух хватило бы для интервала, но пять
// наглядно показывают человеку, что расписание действительно то, которое он задумал.
const int Take = 5;

var apiKey = Environment.GetEnvironmentVariable("ATLORIUM_API_KEY") ?? SandboxKey;
var baseUrl = Environment.GetEnvironmentVariable("ATLORIUM_BASE_URL") ?? "https://atlorium.com";

// Часовой пояс расписания. IANA-идентификатор: Europe/Moscow, America/New_York, UTC.
var timeZone = Environment.GetEnvironmentVariable("CRON_TZ") ?? "Europe/Moscow";

using var http = new HttpClient
{
    BaseAddress = new Uri(baseUrl),
    Timeout = TimeSpan.FromSeconds(30),
};
http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

var client = new CronClient(http);

if (apiKey == SandboxKey)
{
    Console.WriteLine("Демо-ключ: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок —");
    Console.WriteLine("cron считается локально, мокать нечего.\n");
}

var expression = args.Length > 0 ? args[0] : DefaultExpression;

CronEvaluation result;
try
{
    result = await client.EvaluateAsync(expression, timeZone, Take);
}
catch (AtloriumException error)
{
    Console.Error.WriteLine($"Ошибка: {error.Message}");
    return (int)Level.Failed; // проверка не выполнена — это не «всё хорошо»
}

var report = ScheduleValidator.ValidateSchedule(result);

if (report.Level == Level.Failed)
{
    Console.WriteLine($"Выражение:      {expression}");
    foreach (var problem in report.Problems)
    {
        Console.WriteLine($"  [!] {problem}");
    }

    Console.WriteLine("\nВердикт: ОШИБКА — выражение невалидно.");
    Console.WriteLine($"Код выхода: {(int)Level.Failed}");
    return (int)Level.Failed;
}

var zone = result.TimeZone;

Console.WriteLine($"Выражение:      {result.RawExpression}");
Console.WriteLine($"Нормализовано:  {result.NormalizedExpression}");
Console.WriteLine($"Сегменты:       {string.Join(" | ", result.Segments)}");
Console.WriteLine($"Таймзона:       {zone.Id} — {zone.DisplayName}");
if (zone.SupportsDaylightSavingTime)
{
    // Расчёт идёт по базе часовых поясов ОС, поэтому переход на летнее время
    // (там, где он есть) учитывается сам — вручную сдвигать часы не нужно.
    Console.WriteLine($"                переход на летнее время учитывается ({zone.DaylightName})");
}

Console.WriteLine($"\nБлижайшие {result.Occurrences.Count} запусков:");
for (var index = 0; index < result.Occurrences.Count; index++)
{
    Console.WriteLine($"  {index + 1}. {ScheduleValidator.FormatOccurrence(result.Occurrences[index])}");
}

Console.WriteLine();
foreach (var note in report.Notes)
{
    Console.WriteLine($"  [i] {note}");
}
foreach (var problem in report.Problems)
{
    Console.WriteLine($"  [!] {problem}");
}

Console.WriteLine();
Console.WriteLine(report.Level switch
{
    Level.Ok => $"Вердикт: OK — расписание не чаще, чем раз в {ScheduleValidator.WarningMinutes} мин.",
    Level.Warning => "Вердикт: ВНИМАНИЕ — расписание частое, перепроверьте.",
    _ => "Вердикт: ОПАСНО — расписание почти непрерывное, деплой стоит остановить.",
});

// Ключевая деталь: код выхода. Именно он делает из примера рабочую проверку
// для CI — шаг workflow покраснеет сам, без внешнего парсинга вывода.
Console.WriteLine($"Код выхода: {(int)report.Level}");
return (int)report.Level;

// ── Клиент ───────────────────────────────────────────────────────────────────

/// <summary>Ошибка API: HTTP-код разложен в человекочитаемую причину.</summary>
public sealed class AtloriumException(HttpStatusCode status, string body)
    : Exception($"HTTP {(int)status}: {Explain(status)}. Ответ сервера: {body[..Math.Min(200, body.Length)]}")
{
    public HttpStatusCode Status { get; } = status;

    private static string Explain(HttpStatusCode status) => (int)status switch
    {
        400 => "Cron-выражение не передано или тело запроса пустое",
        401 => "API-ключ отсутствует, просрочен или недействителен",
        402 => "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429 => "Превышен лимит запросов — повторите позже",
        500 => "Внутренняя ошибка при разборе выражения",
        _ => "Неизвестная ошибка",
    };
}

/// <summary>
/// Шаблоны конструктора: в OpenAPI-спеке это целое число без расшифровки.
/// Значения подтверждены живыми запросами к <c>/api/Cron/build</c>.
/// </summary>
public enum CronTemplate
{
    EveryMinute = 0,   // * * * * *
    EveryNMinutes = 1, // */{interval} * * * *
    Hourly = 2,        // 0 * * * *
    EveryNHours = 3,   // 0 */{interval} * * *
    Daily = 4,         // {mm} {hh} * * *
    Weekly = 5,        // {mm} {hh} * * MON,WED,FRI
    Monthly = 6,       // {mm} {hh} {dayOfMonth} * *
}

public sealed class CronClient(HttpClient http)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    // 429 — повтор один раз с паузой. MaxRetryDelay — потолок ожидания: исчерпав
    // ЧАСОВОЙ лимит, сервер честно просит подождать десятки минут, и клиент, слепо
    // доверяющий Retry-After, зависнет на всё это время (а в CI съест бюджет джоба).
    private const int RetryDelay = 20;
    private const int MaxRetries = 1;
    private const int MaxRetryDelay = 120;

    /// <summary>
    /// Разбор и валидация выражения: POST /api/Cron/evaluate.
    ///
    /// ВНИМАНИЕ на имена полей — сервер не ругается на лишние, он их молча игнорирует
    /// и подставляет свои умолчания (UTC и 10 запусков). Опечатка в имени поля не даст
    /// ни 400, ни предупреждения — просто тихо не тот результат:
    /// <c>timeZoneId</c> (НЕ <c>timeZone</c>), <c>take</c> (НЕ <c>count</c>).
    /// </summary>
    /// <param name="fromUtc">Точка отсчёта. <c>null</c> — сервер возьмёт «сейчас».</param>
    public async Task<CronEvaluation> EvaluateAsync(
        string expression, string timeZoneId = "UTC", int take = 10, DateTimeOffset? fromUtc = null)
        => await PostAsync<CronEvaluation>("/api/Cron/evaluate", new
        {
            expression,
            timeZoneId,
            take,
            fromUtc,
        });

    /// <summary>
    /// Сборка выражения из шаблона: POST /api/Cron/build.
    /// </summary>
    /// <param name="weekDays">Дни недели: <see cref="DayOfWeek"/>, 0 = воскресенье … 6 = суббота.</param>
    /// <param name="includeSeconds">Добавить шестое поле секунд (формат Quartz).</param>
    public async Task<CronBuildResult> BuildAsync(
        CronTemplate template,
        int interval = 1,
        TimeSpan? timeOfDay = null,
        int dayOfMonth = 1,
        IReadOnlyList<DayOfWeek>? weekDays = null,
        bool includeSeconds = false)
        => await PostAsync<CronBuildResult>("/api/Cron/build", new
        {
            template = (int)template,
            interval,
            timeOfDay = (timeOfDay ?? new TimeSpan(9, 0, 0)).ToString(@"hh\:mm\:ss"),
            dayOfMonth,
            weekDays = (weekDays ?? [DayOfWeek.Monday]).Select(day => (int)day).ToArray(),
            includeSeconds,
        });

    /// <summary>Оба эндпоинта cron принимают только POST с JSON-телом. GET-варианта нет.</summary>
    private async Task<T> PostAsync<T>(string path, object payload)
    {
        for (var attempt = 0; attempt <= MaxRetries; attempt++)
        {
            using var response = await http.PostAsJsonAsync(path, payload, JsonOptions);

            if (response.StatusCode == HttpStatusCode.TooManyRequests && attempt < MaxRetries)
            {
                var delay = RetryAfter(response);
                if (delay == 0)
                {
                    break; // ждать пришлось бы дольше потолка — не ждём
                }

                Console.Error.WriteLine($"429: лимит запросов. Повтор через {delay} с…");
                await Task.Delay(TimeSpan.FromSeconds(delay));
                continue;
            }

            var body = await response.Content.ReadAsStringAsync();
            if (!response.IsSuccessStatusCode)
            {
                throw new AtloriumException(response.StatusCode, body);
            }

            return JsonSerializer.Deserialize<T>(body, JsonOptions)
                   ?? throw new InvalidOperationException("Пустой ответ API.");
        }

        throw new AtloriumException(HttpStatusCode.TooManyRequests, "Квота исчерпана, повтор бессмыслен");
    }

    /// <summary>
    /// Сколько ждать после 429. Мусор и слишком большие значения не берём на веру:
    /// 0 означало бы busy-loop, десятки минут — «спи почти час». Возвращаем 0, если
    /// ждать бессмысленно долго: вызывающий сдастся.
    /// </summary>
    private static int RetryAfter(HttpResponseMessage response)
    {
        var seconds = (int?)response.Headers.RetryAfter?.Delta?.TotalSeconds ?? 0;
        if (seconds <= 0)
        {
            return RetryDelay;
        }
        return seconds <= MaxRetryDelay ? seconds : 0;
    }
}

// ── Модель ответа ────────────────────────────────────────────────────────────

/// <summary>Сведения о часовом поясе, в котором посчитано расписание.</summary>
public sealed record CronTimeZone
{
    public string Id { get; init; } = "";
    public bool HasIanaId { get; init; }
    public string DisplayName { get; init; } = "";
    public string StandardName { get; init; } = "";
    public string DaylightName { get; init; } = "";
    public string BaseUtcOffset { get; init; } = "";
    public bool SupportsDaylightSavingTime { get; init; }
}

/// <summary>Ответ POST /api/Cron/evaluate.</summary>
public sealed record CronEvaluation
{
    public bool IsValid { get; init; }
    public string RawExpression { get; init; } = "";
    public string NormalizedExpression { get; init; } = "";
    public string Error { get; init; } = "";
    public IReadOnlyList<string> Warnings { get; init; } = [];
    public IReadOnlyList<string> Segments { get; init; } = [];

    /// <summary>Ближайшие запуски. Смещение из ответа сохраняется — это местное время запуска.</summary>
    public IReadOnlyList<DateTimeOffset> Occurrences { get; init; } = [];

    public CronTimeZone TimeZone { get; init; } = new();
}

/// <summary>Ответ POST /api/Cron/build.</summary>
public sealed record CronBuildResult
{
    public string Expression { get; init; } = "";
    public IReadOnlyList<string> Warnings { get; init; } = [];
    public string Error { get; init; } = "";
    public bool Success { get; init; }
}

// ── Применение данных: валидация расписания перед деплоем ────────────────────
// Ответ API сам по себе — просто JSON. Ценность появляется, когда из него делают
// вывод. Ключевой факт: сервер НЕ предупреждает о слишком частом расписании — на
// «* 9 * * *» приходит isValid=true и пустой warnings. Он честно сообщает ФАКТЫ
// (ближайшие запуски), а РЕШЕНИЕ принимает клиент. Ниже — ровно это решение.

/// <summary>Уровни вердикта. Значение уровня — это и есть код выхода программы.</summary>
public enum Level
{
    Ok = 0,
    Warning = 1,
    Danger = 2,
    Failed = 3,
}

public sealed record Report
{
    public Level Level { get; private set; } = Level.Ok;

    private readonly List<string> _problems = [];
    private readonly List<string> _notes = [];

    public IReadOnlyList<string> Problems => _problems;
    public IReadOnlyList<string> Notes => _notes;

    public void Fail(Level level, string problem)
    {
        if (level > Level)
        {
            Level = level;
        }
        _problems.Add(problem);
    }

    public void Note(string note) => _notes.Add(note);
}

public static class ScheduleValidator
{
    // Пороги вердикта. Сервер честно считает расписание, но НЕ оценивает его
    // разумность: на «* 9 * * *» он возвращает isValid=true и пустой warnings.
    //
    // 5 минут — граница «задача практически не прекращает работать»: типовая опечатка
    // `* 9 * * *` вместо `0 9 * * *` даёт 60 запусков в час вместо одного.
    // 60 минут — граница «чаще раза в час»: бывает нужно, но стоит перепроверить.
    public const int DangerMinutes = 5;
    public const int WarningMinutes = 60;

    private static readonly string[] WeekdaysRu = ["вс", "пн", "вт", "ср", "чт", "пт", "сб"];

    /// <summary>
    /// «вт 2026-07-14 09:00 (+03:00)» — местное время запуска в его же таймзоне.
    ///
    /// DateTimeOffset сохраняет смещение из ответа, поэтому DayOfWeek и формат дают
    /// МЕСТНОЕ время запуска, а не время в таймзоне машины, где запущен пример.
    /// </summary>
    public static string FormatOccurrence(DateTimeOffset moment)
    {
        var weekday = WeekdaysRu[(int)moment.DayOfWeek]; // DayOfWeek: воскресенье = 0
        var stamp = moment.ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);
        var offset = moment.ToString("zzz", CultureInfo.InvariantCulture); // «+03:00»
        return $"{weekday} {stamp} ({offset})";
    }

    /// <summary>
    /// Минимальный интервал между соседними запусками, в минутах.
    /// <c>null</c>, если запусков меньше двух: интервал не из чего вычислить.
    ///
    /// Именно минимальный, а не средний: расписание вида «0 9 * * 1-5» даёт разрыв в
    /// трое суток на выходных, и среднее его размажет. Опасность — в самом плотном месте.
    /// </summary>
    public static double? MinIntervalMinutes(IReadOnlyList<DateTimeOffset> occurrences)
    {
        if (occurrences.Count < 2)
        {
            return null;
        }

        var smallest = double.MaxValue;
        for (var index = 1; index < occurrences.Count; index++)
        {
            var gap = (occurrences[index] - occurrences[index - 1]).TotalMinutes;
            smallest = Math.Min(smallest, gap);
        }
        return smallest;
    }

    /// <summary>1 → «1 мин», 90 → «90 мин (1 ч 30 мин)», 1440 → «1440 мин (1 д 0 ч)».</summary>
    public static string DescribeInterval(double minutes)
    {
        var total = (long)Math.Round(minutes);
        if (total < 60)
        {
            return $"{total} мин";
        }
        if (total < 1440)
        {
            return $"{total} мин ({total / 60} ч {total % 60} мин)";
        }
        return $"{total} мин ({total / 1440} д {total % 1440 / 60} ч)";
    }

    /// <summary>Вердикт по разобранному выражению. Уровень отчёта = код выхода программы.</summary>
    public static Report ValidateSchedule(CronEvaluation result)
    {
        var report = new Report();

        // 1. Синтаксис. Сервер не бросает исключение на кривом выражении — он возвращает
        //    isValid=false и текст ошибки. Молча проигнорировать это — значит выкатить
        //    задачу, которая никогда не запустится.
        if (!result.IsValid)
        {
            report.Fail(Level.Failed, string.IsNullOrEmpty(result.Error) ? "Выражение невалидно" : result.Error);
            return report;
        }

        var interval = MinIntervalMinutes(result.Occurrences);
        if (interval is not { } minutes)
        {
            report.Note("Запусков меньше двух — интервал вычислить не из чего.");
            return report;
        }

        report.Note($"Минимальный интервал между запусками: {DescribeInterval(minutes)}.");

        // 2. Главная проверка, которой НЕТ на сервере: слишком частое расписание.
        //    Классическая катастрофа — `* 9 * * *` вместо `0 9 * * *`: звёздочка в поле
        //    минут означает «каждую минуту часа», то есть 60 запусков в час вместо одного.
        //    Выражение при этом абсолютно валидно, и никто, кроме вас, не возразит.
        if (minutes < DangerMinutes)
        {
            report.Fail(Level.Danger, $"Задача запускается чаще, чем раз в {DangerMinutes} мин — "
                                      + "это почти наверняка опечатка (например, «*» вместо «0» в поле минут)");
        }
        else if (minutes < WarningMinutes)
        {
            report.Fail(Level.Warning, $"Задача запускается чаще раза в час (интервал {Math.Round(minutes)} мин) — "
                                       + "убедитесь, что так и задумано");
        }

        // 3. Предупреждения самого сервера. Их немного, и на частоту они не реагируют,
        //    но, например, замену Quartz-символа «?» на «*» или отброшенное поле года
        //    сервер сообщает — это стоит показать.
        foreach (var warning in result.Warnings)
        {
            report.Note($"Предупреждение API: {warning}");
        }

        return report;
    }
}
