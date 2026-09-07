# FFF Android knowledge base

## Product model

FFF is the main native Android application and launcher. It presents a catalog of smaller applications:

- **Finance** — local-first personal finance, accounts, categories, operations and monthly budgets.
- **Harness** — authenticated native client for the same AI agent and conversation used by the Telegram bot.
- **Remote Control** — host/terminal control entry. Its richer remote-control implementation is still evolving.

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

Backups are versioned JSON documents selected through Android Storage Access Framework. They contain all Finance tables, including archived objects and explicit IDs. Restore validates references and currencies, then replaces Finance data in one transaction. Backup jobs are serialized and their document streams are opened on the IO dispatcher; the UI prevents overlapping picker or backup operations. The file is user-controlled; no cloud storage is required.

## Harness

Harness code is in `data/harness` and `ui/harness`. A short pairing code is approved by the owner in the Telegram AI topic. The resulting bearer token is stored with Android Keystore encryption. Revocation is durable. The backend shares the Telegram AI topic's agent/thread identity.

## Remote Control

Remote Control is a catalog destination intended for SSH hosts, terminals and agent sessions. Keep host credentials out of the repository and Android backups unless an explicit encrypted storage design is implemented.

## UI system

Theme tokens are in `ui/theme/Theme.kt`. Reusable custom modal surfaces belong in `ui/components`. Product dialogs use Compose `Dialog` plus the FFF surface/theme rather than platform-styled Material `AlertDialog`, so narrow-screen layout and actions are consistent.

## Updates and releases

The updater is in `update/`. With explicit consent it reads the latest public GitHub release, accepts only the canonical `fff-<tag>.apk` and matching `.sha256`, downloads with size/redirect limits, verifies SHA-256, and opens Android Package Installer through a non-exported FileProvider. Android may require the user to authorize installs from FFF once.

GitHub Actions workflows are in `.github/workflows`. Main pushes run CI. Signed `v*` tags build and publish the signed APK and checksum. Never change the application ID or signing key if in-place upgrades must continue working.

## Verification checklist

- Run unit tests, lint and debug assembly.
- For schema changes, test a migration from every supported previous schema.
- For Finance changes, verify balances, archive visibility, deletion restrictions and backup round-trip.
- For releases, verify tag/commit signatures, APK metadata, checksum and signer continuity.
