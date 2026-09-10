# FFF Android knowledge base

## Product model

FFF is the main native Android application and launcher. It presents a catalog of smaller applications:

- **Finance** — local-first personal finance, accounts, categories, operations and monthly budgets.
- **Harness** — authenticated native client for the same AI agent and conversation used by the Telegram bot.
- **Remote Control** — SSH host management, non-interactive terminal commands and background Codex CLI sessions.
- **Gym Tracker** — local workout journal with exercises, sets, records and a calendar.

The Android package is `ru.rockxi.fff`. Navigation starts in `ui/FffApp.kt`; catalog metadata lives in `model/FffApplication.kt`; destinations live in `navigation/Destination.kt`.

## Finance

Finance code is split into:

- `data/finance/FinanceEntities.kt` — Room entities and enum types.
- `data/finance/FinanceDao.kt` — SQL boundary.
- `data/finance/FinanceDatabase.kt` — database singleton, schema migrations, seed data and invariant triggers.
- `data/finance/FinanceRepository.kt` — transactional domain rules, balances, archive/delete and backup/restore.
- `ui/finance/FinanceViewModel.kt` — asynchronous state loading and user actions.
- `ui/finance/FinanceScreen.kt` — phone-first Compose UI.
- `ui/finance/FinanceAnalyticsScreen.kt` — phone-first analytics dashboard and report sharing.
- `data/finance/FinanceAnalytics.kt` — date presets, aggregate calculations and bounded CSV/JSON exports.
- `data/finance/FinanceHarnessBridge.kt` — aggregate-only context passed to Harness on explicit opt-in.

The database is `fff-finance.db` in application-private storage. Accounts hold the current balance. Income, expense and transfer entries update balances transactionally. User-created categories belong to one budget; expense account currency must match the category budget currency. Income and expense entry also offer the built-in `Вне бюджета` choice. It is stored as a null ledger `categoryId`, not as a fake category or budget: it affects account balances, operation totals and general analytics, but never budget spending, allocation, remaining balance or budget details. Monthly allocations are keyed by budget and `YYYY-MM`.

Archive is reversible and hides an object from normal pickers. Permanent deletion is explicit. Financial objects referenced by history must not disappear silently: delete the related operations first; deleting an operation reverses its balance effect transactionally.

Categories have an emoji chosen from more than 150 distinct built-in choices. Active categories can be edited without changing their income/expense kind: name, emoji and budget are mutable. Moving an expense category with history is transactional and is accepted only when every linked expense account uses the destination budget currency. Archived budgets are never offered as edit targets.

Opening the new-operation modal immediately focuses the amount field and requests
the decimal keyboard. For income and expense operations, choosing a category is
the final confirmation and saves immediately once amount/account validation
passes; the optional comment therefore appears before the category grid. Transfers
retain an explicit save action because their final selector is a destination
account rather than a category. While a save is in flight, repeated submission
and dismissal are disabled.

Backups are versioned JSON documents selected through Android Storage Access Framework. They contain all Finance tables, including archived objects and explicit IDs. A null `categoryId` on an income or expense round-trips as the built-in `Вне бюджета` marker. Restore validates present category references and currencies, then replaces Finance data in one transaction. Backup jobs are serialized and their document streams are opened on the IO dispatcher; the UI prevents overlapping picker or backup operations. The file is user-controlled; no cloud storage is required.

Analytics defaults to the current local calendar month. Its inclusive presets are
the current month, calendar quarter, calendar half-year, year and all time; the
custom option validates explicit start and end dates. Transfers are excluded from
income/expense aggregates. Results remain separated by currency and include
income, expense, net flow, operation count, average expense per covered day, top
category and expenses grouped by category. `Вне бюджета` expenses are represented
as a synthetic analytics category while budget calculations continue to exclude
them. The dashboard renders one donut and bounded, scrollable legend per currency.
CSV and JSON exports are deterministic and size/count bounded. Files are written
only to private cache and shared read-only through FileProvider; an analytics
export is a report, not a restorable Finance backup.

## Harness

Harness code is in `data/harness` and `ui/harness`. Android authentication is
independent from Telegram: the owner enters the server's
`HARNESS_OWNER_ACCESS_KEY` once, the server exchanges it for a revocable bearer,
and the access key is not persisted. The bearer is encrypted with Android
Keystore and revocation is durable. The backend owner identity is the stable
`FFF_OWNER_ID`; `BOT_OWNER_ID` is only a compatibility fallback for an optional
Telegram adapter. When that adapter is enabled, `BOT_OWNER_ID` is mandatory,
numeric and must be numerically equal to `FFF_OWNER_ID`.

