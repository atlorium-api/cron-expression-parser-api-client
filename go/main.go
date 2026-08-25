// Клиент API разбора cron-выражений Atlorium — валидация расписания и ближайшие запуски.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//
//	go run .
//	go run . "* 9 * * *"
//
// Программа задумана как ПРОВЕРКА для CI, а не как «печаталка JSON»: она возвращает
// ненулевой код выхода, если выражение невалидно или расписание подозрительно частое.
//
//	0 — OK: реже, чем раз в 60 минут
//	1 — ВНИМАНИЕ: чаще раза в час — убедитесь, что так и задумано
//	2 — ОПАСНО: чаще раза в 5 минут — почти наверняка опечатка
//	3 — выражение невалидно или проверку выполнить не удалось (ошибка API или сети)
//
// Часовой пояс берётся из переменной окружения CRON_TZ (по умолчанию Europe/Moscow).
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// SandboxKey — публичный демо-ключ. ВАЖНО: на этом сервисе он возвращает НАСТОЯЩИЙ
// разбор, а не мок. Разбор cron — чистая локальная логика, внешних
// провайдеров нет, и мокать тут просто нечего. Ответ помечен заголовком
// X-Atlorium-Sandbox: true, но расписание в нём подлинное.
const SandboxKey = "ak_sandbox_demo_mockdata_v1"

// Пороги вердикта. Сервер честно считает расписание, но НЕ оценивает его разумность:
// на «* 9 * * *» он возвращает isValid=true и пустой warnings. Значит, вердикт выносит
// клиент.
//
// 5 минут — граница «задача практически не прекращает работать»: типовая опечатка
// `* 9 * * *` вместо `0 9 * * *` даёт 60 запусков в час вместо одного.
// 60 минут — граница «чаще раза в час»: бывает нужно, но стоит перепроверить.
const (
	DangerMinutes  = 5
	WarningMinutes = 60
)

// Take — сколько ближайших запусков запрашивать. Двух хватило бы для интервала, но пять
// наглядно показывают человеку, что расписание действительно то, которое он задумал.
const Take = 5

// DefaultExpression — расписание по умолчанию: каждый будний день в 9 утра.
const DefaultExpression = "0 9 * * 1-5"

// 429 — повтор один раз с паузой. MaxRetryDelay — потолок ожидания: исчерпав ЧАСОВОЙ
// лимит, сервер честно просит подождать десятки минут, и клиент, слепо доверяющий
// Retry-After, зависнет на всё это время (а в CI съест бюджет джоба). Дольше потолка
// не ждём.
const (
	RetryDelay    = 20 * time.Second
	MaxRetries    = 1
	MaxRetryDelay = 120 * time.Second
)

// Уровни вердикта. Значение уровня — это и есть код выхода программы.
const (
	LevelOK      = 0
	LevelWarning = 1
	LevelDanger  = 2
	LevelFailed  = 3
)

var weekdaysRu = [...]string{"вс", "пн", "вт", "ср", "чт", "пт", "сб"}

var (
	apiKey   = envOr("ATLORIUM_API_KEY", SandboxKey)
	baseURL  = envOr("ATLORIUM_BASE_URL", "https://atlorium.com")
	timeZone = envOr("CRON_TZ", "Europe/Moscow")
	client   = &http.Client{Timeout: 30 * time.Second}
)

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// CronTimeZone — сведения о часовом поясе, в котором посчитано расписание.
type CronTimeZone struct {
	ID                         string `json:"id"`
	HasIanaID                  bool   `json:"hasIanaId"`
	DisplayName                string `json:"displayName"`
	StandardName               string `json:"standardName"`
	DaylightName               string `json:"daylightName"`
	BaseUtcOffset              string `json:"baseUtcOffset"`
	SupportsDaylightSavingTime bool   `json:"supportsDaylightSavingTime"`
}

// CronEvaluation — ответ POST /api/Cron/evaluate.
type CronEvaluation struct {
	IsValid              bool     `json:"isValid"`
	RawExpression        string   `json:"rawExpression"`
	NormalizedExpression string   `json:"normalizedExpression"`
	Error                string   `json:"error"`
	Warnings             []string `json:"warnings"`
	Segments             []string `json:"segments"`
	// Occurrences — ближайшие запуски, ISO-8601 со смещением: «2026-07-14T09:00:00+03:00».
	Occurrences []string     `json:"occurrences"`
	TimeZone    CronTimeZone `json:"timeZone"`
}

