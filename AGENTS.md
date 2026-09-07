# FFF Android agent guide

Before changing application behavior, read [`docs/APP_KNOWLEDGE_BASE.md`](docs/APP_KNOWLEDGE_BASE.md). It is the canonical map of the product, modules, persistence, navigation, update flow, and release process.

Keep these invariants:

- FFF is the launcher; Finance, Harness, and Remote Control are applications inside it.
- Finance remains local-first. Its source of truth is the Room/SQLite database on the device.
- Database schema changes require a lossless Room migration and migration tests.
- Never silently delete financial history. Permanent deletion must be explicit and preserve balances.
- Release builds keep `applicationId=ru.rockxi.fff` and the established signing certificate.
- Public releases are produced only by the GitHub Actions tag workflow.
- Update downloads must remain canonical, checksum-verified, bounded, and installed through the private FileProvider.

Run at minimum before handoff:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Update the knowledge base whenever architecture, storage, navigation, backup format, or release behavior changes.