Harness is a server-backed multi-conversation client. Conversation metadata and
message history are loaded from PostgreSQL through `HarnessApi`; ordinary
conversations have independent agent/LangGraph contexts and can be created,
renamed, archived, restored and deleted. History is fetched in bounded pages and
rendered oldest-to-newest. `nextBefore` loads older pages without replacing
visible messages. The standalone Android list excludes legacy EE/Telegram
conversations and needs no Telegram session, Bot API token or forum topic.
Telegram can still be configured as an isolated backend adapter for the existing
bot without becoming a dependency of the Harness API or Android client.

Ordinary conversations can use either the existing agent provider or Codex backed
by the owner's ChatGPT subscription. Codex device authorization runs on the FFF
server: Android receives only the public HTTPS verification URL, user code and
connection state, and never stores OpenAI OAuth credentials. As defense in depth,
Android displays the code/open actions only for an uncredentialed HTTPS URL on
the exact `auth.openai.com` origin; lookalike suffixes and custom ports fail closed. Status polling is
bounded and runs only while the Harness screen is visible and authorization is
pending. A pending or expired challenge can be restarted; restart first completes
server logout and immediately clears the local challenge/model state before it
requests a new code, so a failed second step remains truthfully disconnected.
Auth status and command responses are generation-checked so stale requests cannot
overwrite a newer login or logout. The model list is always loaded from the authenticated server allowlist;
provider and model preferences are stored per conversation in private
`SharedPreferences`, with a stale model falling back to the first currently
allowed model. Provider and model are also persisted with a pending send so an
explicit retry preserves the backend's idempotency identity.

Finance access is explicitly opt-in per Harness screen and defaults to off. The
user chooses month, quarter, half-year, year, all time or a validated custom
range. Android calculates the report from `fff-finance.db` and sends only strict,
aggregate-only JSON: date bounds, per-currency totals and bounded category
summaries. Raw ledger rows, comments, account balances, backups, credentials and
tokens are excluded. The snapshot is size/date/count bounded and injected into
the model request ephemerally; it is not appended to durable chat messages or
LangGraph checkpoint history. A pending send persists the exact range and
canonical snapshot, so retries and completed idempotent replays cannot switch the
data being analyzed. If the assistant returns the allowlisted format-only export
request, Android recreates CSV or JSON locally from the captured range and shares
it with a fixed MIME type through the private FileProvider. The server never
receives or writes the exported Finance file, and Finance Room remains the sole
source of truth.

Composer drafts live in private `SharedPreferences`, separately per
conversation; they are not Finance SQLite data or part of Finance backups.
The main Activity uses `adjustNothing`, and the Harness chat applies one Compose
`imePadding` to its viewport. This makes Compose the sole owner of the keyboard
inset: the system cannot resize the window once while Compose subtracts the same
keyboard height again.
Pending sends persist text and `clientMessageId`, so explicit retry after a
network failure reuses the idempotency key. Success clears draft and pending
state. Android bounds input to 20,000 UTF-8 bytes. Reopening reloads canonical
server history and restores the local draft. Message history, Telegram secrets
and bearer plaintext must not be written to logs or Finance backups.

## Remote Control

Remote Control is a native phone-first client for owner-scoped SSH hosts, one-shot terminal commands and durable background Codex CLI sessions. It reuses the encrypted Harness pairing bearer token, loads hosts and sessions from the server whenever its ViewModel is created, and polls only the currently opened session output. Passwords, private keys and passphrases remain transient form state: they are sent to the backend when a host is created and are never persisted in Android preferences, databases or backups. Host profiles can currently be created and deleted; editing is not exposed because the server API has no edit route.

Remote commands accept a maximum 120-second execution timeout. A ProxyJump connection can perform two sequential 20-second connects and two 20-second logins before execution, followed by up to 5 seconds of termination cleanup. The HTTP chain therefore waits 230 seconds in the server-side web proxy and 240 seconds in Android. Ordinary host/session metadata and session-launch requests retain their short 15-second client deadline. Ordinary remote responses remain capped at 512 KiB; command responses alone allow 4 MiB so the backend's legal 256,000-character output still fits when aiohttp ASCII-escapes every non-BMP code point as a 12-byte surrogate pair.

