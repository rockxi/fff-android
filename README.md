# FFF Android

Phone-first native Android client for FFF. Finance data is stored locally on the
device in SQLite through Room; no account or remote database is required.

## Install

Download the signed APK from [GitHub Releases](https://github.com/rockxi/fff-android/releases).
The app checks the latest GitHub release and offers to open its download page when
a newer semantic version is available. Existing data remains local during an
in-place update.

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
