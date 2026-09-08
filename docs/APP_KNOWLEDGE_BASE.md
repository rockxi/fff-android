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

The database is `fff-finance.db` in application-private storage. Accounts hold the current balance. Income, expense and transfer entries update balances transactionally. Every category belongs to one budget; expense account currency must match the category budget currency. Monthly allocations are keyed by budget and `YYYY-MM`.

Archive is reversible and hides an object from normal pickers. Permanent deletion is explicit. Financial objects referenced by history must not disappear silently: delete the related operations first; deleting an operation reverses its balance effect transactionally.

Categories have an emoji chosen from more than 150 distinct built-in choices. Active categories can be edited without changing their income/expense kind: name, emoji and budget are mutable. Moving an expense category with history is transactional and is accepted only when every linked expense account uses the destination budget currency. Archived budgets are never offered as edit targets.

Backups are versioned JSON documents selected through Android Storage Access Framework. They contain all Finance tables, including archived objects and explicit IDs. Restore validates references and currencies, then replaces Finance data in one transaction. Backup jobs are serialized and their document streams are opened on the IO dispatcher; the UI prevents overlapping picker or backup operations. The file is user-controlled; no cloud storage is required.

## Harness

Harness code is in `data/harness` and `ui/harness`. A short pairing code is approved by the owner in the Telegram AI topic. The resulting bearer token is stored with Android Keystore encryption. Revocation is durable.

Harness is a server-backed multi-conversation client. Conversation metadata and
message history are loaded from PostgreSQL through `HarnessApi`; ordinary
conversations have independent agent/LangGraph contexts and can be created,
renamed, archived, restored and deleted. History is fetched in bounded pages and
rendered oldest-to-newest. `nextBefore` loads older pages without replacing
visible messages. Source labels distinguish owner, agent and Telegram messages.

The pinned immutable `EE` conversation is a bidirectional view of the exact
Telegram topic configured by `/bind_ai_topic`. Sending supplies a stable UUID,
returns pending after the backend's Telethon User API send, and polls history
every three seconds only while EE is visible. Direct owner messages and Bot API
responses appear in the same history. Polling merges by server message ID,
preserves older pages/cursors and stops when the screen closes. Ordinary
conversations wait for the agent response and do not poll Telegram.

Composer drafts live in private `SharedPreferences`, separately per
conversation; they are not Finance SQLite data or part of Finance backups.
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
weight is tied for the highest historical weight for that exact exercise and
repetition count. Record sets receive a gold visual and accessibility label.

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

Version `0.8.0` (`versionCode 11`) adds the local Gym Tracker with today and
calendar editing, weighted/bodyweight sets and personal-record highlighting,
while retaining `applicationId=ru.rockxi.fff` and the existing update channel.

## Verification checklist

- Run unit tests, lint and debug assembly.
- For schema changes, test a migration from every supported previous schema.
- For Finance changes, verify balances, archive visibility, deletion restrictions and backup round-trip.
- For releases, verify tag/commit signatures, APK metadata, checksum and signer continuity.
