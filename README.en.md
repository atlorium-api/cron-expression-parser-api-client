# Cron Expression Parser API — validate cron, get next run times

[Русский](README.md) · **English**

[![Live API tests](https://github.com/atlorium-api/cron-expression-parser-api-client/actions/workflows/examples.yml/badge.svg)](https://github.com/atlorium-api/cron-expression-parser-api-client/actions/workflows/examples.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-Swagger-brightgreen)](https://atlorium.com/cronAPI)

Ready-to-run examples for the **cron expression parser API** in six languages: **Python, TypeScript (Node.js), Go, Java, C#, PHP.**
**Validate a cron expression**, get the **next run time**, and see a **cron schedule in any timezone** — daylight saving transitions included. Plus the reverse: build a **crontab** expression from a template ("every 15 minutes", "weekdays at 9am") without remembering which field goes where.

Every example **runs out of the box — no signup, no key, no card.** A public demo key is baked in.

```bash
git clone https://github.com/atlorium-api/cron-expression-parser-api-client
cd cron-expression-parser-api-client/python && pip install -r requirements.txt && python main.py
```

```
Демо-ключ: на этом сервисе он возвращает НАСТОЯЩИЙ разбор, а не мок —
cron считается локально, мокать нечего.

Выражение:      0 9 * * 1-5
Нормализовано:  0 9 * * 1-5
Сегменты:       0 | 9 | * | * | 1-5
Таймзона:       Europe/Moscow — (UTC+03:00) Moscow Time
                переход на летнее время учитывается (Moscow Summer Time)

Ближайшие 5 запусков:
  1. вт 2026-07-14 09:00 (+03:00)
  2. ср 2026-07-15 09:00 (+03:00)
  3. чт 2026-07-16 09:00 (+03:00)
  4. пт 2026-07-17 09:00 (+03:00)
  5. пн 2026-07-20 09:00 (+03:00)

  [i] Минимальный интервал между запусками: 1440 мин (1 д 0 ч).

Вердикт: OK — расписание не чаще, чем раз в 60 мин.
Код выхода: 0
```

(The examples print in Russian — the code comments are Russian too. The API itself is language-neutral.)

> **The demo key returns a real result here, not a mock.** That is unusual across Atlorium services and worth stating plainly: cron parsing is pure local logic, there is no upstream data source, and **there is nothing to fake**. Responses still carry the `X-Atlorium-Sandbox: true` header, but the schedule inside is genuine — timezone applied, DST applied. **Every output in this README is real**, captured from actual runs. Services backed by external sources (company registry, BIN, weather) do return plausible mock data in the sandbox; this one does not.

---

## The point: the example returns a non-zero exit code

This is not a JSON printer — it is a **working cron schedule check for CI**. The program decides for itself whether the schedule is sane and reports it through the exit code, so nothing needs to parse its output:

| Exit code | When |
|---|---|
| `0` | **OK** — the schedule fires less often than once every 60 minutes |
| `1` | **WARNING** — fires more than once an hour: make sure that is intended |
| `2` | **DANGER** — fires more than once every 5 minutes: almost certainly a typo |
| `3` | **FAILED** — the expression is invalid, or the check could not run (API or network error) |

### Why: a one-character catastrophe

The classic scheduler outage is **`* 9 * * *` instead of `0 9 * * *`**.

Intended: "every day at 9:00". Actual: **"every minute from 9:00 to 9:59"** — 60 runs an hour instead of one. An asterisk in the minute field does not mean "at the top of the hour", it means "at every minute". The expression is **perfectly valid**, cron accepts it silently, and you find out from the cloud bill or the dead database.

Here is what the same example prints for that expression — a **real run**, `python main.py "* 9 * * *"`:

```
Выражение:      * 9 * * *
Нормализовано:  * 9 * * *
Сегменты:       * | 9 | * | * | *
Таймзона:       Europe/Moscow — (UTC+03:00) Moscow Time
                переход на летнее время учитывается (Moscow Summer Time)

Ближайшие 5 запусков:
  1. вт 2026-07-14 09:00 (+03:00)
  2. вт 2026-07-14 09:01 (+03:00)
  3. вт 2026-07-14 09:02 (+03:00)
  4. вт 2026-07-14 09:03 (+03:00)
  5. вт 2026-07-14 09:04 (+03:00)

  [i] Минимальный интервал между запусками: 1 мин (1 мин).
  [!] Задача запускается чаще, чем раз в 5 мин — это почти наверняка опечатка (например, «*» вместо «0» в поле минут)

Вердикт: ОПАСНО — расписание почти непрерывное, деплой стоит остановить.
Код выхода: 2
```

One character apart. The list of upcoming runs makes the difference obvious in a second.

### The core idea: the server states facts, the client makes the call

**The API does not warn you about an over-frequent schedule.** For `* 9 * * *` it honestly returns `isValid: true` and an **empty** `warnings` array — syntactically the expression is flawless, and that is correct: a parser cannot know what you meant.

So the `validateSchedule()` function in each of the six examples:

1. Takes the **facts** from the API — the `occurrences[]` list of upcoming runs.
2. Computes the **minimum gap** between adjacent runs **itself**.
3. Compares it against thresholds (`DANGER_MINUTES = 5`, `WARNING_MINUTES = 60`) and **issues a verdict**.

The gap is the **minimum**, not the average: a `0 9 * * 1-5` schedule has a three-day hole over the weekend, and an average would smear it out. The danger always lives in the tightest spot.

### Wiring it into CI

A non-zero exit code means the GitHub Actions step goes red on its own — no `grep` over stdout:

```yaml
name: cron-lint

on: [push, pull_request]

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - run: pip install -r requirements.txt
        working-directory: python
      # Validate the schedule before deploying. A `* 9 * * *` typo turns the job red.
      - run: python main.py "$(yq '.schedule' deploy/job.yaml)"
        working-directory: python
        env:
          CRON_TZ: Europe/Moscow
          ATLORIUM_API_KEY: ${{ secrets.ATLORIUM_API_KEY }}
```

## What it is for

A cron expression is one of the few formats you **cannot proofread by eye**. `*/7 * * * *` is not "every 7 minutes" — it is "at minute 0, 7, 14, 21, 28, 35, 42, 49 and 56", so the gap between minute 56 and the next zero is 4 minutes, not 7. `0 0 1 * 1` means "on the 1st **or** on Mondays" in a system crontab, but "on the 1st **if** it is a Monday" here (see the section below — this divergence matters). You only see these things in a list of actual run times.

Typical uses:
- **CI validation** before rolling out a schedule — exactly what `validateSchedule()` does.
- **Showing users** "next run: tomorrow at 09:00" next to a cron input field.
- **A schedule builder** so nobody hand-writes cron: `/api/Cron/build` assembles the expression from a template.
- **Re-basing schedules across timezones** when infrastructure moves.

## Quick start

Try the API without cloning anything:

```bash
curl -X POST "https://atlorium.com/api/Cron/evaluate" \
     -H "Authorization: Bearer ak_sandbox_demo_mockdata_v1" \
     -H "Content-Type: application/json" \
     -d '{"expression":"0 9 * * 1-5","timeZoneId":"Europe/Moscow","take":3}'
```

| Language | Run | Requires |
|----------|-----|----------|
| [Python](python/) | `pip install -r requirements.txt && python main.py` | Python 3.10+ |
| [TypeScript / Node.js](node/) | `npm install && npm start` | Node.js 20+ |
| [Go](go/) | `go run .` | Go 1.22+ |
| [Java](java/) | `java Main.java` | JDK 11+ (no dependencies) |
| [C#](csharp/) | `dotnet run` | .NET 8+ |
| [PHP](php/) | `php main.php` | PHP 8.1+ |

Pass your own expression as an argument: `python main.py "*/15 * * * *"`
Change the timezone: `CRON_TZ=America/New_York python main.py`

## Authentication

The key goes in the `Authorization` header:

```
Authorization: Bearer YOUR_KEY
```

| Key | Behaviour |
|-----|-----------|
| `ak_sandbox_demo_mockdata_v1` | **Demo key.** Public, shared by everyone. No account, no charge. **On this service it returns a real parse**, not a mock — there is nothing to fake |
| Live key | The same thing, on your own account: the limit is counted per key, not per shared public IP. Get one at [atlorium.com](https://atlorium.com) |

Switching to a live key requires **no code changes** — every example reads an environment variable:

```bash
export ATLORIUM_API_KEY="ak_your_live_key"
```

Every sandbox response carries the header `X-Atlorium-Sandbox: true`.

## Endpoints

Base URL: `https://atlorium.com`

Both endpoints are **`POST` with a JSON body**. There is no GET variant and no "root" `/api/cron` route (it returns `404`).

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/Cron/evaluate` | Parse and validate an expression + upcoming runs in a given timezone |
| `POST` | `/api/Cron/build` | The reverse: assemble an expression from a schedule template |

### `POST /api/Cron/evaluate`

Request body (`CronEvaluateRequest`):

| Field | Type | Description |
|-------|------|-------------|
| `expression` | string | **Required.** Cron expression: 5 fields (`min hour day month dow`) or 6 (leading seconds field, Quartz style) |
| `timeZoneId` | string | Timezone to compute runs in. IANA: `Europe/Moscow`, `America/New_York`, `UTC`. Defaults to **`UTC`** |
| `fromUtc` | datetime\|null | Starting point. Defaults to "now" |
| `take` | int | How many upcoming runs to return. 1 to 100, defaults to **10**. Out-of-range values are not rejected but **silently clamped**: `0` yields 1 occurrence, `1000` yields 100 |

> **Mind the field names.** The field is **`timeZoneId`**, not `timeZone`, and **`take`**, not `count`. Unknown fields are **silently ignored** and the server falls back to its defaults (UTC, 10 occurrences) — you get no `400` and no warning, just quietly wrong results. A typo in a field name costs more here than usual.

### `POST /api/Cron/build`

Request body (`CronBuildRequest`). In the OpenAPI spec `template` is a bare integer with no enum names; **the values below were confirmed with live requests**:

| Field | Type | Description |
|-------|------|-------------|
| `template` | int | Template type, see the table below |
| `interval` | int | Step for templates `1` and `3` (every N minutes / hours) |
| `timeOfDay` | string | Run time, `"09:30:00"` — for templates `4`, `5`, `6` |
| `dayOfMonth` | int | Day of month 1–31 — for template `6` |
| `weekDays` | int[] | Days of week — for template `5`. **`0` = Sunday … `6` = Saturday** |
| `includeSeconds` | bool | Add a leading seconds field (Quartz, 6 fields). **Defaults to `true`** — omit it and you get a six-field expression, not the familiar five-field one |

| `template` | Meaning | Sample response (`includeSeconds: false`) |
|---|---|---|
| `0` | Every minute | `* * * * *` |
| `1` | Every N minutes (`interval`) | `*/15 * * * *` |
| `2` | Hourly | `0 * * * *` |
| `3` | Every N hours (`interval`) | `0 */15 * * *` |
| `4` | Daily at `timeOfDay` | `30 9 * * *` |
| `5` | On `weekDays` at `timeOfDay` | `0 9 * * MON,WED,FRI` |
| `6` | Monthly on `dayOfMonth` at `timeOfDay` | `30 9 15 * *` |

Response (`CronBuildResult`): `{ "expression": "...", "warnings": [], "error": "", "success": true }`

## `evaluate` response fields

| Field | Type | Meaning |
|-------|------|---------|
| `isValid` | bool | **The key field.** The expression parsed and at least one run was found |
| `error` | string | Error text when `isValid: false`. Empty string otherwise |
| `rawExpression` | string | The expression exactly as submitted |
| `normalizedExpression` | string | After normalisation: collapsed whitespace, Quartz `?` rewritten to `*`, year field dropped |
| `segments` | string[] | The normalised expression split into fields: `["0","9","*","*","1-5"]` |
| `occurrences` | datetime[] | **Upcoming run times** in the requested timezone, ISO-8601 with offset: `2026-07-14T09:00:00+03:00`. The reason you call this API |
| `warnings` | string[] | Normalisation warnings (`?` rewrite, dropped year field). **The server does NOT warn about an over-frequent schedule** — that is the client's job |
| `timeZone` | object | The resolved timezone, see below |

The `timeZone` object:

| Field | Type | Meaning |
|-------|------|---------|
| `id` | string | Identifier: `Europe/Moscow` |
| `hasIanaId` | bool | Whether the ID resolved as IANA (rather than a Windows name like `Russian Standard Time`) |
| `displayName` | string | Human-readable name: `(UTC+03:00) Moscow Time` |
| `standardName` | string | Standard-time name: `Moscow Standard Time` |
| `daylightName` | string | Daylight-time name: `Moscow Summer Time` |
| `baseUtcOffset` | string | Base offset from UTC: `03:00:00` |
| `supportsDaylightSavingTime` | bool | Whether the zone defines a DST transition at all |

## Error handling

| Code | Cause | What to do |
|------|-------|------------|
| `400` | Empty body, or `expression` not supplied | Send a JSON body with a non-empty `expression` |
| `401` | Key missing, expired or invalid | Check the `Authorization` header |
| `402` | Insufficient credit balance | Top up at [atlorium.com](https://atlorium.com) |
| `404` | No such route | Remember: there is no root `/api/cron`, only `/evaluate` and `/build` |
| `429` | Rate limit exceeded | Retry with backoff — **but cap the wait** (see below) |
| `500` | Internal parsing error | Tell us: support@atlorium.com |

**A malformed expression is NOT an HTTP error.** The server answers `200 OK` with `isValid: false` and a message in `error`. That is by design: the parse ran, the result was simply negative. A client that only checks the HTTP status will happily ship an invalid schedule — the examples check `isValid`.

All six examples map HTTP codes to human-readable causes — see the `AtloriumError` class.

**About 429 and the wait cap.** Once the hourly quota is gone, the server honestly asks you to wait tens of minutes. A client that blindly sleeps for as long as it is told hangs for that entire time (and in CI just burns the job budget). Hence `MAX_RETRY_DELAY = 120` seconds in the examples: we never wait longer than the cap — we report the quota as exhausted and exit with code `3`.

## Pricing and limits

**Pay-as-you-go, no subscription** — you pay per successful request.

The limits on this service are generous, because it computes everything locally, so calling `/evaluate` from a UI on every keystroke is genuinely practical. Current values: [atlorium.com/pricing](https://atlorium.com/pricing).

Current prices and limits: **[atlorium.com/pricing](https://atlorium.com/pricing)**

## FAQ

**How do I get the next run time of a cron expression?** One POST to `/api/Cron/evaluate` — `occurrences[0]` is the next run, already converted to the timezone you asked for. `take` controls how many to return (up to 100).

**Is daylight saving time handled?** Yes. The calculation uses the operating system's timezone database, so the clock shift is applied for you. Easy to confirm: `0 9 * * *` in `Europe/Berlin` yields `09:00+01:00` in winter and `09:00+02:00` in summer — local run time stays at 9am while the UTC offset moves. That is exactly what you want, and exactly what breaks when schedules are hand-computed in UTC. Separately: `Europe/Moscow` reports `supportsDaylightSavingTime: true` (that is how the zone is flagged in the database), but the actual offset is `+03:00` year-round — Russia abolished the transition in 2014, and the run times reflect that correctly.

**Why not just use croniter / cron-parser in my own code?** Do, if you already have a library you trust for your language. The API earns its keep where there is no such library or you would rather not add one: one check across **six languages at once**, a CI step with no dependencies to install, validating user input without shipping a parser to the browser. And one parsing semantics across a polyglot fleet instead of three libraries that quietly disagree about `*/7`.

**Why doesn't the API warn me that a schedule is too frequent?** Because frequency is a property of your job, not of the expression: `*/1 * * * *` is normal for a metrics scraper and catastrophic for an email blast. The server supplies **facts** (when it will actually fire); the client decides. The decision logic ships ready-made as `validateSchedule()` in all six examples, with the thresholds as constants.

**Is Quartz syntax with seconds supported?** Yes. Six fields — the first is seconds. The Quartz `?` character is accepted and normalised to `*`, which shows up in `warnings`. A 7th year field is dropped, also with a warning.

## An important divergence from system crontab: day-of-month AND day-of-week

Know this before you move an expression out of a working crontab.

When an expression restricts **both** the day-of-month **and** the day-of-week (neither is `*`), classic system cron (Vixie/POSIX, the one on Linux) joins them with **OR**: `0 0 1 * 1` fires **both** on the 1st of every month **and** every Monday.

**This API behaves differently.** It is built on our implementation, which applies **AND**: it fires only when the 1st of the month **falls on** a Monday. Confirmed with a live request — `0 0 1 * 1`, starting from 1 January 2027:

```
2027-02-01  Monday
2027-03-01  Monday
2027-11-01  Monday
2028-05-01  Monday
2029-01-01  Monday
2029-10-01  Monday
```

Six runs in three years instead of roughly two hundred. The difference is enormous, and it would be dishonest to keep quiet about it.

**Practical takeaway:** if both fields are set explicitly, **do not move the expression between system cron and this API blindly** — compare the `occurrences` lists first. If you do not actually need both fields (and most schedules do not), leave one of them as `*` and the semantics agree with every implementation. Incidentally, this is precisely why it pays to look at the list of real run times rather than at the expression.

## Other Atlorium APIs

Schedule validation is usually part of the broader "check the config before deploying" job. The same account and the same key also give you:

- [Weather data](https://github.com/atlorium-api/weather-api-client) — current conditions by coordinates
- [SSL certificate check](https://github.com/atlorium-api/ssl-certificate-check-api-client) — expiry, SAN, chain of trust
- [GAR/FIAS addresses](https://github.com/atlorium-api/gar-fias-address-api-client) — search and suggestions from the official registry
- [DNS Lookup](https://github.com/atlorium-api/dns-lookup-api-client) — domain records, MX, SPF, DMARC
- [Address standardization](https://github.com/atlorium-api/address-standardization-api-client) — parse a string into components, quality score
- [CIDR calculator](https://github.com/atlorium-api/cidr-subnet-calculator-api-client) — split networks into subnets, IP range membership

Full catalogue: [atlorium.com](https://atlorium.com)

## Links

- **API reference (Swagger):** [atlorium.com/cronAPI](https://atlorium.com/cronAPI)
- **OpenAPI spec:** [cron_en-US.json](https://atlorium.com/openapi/cron_en-US.json)
- **Support:** support@atlorium.com

## License

[MIT](LICENSE)