// CronBuildResult — ответ POST /api/Cron/build.
type CronBuildResult struct {
	Expression string   `json:"expression"`
	Warnings   []string `json:"warnings"`
	Error      string   `json:"error"`
	Success    bool     `json:"success"`
}

// APIError раскладывает HTTP-код в человекочитаемую причину.
type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string {
	reasons := map[int]string{
		400: "cron-выражение не передано или тело запроса пустое",
		401: "API-ключ отсутствует, просрочен или недействителен",
		402: "недостаточно кредитов на балансе — пополните на https://atlorium.com",
		429: "превышен лимит запросов — повторите позже",
		500: "внутренняя ошибка при разборе выражения",
	}
	reason, ok := reasons[e.Status]
	if !ok {
		reason = "неизвестная ошибка"
	}
	return fmt.Sprintf("HTTP %d: %s. Ответ сервера: %s", e.Status, reason, e.Body)
}

// retryAfter сообщает, сколько ждать после 429. Мусор и слишком большие значения не
// берём на веру: 0 означало бы busy-loop, десятки минут — «спи почти час».
// Возвращаем 0, если ждать бессмысленно долго: вызывающий сдастся.
func retryAfter(response *http.Response) time.Duration {
	seconds, err := strconv.Atoi(response.Header.Get("Retry-After"))
	if err != nil || seconds <= 0 {
		return RetryDelay
	}
	delay := time.Duration(seconds) * time.Second
	if delay > MaxRetryDelay {
		return 0
	}
	return delay
}

// post — оба эндпоинта cron принимают только POST с JSON-телом. GET-варианта нет.
func post(path string, payload any, out any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	for attempt := 0; attempt <= MaxRetries; attempt++ {
		request, err := http.NewRequest(http.MethodPost, baseURL+path, bytes.NewReader(body))
		if err != nil {
			return err
		}
		request.Header.Set("Authorization", "Bearer "+apiKey)
		request.Header.Set("Accept", "application/json")
		request.Header.Set("Content-Type", "application/json")

		response, err := client.Do(request)
		if err != nil {
			return err
		}
		answer, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil {
			return err
		}

		if response.StatusCode == http.StatusTooManyRequests && attempt < MaxRetries {
			delay := retryAfter(response)
			if delay == 0 {
				break // ждать пришлось бы дольше потолка — не ждём
			}
			fmt.Fprintf(os.Stderr, "429: лимит запросов. Повтор через %s…\n", delay)
			time.Sleep(delay)
			continue
		}

		if response.StatusCode != http.StatusOK {
			return &APIError{Status: response.StatusCode, Body: string(answer)}
		}
		return json.Unmarshal(answer, out)
	}

	return &APIError{Status: http.StatusTooManyRequests, Body: "квота исчерпана, повтор бессмыслен"}
}

// ── Эндпоинты ────────────────────────────────────────────────────────────────

