/**
 * Клиент API разбора cron-выражений Atlorium — валидация расписания и ближайшие запуски.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   npm install
 *   npm start
 *   npm start -- "* 9 * * *"
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

/**
 * Публичный демо-ключ. ВАЖНО: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок.
 * Разбор cron — чистая локальная логика, внешних источников нет,
 * и мокать тут просто нечего. Ответ помечен заголовком X-Atlorium-Sandbox: true, но
 * расписание в нём подлинное — примерам из README можно верить как есть.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const API_KEY = process.env.ATLORIUM_API_KEY ?? SANDBOX_KEY;
const BASE_URL = process.env.ATLORIUM_BASE_URL ?? 'https://atlorium.com';

/** Часовой пояс расписания. IANA-идентификатор: Europe/Moscow, America/New_York, UTC. */
const TIME_ZONE = process.env.CRON_TZ ?? 'Europe/Moscow';

const TIMEOUT_MS = 30_000;

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
export const Level = { OK: 0, WARNING: 1, DANGER: 2, FAILED: 3 } as const;
export type Level = (typeof Level)[keyof typeof Level];

const WEEKDAYS_RU = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'] as const;

/** Сведения о часовом поясе, в котором посчитано расписание. */
export interface CronTimeZone {
  id: string;
  hasIanaId: boolean;
  displayName: string;
  standardName: string;
  daylightName: string;
  baseUtcOffset: string;
  supportsDaylightSavingTime: boolean;
}

/** Ответ POST /api/Cron/evaluate. */
export interface CronEvaluation {
  isValid: boolean;
  rawExpression: string;
  normalizedExpression: string;
  error: string;
  warnings: string[];
  segments: string[];
  /** Ближайшие запуски, ISO-8601 со смещением: «2026-07-14T09:00:00+03:00». */
  occurrences: string[];
  timeZone: CronTimeZone;
}

/** Ответ POST /api/Cron/build. */
export interface CronBuildResult {
  expression: string;
  warnings: string[];
  error: string;
  success: boolean;
}

const ERROR_REASONS: Record<number, string> = {
  400: 'Cron-выражение не передано или тело запроса пустое',
  401: 'API-ключ отсутствует, просрочен или недействителен',
  402: 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
  429: 'Превышен лимит запросов — повторите позже',
  500: 'Внутренняя ошибка при разборе выражения',
};

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
export class AtloriumError extends Error {
  constructor(readonly status: number, body: string) {
    const reason = ERROR_REASONS[status] ?? 'Неизвестная ошибка';
    super(`HTTP ${status}: ${reason}. Ответ сервера: ${body.slice(0, 200)}`);
    this.name = 'AtloriumError';
  }
}

const sleep = (seconds: number) => new Promise((resolve) => setTimeout(resolve, seconds * 1000));

/**
 * Сколько ждать после 429. Мусор и слишком большие значения не берём на веру:
 * 0 означало бы busy-loop, десятки минут — «спи почти час». Возвращаем 0, если ждать
 * бессмысленно долго: вызывающий сдастся и честно скажет, что квота исчерпана.
 */
function retryAfter(response: Response): number {
  const seconds = Number.parseInt(response.headers.get('Retry-After') ?? '', 10);
  if (!Number.isFinite(seconds) || seconds <= 0) return RETRY_DELAY;
  return seconds <= MAX_RETRY_DELAY ? seconds : 0;
}

