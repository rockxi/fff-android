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
  can be exported and restored through Android's document picker. Every category
  belongs to one budget; expense accounts and budgets must use the same currency.
  Operations are grouped by local calendar day and today's expenses are summarized
  separately for every currency.
  Finance forms use cohesive dark, accessible controls with explicit selected and
  validation states, phone-sized touch targets and keyboard-aware scrolling.
- **AI Harness** is a server-backed multi-conversation chat for the FFF assistant.
  It restores history, keeps one draft per conversation, paginates older messages
  and supports create, rename, archive/restore and delete actions. A pinned
  immutable **EE** conversation mirrors the bound Telegram topic in both
  directions: Harness posts from the owner's account through User API, while
  direct owner messages and Bot API answers appear back in Android. Retries reuse
  a stable UUID to prevent duplicates. Pair the device with the
  short code shown in the app and approve it personally with `/pair CODE` in the
  Telegram AI topic. The bearer credential is encrypted with Android Keystore.
  Unpairing durably revokes it, and the app returns to pairing if the server reports
  that the session has expired.
- **Remote Control** uses the same paired Harness credential to manage owner-scoped
  SSH profiles with password or private-key authentication and an optional single
  ProxyJump. It runs non-interactive terminal commands and starts background
  `codex exec --json` sessions whose status and retained output can be revisited
  from the app. Credentials are submitted once and are not stored on the phone.
- **Gym Tracker** is a local workout log that opens on today. Exercises belong to
  the built-in chest, back, shoulders, legs, arms, abs and cardio categories;
  each day stores editable sets with repetitions and either equipment weight or
  an explicitly entered body weight. The calendar marks workout days and opens
  any selected date for review and editing. The heaviest historical result for
  the same exercise and repetition count is highlighted as a personal record.
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