The backend encrypts SSH passwords, private keys and passphrases with AES-GCM.
`REMOTE_ENCRYPTION_KEY` should be an independent random value of at least 32
characters; when absent, the backend falls back to `MINIAPP_ENCRYPTION_KEY` for
compatibility. Losing or rotating that key without migrating ciphertext makes
saved credentials unusable. Neither key nor SSH credentials belong in Git.

The paired Harness bearer token is an owner-level capability for every configured
host, and commands execute with the selected Unix account's privileges. Deployers
must apply least privilege and maintain trusted SSH `known_hosts` for the backend;
the Android client does not provide host-key enrollment or an interactive TTY.
Sessions are background work relative to the phone UI, not durable external jobs:
backend restart terminates the SSH process and stale active records become
`interrupted`. Retained output is capped at the latest 2,000,000 characters.

The AI Harness exposes host listing and SSH command tools. Command execution is
allowed only for an explicit user request and at most once per agent turn. Remote
stdout/stderr is wrapped and treated as untrusted data, never as agent instructions.

## Gym Tracker

Gym Tracker code is split between `data/gym` (Room entities, DAO, database and
repository) and `ui/gym` (state, ViewModel and Compose screens). It uses the
separate application-private `fff-gym.db` database. Version 1 seeds seven
categories: Грудь, Спина, Плечи, Ноги, Руки, Пресс and Кардио. Exercises belong
to exactly one category; sets belong to an exercise and a local calendar day.

Opening Gym Tracker shows today. A workout day can be explicitly started, and
adding a set also ensures its day exists. Merely browsing a calendar date does
not create a stored workout day. Sets contain a positive repetition count and
either a positive equipment weight or an explicitly entered body weight, stored
as integer grams. These two weight modes are mutually exclusive. Exercises and
sets can be created, edited and explicitly deleted from the phone UI.

The Monday-first six-week calendar shows workout activity for the displayed
month and opens any date into the same editable day/exercise flow; “Сегодня”
returns to the current local date. A set is a personal record when its effective
weight is the single best result for that exercise: effective weight wins, then
the larger repetition count, then the earliest `createdAt`/ID for a complete tie.
Record sets receive a gold visual and accessibility label. Creating a set prefills
mode, effective weight and repetitions from the latest persisted set for the same
exercise across all dates; editing always uses that set's own values instead.

Gym is local-only and independent from Finance. The versioned Finance JSON
backup exports only Finance tables and **does not include `fff-gym.db` or Gym
data**. Platform app-data backup is also disabled explicitly by the manifest
(`android:allowBackup="false"` and `android:fullBackupContent="false"`), so it
does not provide a separate Gym backup. Preserving Gym data currently relies on
keeping the installed application's private data during in-place updates.

## UI system

Theme tokens are in `ui/theme/Theme.kt`. Reusable custom modal surfaces and controls belong in `ui/components`. Product dialogs use Compose `Dialog` plus the FFF surface/theme rather than platform-styled Material `AlertDialog`, so narrow-screen layout and actions are consistent. Finance text inputs expose focus, supporting and field-error states; custom single-choice rows and four-column emoji/category grids provide checked radio semantics and at least 48dp touch targets.

## Updates and releases

The updater is in `update/`. With explicit consent it reads the latest public GitHub release, accepts only the canonical `fff-<tag>.apk` and matching `.sha256`, downloads with size/redirect limits, verifies SHA-256, and opens Android Package Installer through a non-exported FileProvider. Android may require the user to authorize installs from FFF once.

GitHub Actions workflows are in `.github/workflows`. Main pushes run CI. Signed `v*` tags build and publish the signed APK and checksum. Never change the application ID or signing key if in-place upgrades must continue working.

Version `0.10.0` (`versionCode 16`) adds Finance analytics and bounded CSV/JSON
reports, opt-in local Finance context and assistant-requested local exports in the
standalone Harness, owner access-key authentication independent from Telegram,
and the built-in `Вне бюджета` operation choice.
It retains the fast Finance operation-entry flow from `0.9.3`.
It retains the Harness composer fix from `0.9.2`, which combines
`adjustNothing` with exactly one Compose IME inset. It retains the Codex
subscription and Gym improvements from `0.9.0`,
`applicationId=ru.rockxi.fff`, the established signing identity and the existing
update channel.

## Verification checklist

- Run unit tests, lint and debug assembly.
- For schema changes, test a migration from every supported previous schema.
- For Finance changes, verify balances, archive visibility, deletion restrictions and backup round-trip.
- For releases, verify tag/commit signatures, APK metadata, checksum and signer continuity.
