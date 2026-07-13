/*
 * Клиент API разбора cron-выражений Atlorium — валидация расписания и ближайшие запуски.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе).
 * Начиная с Java 11 файл запускается напрямую, без компиляции и без зависимостей:
 *
 *     java Main.java
 *     java Main.java "* 9 * * *"
 *
 * Программа задумана как ПРОВЕРКА для CI, а не как «печаталка JSON»: она возвращает
 * ненулевой код выхода, если выражение невалидно или расписание подозрительно частое.
 *
 *     0 — OK: реже, чем раз в 60 минут
 *     1 — ВНИМАНИЕ: чаще раза в час — убедитесь, что так и задумано
 *     2 — ОПАСНО: чаще раза в 5 минут — почти наверняка опечатка
 *     3 — выражение невалидно или проверку выполнить не удалось (ошибка API или сети)
 *
 * Часовой пояс берётся из переменной окружения CRON_TZ (по умолчанию Europe/Moscow).
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    /**
     * Публичный демо-ключ. ВАЖНО: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок.
     * Разбор cron — чистая локальная логика, внешних источников нет,
     * и мокать тут просто нечего. Ответ помечен заголовком X-Atlorium-Sandbox: true, но
     * расписание в нём подлинное.
     */
    static final String SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1";

    static final String API_KEY = envOr("ATLORIUM_API_KEY", SANDBOX_KEY);
    static final String BASE_URL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com");

    /** Часовой пояс расписания. IANA-идентификатор: Europe/Moscow, America/New_York, UTC. */
    static final String TIME_ZONE = envOr("CRON_TZ", "Europe/Moscow");

    // ── Пороги вердикта ──────────────────────────────────────────────────────
    // Сервер честно считает расписание, но НЕ оценивает его разумность: на «* 9 * * *»
    // он возвращает isValid=true и пустой warnings. Значит, вердикт выносит клиент.
    //
    // 5 минут — граница «задача практически не прекращает работать»: типовая опечатка
    // `* 9 * * *` вместо `0 9 * * *` даёт 60 запусков в час вместо одного.
    // 60 минут — граница «чаще раза в час»: бывает нужно, но стоит перепроверить.
    static final int DANGER_MINUTES = 5;
    static final int WARNING_MINUTES = 60;

    /**
     * Сколько ближайших запусков запрашивать. Двух хватило бы для интервала, но пять
     * наглядно показывают человеку, что расписание действительно то, которое он задумал.
     */
    static final int TAKE = 5;

    /** Расписание по умолчанию: каждый будний день в 9 утра. */
    static final String DEFAULT_EXPRESSION = "0 9 * * 1-5";

    /** 429 — повтор один раз с паузой. */
    static final int RETRY_DELAY = 20;
    static final int MAX_RETRIES = 1;

    /**
     * Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно просит подождать десятки
     * минут — и клиент, слепо доверяющий Retry-After, зависнет на всё это время (а в CI
     * просто съест бюджет джоба). Дольше потолка не ждём.
     */
    static final int MAX_RETRY_DELAY = 120;

    /** Уровни вердикта. Значение уровня — это и есть код выхода программы. */
    static final int OK = 0;
    static final int WARNING = 1;
    static final int DANGER = 2;
    static final int FAILED = 3;

    static final String[] WEEKDAYS_RU = {"вс", "пн", "вт", "ср", "чт", "пт", "сб"};

    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
    static class AtloriumException extends RuntimeException {
        private static final Map<Integer, String> REASONS = Map.of(
                400, "Cron-выражение не передано или тело запроса пустое",
                401, "API-ключ отсутствует, просрочен или недействителен",
                402, "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
                429, "Превышен лимит запросов — повторите позже",
                500, "Внутренняя ошибка при разборе выражения");

        final int status;

        AtloriumException(int status, String body) {
            super("HTTP " + status + ": "
                    + REASONS.getOrDefault(status, "Неизвестная ошибка")
                    + ". Ответ сервера: " + body.substring(0, Math.min(200, body.length())));
            this.status = status;
        }
    }

    /**
     * Сколько ждать после 429. Мусор и слишком большие значения не берём на веру:
     * 0 означало бы busy-loop, десятки минут — «спи почти час». Возвращаем 0, если
     * ждать бессмысленно долго: вызывающий сдастся.
     */
    static int retryAfter(HttpResponse<String> response) {
        int seconds;
        try {
            seconds = Integer.parseInt(response.headers().firstValue("Retry-After").orElse(""));
        } catch (NumberFormatException ignored) {
            seconds = 0;
        }
        if (seconds <= 0) {
            return RETRY_DELAY;
        }
        return seconds <= MAX_RETRY_DELAY ? seconds : 0;
    }

    /** Оба эндпоинта cron принимают только POST с JSON-телом. GET-варианта нет. */
    static String post(String path, String json) throws IOException, InterruptedException {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
                int delay = retryAfter(response);
                if (delay == 0) {
                    break; // ждать пришлось бы дольше потолка — не ждём
                }
                System.err.println("429: лимит запросов. Повтор через " + delay + " с…");
                Thread.sleep(delay * 1000L);
                continue;
            }

            if (response.statusCode() != 200) {
                throw new AtloriumException(response.statusCode(), response.body());
            }
            return response.body();
        }

        throw new AtloriumException(429, "Квота исчерпана, повтор бессмыслен");
    }

    // ── Эндпоинты ────────────────────────────────────────────────────────────

    /**
     * Разбор и валидация выражения: POST /api/Cron/evaluate.
     *
     * ВНИМАНИЕ на имена полей — сервер не ругается на лишние, он их молча игнорирует
     * и подставляет свои умолчания (UTC и 10 запусков). Опечатка в имени поля не даст
     * ни 400, ни предупреждения — просто тихо не тот результат.
     *
     *   timeZoneId (НЕ timeZone), take (НЕ count).
     */
    static String evaluate(String expression, String timeZoneId, int take)
            throws IOException, InterruptedException {
        String json = "{\"expression\":\"" + escape(expression) + "\","
                + "\"timeZoneId\":\"" + escape(timeZoneId) + "\","
                + "\"take\":" + take + "}";
        return post("/api/Cron/evaluate", json);
    }

    // Шаблоны конструктора: в OpenAPI-спеке это целое число без расшифровки.
    // Значения подтверждены живыми запросами к /api/Cron/build.
    static final int TEMPLATE_EVERY_MINUTE = 0;     // * * * * *
    static final int TEMPLATE_EVERY_N_MINUTES = 1;  // */{interval} * * * *
    static final int TEMPLATE_HOURLY = 2;           // 0 * * * *
    static final int TEMPLATE_EVERY_N_HOURS = 3;    // 0 */{interval} * * *
    static final int TEMPLATE_DAILY = 4;            // {mm} {hh} * * *
    static final int TEMPLATE_WEEKLY = 5;           // {mm} {hh} * * MON,WED,FRI
    static final int TEMPLATE_MONTHLY = 6;          // {mm} {hh} {dayOfMonth} * *

    /**
     * Сборка выражения из шаблона: POST /api/Cron/build.
     *
     * weekDays — дни недели числами: 0 = воскресенье … 6 = суббота.
     * includeSeconds=true добавляет шестое поле секунд (формат Quartz).
     */
    static String build(int template, int interval, String timeOfDay, int dayOfMonth,
                        List<Integer> weekDays, boolean includeSeconds)
            throws IOException, InterruptedException {
        StringBuilder days = new StringBuilder();
        for (int day : weekDays) {
            if (days.length() > 0) {
                days.append(',');
            }
            days.append(day);
        }

        String json = "{\"template\":" + template + ","
                + "\"interval\":" + interval + ","
                + "\"timeOfDay\":\"" + escape(timeOfDay) + "\","
                + "\"dayOfMonth\":" + dayOfMonth + ","
                + "\"weekDays\":[" + days + "],"
                + "\"includeSeconds\":" + includeSeconds + "}";
        return post("/api/Cron/build", json);
    }

    /** Cron-выражения не содержат кавычек и слешей, но экранирование дешевле доверия. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Разбор JSON ──────────────────────────────────────────────────────────
    // Пример намеренно оставлен без внешних зависимостей, чтобы запускаться одной
    // командой `java Main.java`. В рабочем проекте берите Jackson или Gson и
    // маппьте ответ в полноценную запись — эти регулярки существуют только ради
    // отсутствия pom.xml. Здесь они безопасны: все имена полей в ответе уникальны,
    // вложенность одна (объект timeZone), массивы плоские и состоят из строк.

    static String str(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"") : null;
    }

    static boolean bool(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() && "true".equals(matcher.group(1));
    }

    /** Плоский массив строк: "segments": ["0", "9", "*", "*", "1-5"] */
    static List<String> strings(String json, String field) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(json);
        if (!matcher.find()) {
            return values;
        }
        Matcher item = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(matcher.group(1));
        while (item.find()) {
            values.add(item.group(1));
        }
        return values;
    }

    // ── Применение данных: валидация расписания перед деплоем ─────────────────
    // Ответ API сам по себе — просто JSON. Ценность появляется, когда из него делают
    // вывод. Ключевой факт: сервер НЕ предупреждает о слишком частом расписании — на
    // «* 9 * * *» приходит isValid=true и пустой warnings. Он честно сообщает ФАКТЫ
    // (ближайшие запуски), а РЕШЕНИЕ принимает клиент. Ниже — ровно это решение.

    /**
     * «вт 2026-07-14 09:00 (+03:00)» — местное время запуска в его же таймзоне.
     *
     * OffsetDateTime сохраняет смещение из строки, поэтому getDayOfWeek() и формат дают
     * МЕСТНОЕ время запуска, а не время в таймзоне машины, где запущен пример.
     */
    static String formatOccurrence(String iso) {
        OffsetDateTime moment = OffsetDateTime.parse(iso);
        // DayOfWeek: понедельник = 1 … воскресенье = 7. Остаток от деления на 7 даёт
        // индекс в массиве, где 0 — воскресенье.
        String weekday = WEEKDAYS_RU[moment.getDayOfWeek().getValue() % 7];
        String offset = moment.getOffset().getId(); // «+03:00» либо «Z» для UTC
        return weekday + " " + moment.format(STAMP) + " (" + ("Z".equals(offset) ? "+00:00" : offset) + ")";
    }

    /**
     * Минимальный интервал между соседними запусками, в минутах. -1, если запусков
     * меньше двух: интервал не из чего вычислить.
     *
     * Именно минимальный, а не средний: расписание вида «0 9 * * 1-5» даёт разрыв в
     * трое суток на выходных, и среднее его размажет. Опасность — в самом плотном месте.
     */
    static double minIntervalMinutes(List<String> occurrences) {
        if (occurrences.size() < 2) {
            return -1;
        }

        double smallest = Double.MAX_VALUE;
        OffsetDateTime previous = OffsetDateTime.parse(occurrences.get(0));
        for (int index = 1; index < occurrences.size(); index++) {
            OffsetDateTime current = OffsetDateTime.parse(occurrences.get(index));
            double gap = Duration.between(previous, current).getSeconds() / 60.0;
            smallest = Math.min(smallest, gap);
            previous = current;
        }
        return smallest;
    }

    /** 1 → «1 мин», 90 → «90 мин (1 ч 30 мин)», 1440 → «1440 мин (1 д 0 ч)». */
    static String describeInterval(double minutes) {
        long total = Math.round(minutes);
        if (total < 60) {
            return total + " мин";
        }
        if (total < 1440) {
            return total + " мин (" + (total / 60) + " ч " + (total % 60) + " мин)";
        }
        return total + " мин (" + (total / 1440) + " д " + ((total % 1440) / 60) + " ч)";
    }

    static final class Report {
        int level = OK;
        final List<String> problems = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        void fail(int level, String problem) {
            this.level = Math.max(this.level, level);
            this.problems.add(problem);
        }
    }

    /** Вердикт по разобранному выражению. Уровень отчёта = код выхода программы. */
    static Report validateSchedule(String json) {
        Report report = new Report();

        // 1. Синтаксис. Сервер не бросает исключение на кривом выражении — он возвращает
        //    isValid=false и текст ошибки. Молча проигнорировать это — значит выкатить
        //    задачу, которая никогда не запустится.
        if (!bool(json, "isValid")) {
            String error = str(json, "error");
            report.fail(FAILED, (error == null || error.isEmpty()) ? "Выражение невалидно" : error);
            return report;
        }

        double interval = minIntervalMinutes(strings(json, "occurrences"));
        if (interval < 0) {
            report.notes.add("Запусков меньше двух — интервал вычислить не из чего.");
            return report;
        }

        report.notes.add("Минимальный интервал между запусками: " + describeInterval(interval) + ".");

        // 2. Главная проверка, которой НЕТ на сервере: слишком частое расписание.
        //    Классическая катастрофа — `* 9 * * *` вместо `0 9 * * *`: звёздочка в поле
        //    минут означает «каждую минуту часа», то есть 60 запусков в час вместо одного.
        //    Выражение при этом абсолютно валидно, и никто, кроме вас, не возразит.
        if (interval < DANGER_MINUTES) {
            report.fail(DANGER, "Задача запускается чаще, чем раз в " + DANGER_MINUTES
                    + " мин — это почти наверняка опечатка (например, «*» вместо «0» в поле минут)");
        } else if (interval < WARNING_MINUTES) {
            report.fail(WARNING, "Задача запускается чаще раза в час (интервал "
                    + Math.round(interval) + " мин) — убедитесь, что так и задумано");
        }

        // 3. Предупреждения самого сервера. Их немного, и на частоту они не реагируют,
        //    но, например, замену Quartz-символа «?» на «*» или отброшенное поле года
        //    сервер сообщает — это стоит показать.
        for (String warning : strings(json, "warnings")) {
            report.notes.add("Предупреждение API: " + warning);
        }

        return report;
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("Демо-ключ: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок —");
            System.out.println("cron считается локально, мокать нечего.\n");
        }

        String expression = args.length > 0 ? args[0] : DEFAULT_EXPRESSION;

        String json;
        try {
            json = evaluate(expression, TIME_ZONE, TAKE);
        } catch (AtloriumException error) {
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(FAILED); // проверка не выполнена — это не «всё хорошо»
            return;
        }

        Report report = validateSchedule(json);

        if (report.level == FAILED) {
            System.out.println("Выражение:      " + expression);
            for (String problem : report.problems) {
                System.out.println("  [!] " + problem);
            }
            System.out.println("\nВердикт: ОШИБКА — выражение невалидно.");
            System.out.println("Код выхода: " + FAILED);
            System.exit(FAILED);
            return;
        }

        List<String> occurrences = strings(json, "occurrences");

        System.out.println("Выражение:      " + str(json, "rawExpression"));
        System.out.println("Нормализовано:  " + str(json, "normalizedExpression"));
        System.out.println("Сегменты:       " + String.join(" | ", strings(json, "segments")));
        System.out.println("Таймзона:       " + str(json, "id") + " — " + str(json, "displayName"));
        if (bool(json, "supportsDaylightSavingTime")) {
            // Расчёт идёт по базе часовых поясов ОС, поэтому переход на летнее время
            // (там, где он есть) учитывается сам — вручную сдвигать часы не нужно.
            System.out.println("                переход на летнее время учитывается ("
                    + str(json, "daylightName") + ")");
        }

        System.out.println("\nБлижайшие " + occurrences.size() + " запусков:");
        for (int index = 0; index < occurrences.size(); index++) {
            System.out.println("  " + (index + 1) + ". " + formatOccurrence(occurrences.get(index)));
        }

        System.out.println();
        for (String note : report.notes) {
            System.out.println("  [i] " + note);
        }
        for (String problem : report.problems) {
            System.out.println("  [!] " + problem);
        }

        System.out.println();
        if (report.level == OK) {
            System.out.println("Вердикт: OK — расписание не чаще, чем раз в " + WARNING_MINUTES + " мин.");
        } else if (report.level == WARNING) {
            System.out.println("Вердикт: ВНИМАНИЕ — расписание частое, перепроверьте.");
        } else {
            System.out.println("Вердикт: ОПАСНО — расписание почти непрерывное, деплой стоит остановить.");
        }

        // Ключевая деталь: код выхода. Именно он делает из примера рабочую проверку
        // для CI — шаг workflow покраснеет сам, без внешнего парсинга вывода.
        System.out.println("Код выхода: " + report.level);
        System.exit(report.level);
    }
}