/** Оба эндпоинта cron — POST с JSON-телом. GET-варианта у них нет. */
async function post<T>(path: string, payload: unknown): Promise<T> {
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt += 1) {
    const response = await fetch(new URL(path, BASE_URL), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${API_KEY}`,
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });

    if (response.status === 429 && attempt < MAX_RETRIES) {
      const delay = retryAfter(response);
      if (delay === 0) break; // ждать пришлось бы дольше потолка — не ждём
      console.error(`429: лимит запросов. Повтор через ${delay} с…`);
      await sleep(delay);
      continue;
    }

    if (!response.ok) {
      throw new AtloriumError(response.status, await response.text());
    }
    return (await response.json()) as T;
  }

  throw new AtloriumError(429, 'Квота исчерпана, повтор бессмыслен');
}

// ── Эндпоинты ────────────────────────────────────────────────────────────────

/**
 * Разбор и валидация выражения: POST /api/Cron/evaluate.
 *
 * ВНИМАНИЕ на имена полей — сервер не ругается на лишние, он их молча игнорирует
 * и подставляет свои умолчания (UTC и 10 запусков). Опечатка в имени поля не даст
 * ни 400, ни предупреждения — просто тихо не тот результат.
 *
 *   timeZoneId (НЕ timeZone), take (НЕ count).
 */
export async function evaluate(
  expression: string,
  timeZoneId = 'UTC',
  take = 10,
  fromUtc?: string,
): Promise<CronEvaluation> {
  // fromUtc — точка отсчёта. Не задана — сервер берёт «сейчас».
  return post<CronEvaluation>('/api/Cron/evaluate', {
    expression,
    timeZoneId,
    take,
    ...(fromUtc === undefined ? {} : { fromUtc }),
  });
}

/**
 * Шаблоны конструктора: в OpenAPI-спеке это целое число без расшифровки.
 * Значения подтверждены живыми запросами к /api/Cron/build.
 */
export const Template = {
  EveryMinute: 0, // * * * * *
  EveryNMinutes: 1, // */{interval} * * * *
  Hourly: 2, // 0 * * * *
  EveryNHours: 3, // 0 */{interval} * * *
  Daily: 4, // {mm} {hh} * * *
  Weekly: 5, // {mm} {hh} * * MON,WED,FRI
  Monthly: 6, // {mm} {hh} {dayOfMonth} * *
} as const;

export interface BuildOptions {
  interval?: number;
  timeOfDay?: string;
  dayOfMonth?: number;
  /** Дни недели числами: 0 = воскресенье … 6 = суббота. */
  weekDays?: number[];
  /** true добавляет шестое поле секунд (формат Quartz). */
  includeSeconds?: boolean;
}

/** Сборка выражения из шаблона: POST /api/Cron/build. */
export async function build(
  template: number,
  options: BuildOptions = {},
): Promise<CronBuildResult> {
  return post<CronBuildResult>('/api/Cron/build', {
    template,
    interval: options.interval ?? 1,
    timeOfDay: options.timeOfDay ?? '09:00:00',
    dayOfMonth: options.dayOfMonth ?? 1,
    weekDays: options.weekDays ?? [1],
    includeSeconds: options.includeSeconds ?? false,
  });
}

// ── Применение данных: валидация расписания перед деплоем ────────────────────
// Ответ API сам по себе — просто JSON. Ценность появляется, когда из него делают
// вывод. Ключевой факт: сервер НЕ предупреждает о слишком частом расписании — на
// «* 9 * * *» приходит isValid=true и пустой warnings. Он честно сообщает ФАКТЫ
// (ближайшие запуски), а РЕШЕНИЕ принимает клиент. Ниже — ровно это решение.

/**
 * «вт 2026-07-14 09:00 (+03:00)» — местное время запуска в его же таймзоне.
 *
 * Date в JavaScript хранит только момент времени и теряет исходное смещение, поэтому
 * дату и время берём прямо из строки, а день недели считаем, подменив смещение на «Z»:
 * тогда getUTCDay() вернёт день недели МЕСТНОЙ даты запуска, а не даты в таймзоне,
 * которая случайно оказалась настроена на машине.
 */
export function formatOccurrence(iso: string): string {
  const weekday = WEEKDAYS_RU[new Date(`${iso.slice(0, 19)}Z`).getUTCDay()] ?? '??';
  const match = /([+-]\d{2}:\d{2}|Z)$/.exec(iso);
  const offset = !match || match[1] === 'Z' ? '+00:00' : match[1];
  return `${weekday} ${iso.slice(0, 10)} ${iso.slice(11, 16)} (${offset})`;
}

/**
 * Минимальный интервал между соседними запусками, в минутах.
 *
 * Именно минимальный, а не средний: расписание вида «0 9 * * 1-5» даёт разрыв в
 * трое суток на выходных, и среднее его размажет. Опасность — в самом плотном месте.
 *
 * null, если запусков меньше двух: интервал не из чего вычислить.
 */
export function minIntervalMinutes(occurrences: string[]): number | null {
  if (occurrences.length < 2) return null;

  // А вот здесь Date подходит идеально: нужен именно момент времени, и он разбирается
  // со смещением корректно.
  const moments = occurrences.map((iso) => new Date(iso).getTime());

  let smallest = Number.POSITIVE_INFINITY;
  for (let index = 1; index < moments.length; index += 1) {
    const gap = ((moments[index] as number) - (moments[index - 1] as number)) / 60_000;
    if (gap < smallest) smallest = gap;
  }
  return smallest;
}

/** 1 → «1 мин», 90 → «90 мин (1 ч 30 мин)», 1440 → «1440 мин (1 д 0 ч)». */
export function describeInterval(minutes: number): string {
  const total = Math.round(minutes);
  if (total < 60) return `${total} мин`;
  if (total < 1440) return `${total} мин (${Math.floor(total / 60)} ч ${total % 60} мин)`;
  return `${total} мин (${Math.floor(total / 1440)} д ${Math.floor((total % 1440) / 60)} ч)`;
}

export interface Report {
  level: Level;
  problems: string[];
  notes: string[];
}

/** Вердикт по разобранному выражению. Уровень отчёта = код выхода программы. */
export function validateSchedule(result: CronEvaluation): Report {
  const problems: string[] = [];
  const notes: string[] = [];
  let level: Level = Level.OK;

  const fail = (next: Level, problem: string): void => {
    level = Math.max(level, next) as Level;
    problems.push(problem);
  };

  // 1. Синтаксис. Сервер не бросает исключение на кривом выражении — он возвращает
  //    isValid=false и текст ошибки. Молча проигнорировать это — значит выкатить
  //    задачу, которая никогда не запустится.
  if (!result.isValid) {
    fail(Level.FAILED, result.error || 'Выражение невалидно');
    return { level, problems, notes };
  }

  const interval = minIntervalMinutes(result.occurrences ?? []);

  if (interval === null) {
    notes.push('Запусков меньше двух — интервал вычислить не из чего.');
    return { level, problems, notes };
  }

  notes.push(`Минимальный интервал между запусками: ${describeInterval(interval)}.`);

  // 2. Главная проверка, которой НЕТ на сервере: слишком частое расписание.
  //    Классическая катастрофа — `* 9 * * *` вместо `0 9 * * *`: звёздочка в поле
  //    минут означает «каждую минуту часа», то есть 60 запусков в час вместо одного.
  //    Выражение при этом абсолютно валидно, и никто, кроме вас, не возразит.
  if (interval < DANGER_MINUTES) {
    fail(
      Level.DANGER,
      `Задача запускается чаще, чем раз в ${DANGER_MINUTES} мин — `
      + 'это почти наверняка опечатка (например, «*» вместо «0» в поле минут)',
    );
  } else if (interval < WARNING_MINUTES) {
    fail(
      Level.WARNING,
      `Задача запускается чаще раза в час (интервал ${Math.round(interval)} мин) — `
      + 'убедитесь, что так и задумано',
    );
  }

  // 3. Предупреждения самого сервера. Их немного, и на частоту они не реагируют,
  //    но, например, замену Quartz-символа «?» на «*» или отброшенное поле года
  //    сервер сообщает — это стоит показать.
  for (const warning of result.warnings ?? []) {
    notes.push(`Предупреждение API: ${warning}`);
  }

  return { level, problems, notes };
}

async function main(): Promise<number> {
  if (API_KEY === SANDBOX_KEY) {
    console.log('Демо-ключ: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок —');
    console.log('cron считается локально, мокать нечего.\n');
  }

  const expression = process.argv[2] ?? DEFAULT_EXPRESSION;

  let result: CronEvaluation;
  try {
    result = await evaluate(expression, TIME_ZONE, TAKE);
  } catch (error: unknown) {
    console.error('Ошибка:', error instanceof Error ? error.message : error);
    return Level.FAILED; // проверка не выполнена — это не «всё хорошо»
  }

  const report = validateSchedule(result);

  if (report.level === Level.FAILED) {
    console.log(`Выражение:      ${expression}`);
    report.problems.forEach((problem) => console.log(`  [!] ${problem}`));
    console.log('\nВердикт: ОШИБКА — выражение невалидно.');
    console.log(`Код выхода: ${Level.FAILED}`);
    return Level.FAILED;
  }

  const zone = result.timeZone;

  console.log(`Выражение:      ${result.rawExpression}`);
  console.log(`Нормализовано:  ${result.normalizedExpression}`);
  console.log(`Сегменты:       ${(result.segments ?? []).join(' | ')}`);
  console.log(`Таймзона:       ${zone.id} — ${zone.displayName}`);
  if (zone.supportsDaylightSavingTime) {
    // Расчёт идёт по базе часовых поясов ОС, поэтому переход на летнее время
    // (там, где он есть) учитывается сам — вручную сдвигать часы не нужно.
    console.log(`                переход на летнее время учитывается (${zone.daylightName})`);
  }

  console.log(`\nБлижайшие ${result.occurrences.length} запусков:`);
  result.occurrences.forEach((iso, index) => {
    console.log(`  ${index + 1}. ${formatOccurrence(iso)}`);
  });

  console.log();
  report.notes.forEach((note) => console.log(`  [i] ${note}`));
  report.problems.forEach((problem) => console.log(`  [!] ${problem}`));

  console.log();
  if (report.level === Level.OK) {
    console.log(`Вердикт: OK — расписание не чаще, чем раз в ${WARNING_MINUTES} мин.`);
  } else if (report.level === Level.WARNING) {
    console.log('Вердикт: ВНИМАНИЕ — расписание частое, перепроверьте.');
  } else {
    console.log('Вердикт: ОПАСНО — расписание почти непрерывное, деплой стоит остановить.');
  }

  // Ключевая деталь: код выхода. Именно он делает из примера рабочую проверку
  // для CI — шаг workflow покраснеет сам, без внешнего парсинга вывода.
  console.log(`Код выхода: ${report.level}`);
  return report.level;
}

// Запуск только когда файл выполняется напрямую, а не импортируется.
if (process.argv[1]?.includes('index')) {
  main().then((code) => process.exit(code));
}