// Evaluate разбирает и валидирует выражение: POST /api/Cron/evaluate.
//
// ВНИМАНИЕ на имена полей — сервер не ругается на лишние, он их молча игнорирует и
// подставляет свои умолчания (UTC и 10 запусков). Опечатка в имени поля не даст ни 400,
// ни предупреждения — просто тихо не тот результат.
//
//	timeZoneId (НЕ timeZone), take (НЕ count).
//
// fromUtc (точка отсчёта) необязателен: пустая строка — сервер возьмёт «сейчас».
func Evaluate(expression, timeZoneID string, take int, fromUtc string) (*CronEvaluation, error) {
	payload := map[string]any{
		"expression": expression,
		"timeZoneId": timeZoneID,
		"take":       take,
	}
	if fromUtc != "" {
		payload["fromUtc"] = fromUtc
	}

	var result CronEvaluation
	if err := post("/api/Cron/evaluate", payload, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// Шаблоны конструктора: в OpenAPI-спеке это целое число без расшифровки.
// Значения подтверждены живыми запросами к /api/Cron/build.
const (
	TemplateEveryMinute   = 0 // * * * * *
	TemplateEveryNMinutes = 1 // */{interval} * * * *
	TemplateHourly        = 2 // 0 * * * *
	TemplateEveryNHours   = 3 // 0 */{interval} * * *
	TemplateDaily         = 4 // {mm} {hh} * * *
	TemplateWeekly        = 5 // {mm} {hh} * * MON,WED,FRI
	TemplateMonthly       = 6 // {mm} {hh} {dayOfMonth} * *
)

// BuildRequest — параметры конструктора выражений.
type BuildRequest struct {
	Template   int    `json:"template"`
	Interval   int    `json:"interval"`
	TimeOfDay  string `json:"timeOfDay"`
	DayOfMonth int    `json:"dayOfMonth"`
	// WeekDays — дни недели числами: 0 = воскресенье … 6 = суббота.
	WeekDays []int `json:"weekDays"`
	// IncludeSeconds=true добавляет шестое поле секунд (формат Quartz).
	IncludeSeconds bool `json:"includeSeconds"`
}

// Build собирает выражение из шаблона: POST /api/Cron/build.
func Build(request BuildRequest) (*CronBuildResult, error) {
	var result CronBuildResult
	if err := post("/api/Cron/build", request, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// ── Применение данных: валидация расписания перед деплоем ────────────────────
// Ответ API сам по себе — просто JSON. Ценность появляется, когда из него делают
// вывод. Ключевой факт: сервер НЕ предупреждает о слишком частом расписании — на
// «* 9 * * *» приходит isValid=true и пустой warnings. Он честно сообщает ФАКТЫ
// (ближайшие запуски), а РЕШЕНИЕ принимает клиент. Ниже — ровно это решение.

// parseInstant разбирает ISO-8601 со смещением: «2026-07-14T09:00:00+03:00».
// time.Parse сохраняет смещение из строки, поэтому Weekday() и Format() дают МЕСТНОЕ
// время запуска, а разность двух таких дат — корректный интервал в реальном времени.
func parseInstant(iso string) (time.Time, error) {
	return time.Parse(time.RFC3339, iso)
}

// FormatOccurrence печатает «вт 2026-07-14 09:00 (+03:00)».
func FormatOccurrence(iso string) string {
	moment, err := parseInstant(iso)
	if err != nil {
		return iso // формат неожиданный — показываем как есть, а не врём
	}
	return fmt.Sprintf("%s %s (%s)",
		weekdaysRu[int(moment.Weekday())],
		moment.Format("2006-01-02 15:04"),
		moment.Format("-07:00"))
}

// MinIntervalMinutes — минимальный интервал между соседними запусками, в минутах.
//
// Именно минимальный, а не средний: расписание вида «0 9 * * 1-5» даёт разрыв в трое
// суток на выходных, и среднее его размажет. Опасность — в самом плотном месте.
//
// Второе значение — false, если запусков меньше двух: интервал не из чего вычислить.
func MinIntervalMinutes(occurrences []string) (float64, bool) {
	if len(occurrences) < 2 {
		return 0, false
	}

	moments := make([]time.Time, 0, len(occurrences))
	for _, iso := range occurrences {
		moment, err := parseInstant(iso)
		if err != nil {
			return 0, false
		}
		moments = append(moments, moment)
	}

	smallest := math.Inf(1)
	for index := 1; index < len(moments); index++ {
		gap := moments[index].Sub(moments[index-1]).Minutes()
		if gap < smallest {
			smallest = gap
		}
	}
	return smallest, true
}

// DescribeInterval: 1 → «1 мин», 90 → «90 мин (1 ч 30 мин)», 1440 → «1440 мин (1 д 0 ч)».
func DescribeInterval(minutes float64) string {
	total := int(math.Round(minutes))
	switch {
	case total < 60:
		return fmt.Sprintf("%d мин", total)
	case total < 1440:
		return fmt.Sprintf("%d мин (%d ч %d мин)", total, total/60, total%60)
	default:
		return fmt.Sprintf("%d мин (%d д %d ч)", total, total/1440, (total%1440)/60)
	}
}

// Report — результат проверки расписания.
type Report struct {
	Level    int
	Problems []string
	Notes    []string
}

func (r *Report) fail(level int, problem string) {
	if level > r.Level {
		r.Level = level
	}
	r.Problems = append(r.Problems, problem)
}

// ValidateSchedule выносит вердикт по разобранному выражению.
// Уровень отчёта = код выхода программы.
func ValidateSchedule(result *CronEvaluation) *Report {
	report := &Report{Level: LevelOK}

	// 1. Синтаксис. Сервер не бросает исключение на кривом выражении — он возвращает
	//    isValid=false и текст ошибки. Молча проигнорировать это — значит выкатить
	//    задачу, которая никогда не запустится.
	if !result.IsValid {
		problem := result.Error
		if problem == "" {
			problem = "Выражение невалидно"
		}
		report.fail(LevelFailed, problem)
		return report
	}

	interval, ok := MinIntervalMinutes(result.Occurrences)
	if !ok {
		report.Notes = append(report.Notes, "Запусков меньше двух — интервал вычислить не из чего.")
		return report
	}

	report.Notes = append(report.Notes,
		"Минимальный интервал между запусками: "+DescribeInterval(interval)+".")

	// 2. Главная проверка, которой НЕТ на сервере: слишком частое расписание.
	//    Классическая катастрофа — `* 9 * * *` вместо `0 9 * * *`: звёздочка в поле
	//    минут означает «каждую минуту часа», то есть 60 запусков в час вместо одного.
	//    Выражение при этом абсолютно валидно, и никто, кроме вас, не возразит.
	switch {
	case interval < DangerMinutes:
		report.fail(LevelDanger, fmt.Sprintf(
			"Задача запускается чаще, чем раз в %d мин — это почти наверняка опечатка "+
				"(например, «*» вместо «0» в поле минут)", DangerMinutes))
	case interval < WarningMinutes:
		report.fail(LevelWarning, fmt.Sprintf(
			"Задача запускается чаще раза в час (интервал %d мин) — убедитесь, что так и задумано",
			int(math.Round(interval))))
	}

	// 3. Предупреждения самого сервера. Их немного, и на частоту они не реагируют,
	//    но, например, замену Quartz-символа «?» на «*» или отброшенное поле года
	//    сервер сообщает — это стоит показать.
	for _, warning := range result.Warnings {
		report.Notes = append(report.Notes, "Предупреждение API: "+warning)
	}

	return report
}

func main() {
	os.Exit(run())
}

func run() int {
	if apiKey == SandboxKey {
		fmt.Println("Демо-ключ: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок —")
		fmt.Println("cron считается локально, мокать нечего.")
		fmt.Println()
	}

	expression := DefaultExpression
	if len(os.Args) > 1 {
		expression = os.Args[1]
	}

	result, err := Evaluate(expression, timeZone, Take, "")
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		return LevelFailed // проверка не выполнена — это не «всё хорошо»
	}

	report := ValidateSchedule(result)

	if report.Level == LevelFailed {
		fmt.Printf("Выражение:      %s\n", expression)
		for _, problem := range report.Problems {
			fmt.Println("  [!]", problem)
		}
		fmt.Println("\nВердикт: ОШИБКА — выражение невалидно.")
		fmt.Printf("Код выхода: %d\n", LevelFailed)
		return LevelFailed
	}

	zone := result.TimeZone

	fmt.Printf("Выражение:      %s\n", result.RawExpression)
	fmt.Printf("Нормализовано:  %s\n", result.NormalizedExpression)
	fmt.Printf("Сегменты:       %s\n", strings.Join(result.Segments, " | "))
	fmt.Printf("Таймзона:       %s — %s\n", zone.ID, zone.DisplayName)
	if zone.SupportsDaylightSavingTime {
		// Расчёт идёт по базе часовых поясов ОС, поэтому переход на летнее время
		// (там, где он есть) учитывается сам — вручную сдвигать часы не нужно.
		fmt.Printf("                переход на летнее время учитывается (%s)\n", zone.DaylightName)
	}

	fmt.Printf("\nБлижайшие %d запусков:\n", len(result.Occurrences))
	for index, iso := range result.Occurrences {
		fmt.Printf("  %d. %s\n", index+1, FormatOccurrence(iso))
	}

	fmt.Println()
	for _, note := range report.Notes {
		fmt.Println("  [i]", note)
	}
	for _, problem := range report.Problems {
		fmt.Println("  [!]", problem)
	}

	fmt.Println()
	switch report.Level {
	case LevelOK:
		fmt.Printf("Вердикт: OK — расписание не чаще, чем раз в %d мин.\n", WarningMinutes)
	case LevelWarning:
		fmt.Println("Вердикт: ВНИМАНИЕ — расписание частое, перепроверьте.")
	default:
		fmt.Println("Вердикт: ОПАСНО — расписание почти непрерывное, деплой стоит остановить.")
	}

	// Ключевая деталь: код выхода. Именно он делает из примера рабочую проверку
	// для CI — шаг workflow покраснеет сам, без внешнего парсинга вывода.
	fmt.Printf("Код выхода: %d\n", report.Level)
	return report.Level
}
