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
  accounts, emoji categories, transfers, and named monthly budgets with category
  breakdowns. Accounts, categories and budgets can be archived or safely deleted;
  deleting an operation reverses its balance effect. Versioned local JSON backups
  can be exported and restored through Android's document picker. Every category
  belongs to one budget; expense accounts and budgets must use the same currency.
- **AI Harness** is the native chat for the FFF assistant. Pair the device with the
  short code shown in the app and approve it personally with `/pair CODE` in the
  Telegram AI topic. The bearer credential is encrypted with Android Keystore.
  Unpairing durably revokes it, and the app returns to pairing if the server reports
  that the session has expired.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
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
