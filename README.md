# FFF Android

Phone-first native Android client for FFF. Finance data is stored locally on the
device in SQLite through Room; no account or remote database is required.

## Install

Download the signed APK from [GitHub Releases](https://github.com/rockxi/fff-android/releases).
With your consent, the app checks the latest GitHub release and asks before it
downloads the signed APK. It verifies the published SHA-256 checksum and opens the
Android package installer directly, without sending you to a browser. Existing data
remains local during an in-place update.

## Applications

- **Finance** is a local-first ledger backed by Room/SQLite. It supports multiple
  accounts, editable emoji categories with more than 150 distinct icons, transfers,
  and named monthly budgets with category breakdowns. Category names, icons and
  budgets can be changed safely without changing their income/expense type.
  Accounts, categories and budgets can be archived or safely deleted;
  deleting an operation reverses its balance effect. Versioned local JSON backups
  can be exported and restored through Android's document picker. User-created
  categories belong to one budget; expense accounts and budgets must use the same
  currency. Income and expense forms also provide a built-in **Вне бюджета**
  choice: those operations affect their account and general analytics, but never
  a budget's spending or remaining amount.
  Operations are grouped by local calendar day and today's expenses are summarized
  separately for every currency.
  Analytics opens on the current calendar month and shows a per-currency expense
  donut, category legend, income, expense, net cash flow, operation count, daily
  average and top category. Presets cover month, calendar quarter, calendar
  half-year, year and all time; an inclusive custom date range is also available.
  The displayed report can be shared as bounded CSV or JSON.
  Finance forms use cohesive dark, accessible controls with explicit selected and
  validation states, phone-sized touch targets and keyboard-aware scrolling.
- **AI Harness** is a standalone, server-backed multi-conversation chat for the
  FFF assistant. A fresh install connects with the owner's FFF access key; the key
  is exchanged for a revocable bearer token and is not retained on the phone.
  It restores history, keeps one draft per conversation, paginates older messages
  and supports create, rename, archive/restore and delete actions. Android does
  not require Telegram, pairing commands or an EE topic; Telegram may remain
  enabled separately on the server for the legacy bot. Retries reuse a stable UUID
  to prevent duplicates. The bearer credential is encrypted with Android Keystore.
  Disconnecting durably revokes it, and the app returns to access-key onboarding
  if the server reports that the session has expired. Conversations can use
  Codex through the owner's ChatGPT subscription: the app shows a device code,
  opens the official HTTPS authorization page and then loads only the models from
  the server allowlist. The authorization action is shown only for the exact
  uncredentialed `https://auth.openai.com` origin. Provider/model choices and retry identity are kept per
  conversation; OAuth credentials remain on the FFF server. The chat viewport
  applies keyboard insets once, keeping
  the composer directly above the IME instead of lifting it by the keyboard height
  twice.
  Finance context is off by default. When explicitly enabled for a chosen preset
  or custom range, Android derives a bounded aggregate report from local SQLite and
  attaches only totals and category statistics to that request. Raw operations,
  backups and credentials are never sent. The assistant may request CSV or JSON;
  Android then generates the file locally for the exact captured range and opens
  the Sharesheet.
- **Remote Control** uses the same Harness bearer credential to manage owner-scoped
  SSH profiles with password or private-key authentication and an optional single
  ProxyJump. It runs non-interactive terminal commands and starts background
  `codex exec --json` sessions whose status and retained output can be revisited
  from the app. Credentials are submitted once and are not stored on the phone.
- **Gym Tracker** is a local workout log that opens on today. Exercises belong to
  the built-in chest, back, shoulders, legs, arms, abs and cardio categories;
  each day stores editable sets with repetitions and either equipment weight or
  an explicitly entered body weight. The calendar marks workout days and opens
  any selected date for review and editing. Exactly one set per exercise is the
  all-time personal record: highest effective weight wins, then repetitions, then
  the earliest persisted set for a complete tie. A new-set form is prefilled from
  that exercise's most recently persisted set, including weight mode, weight and
  repetitions; editing retains the selected set's own values.
  Gym uses its own private Room/SQLite database. Finance JSON backup files do not
  include Gym data.

Remote Control grants the paired device the authority of the configured remote
Unix accounts. Prefer dedicated least-privilege accounts and trusted server host
keys. Host-key enrollment/confirmation and interactive TTY programs are not
available in the Android UI. Codex jobs continue while the screen is closed, but
the backend is the job supervisor: restarting it interrupts active jobs and marks
their durable session records as `interrupted`.

## Build

```bash
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

This repository contains no credentials, Telegram tokens, signing keys, or server secrets.

## Release signing

Release APKs are built by GitHub Actions from `v*` tags and signed with the stable
project key stored as encrypted repository secrets. The private key is deliberately
not part of the repository. Keep an offline backup of that key: Android will only
install future updates over an existing installation when both APKs use the same key.

## License

[MIT](LICENSE)
