# Memora

Your personal library, always within reach. Save notes, links, photos, videos, voice notes, documents and shared files in one native Android app. Keep originals on your phone, find them offline, and optionally use AI to discover connections in what you save.

## What you can do

- **Capture and organize:** receive Android shares, import WhatsApp exports, browse lists or grids, and organize items into folders, Favorites and Inbox.
- **Find what matters:** paged local text search, bundled Latin OCR, PDF extraction, date/type filters and original-file previews.
- **Explore with AI:** optional semantic retrieval and source-linked answers, with pause, exclusions, network controls and clearly labeled fallback results.
- **Keep control:** original files stay local, App Lock protects access, Trash supports recovery, and passphrase-encrypted backups support moving your library.
- **Make it yours:** Light, Dark and System appearance, adaptive layouts, reminders and editable English voice input.

## Releases and current work

| Channel | Version | Status |
| --- | --- | --- |
| [Public release](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.1) | 1.0.1 / code3 | Published; physical-device and live-provider limitations are recorded in the release evidence. |
| Private QA candidate | 1.0.3 / code6 | Remediation branch; exact-source CI, permanent signing and verified delivery are required before installation. |

The private candidate adds bounded indexing and AI retrieval, Room schema9, paged search, GIF animation under Video, voice recovery, adaptive layouts and privacy-safe diagnostics. It retains `com.personallibrary.app`, the permanent signing identity and existing supported database/backup upgrade code; physical migration and restore acceptance remain pending. A higher version code supports updating an existing permanently signed installation in place. **Back up first; stop if Android requests uninstall or reports a signature conflict.** Verification-only debug/optimizedCheck APKs use a different certificate.

[Current status](docs/STATUS.md) · [Installation and owner testing](docs/HANDOFF.md) · [Documentation](docs/README.md) · [Working checkpoint](CODEX_WORKLOG.md)

## Privacy and AI

Local library browsing, text search and bundled OCR work offline. Optional AI sends bounded representations of eligible content to the configured backend/provider; pause analysis or exclude items and folders to control future processing. Provider credentials belong only on the server. App Lock and encrypted backups protect different parts of the workflow; keep your backup passphrase safe.

The backend has no content-library database. Runtime revocations, consumed nonces and rate budgets remain process-local; restart and replica persistence are deferred findings F36/F37. Environment-configured revocations reload on restart. The candidate's automatic registration route requires a separately approved backend rollout; local tests do not establish deployed or live-provider acceptance. See [backend setup and limits](server/README.md).

## Build and verify

Use JDK21, Android platform36.1 and build-tools36.0.0 with the checked-in Gradle wrapper. Minimum Android10/API29; target36. Windows local setup uses the existing `.tools/android-sdk`; see [project instructions](AGENTS.md).

```powershell
.\gradlew.bat -PcoreOnly=true :core:test --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --no-configuration-cache --console=plain
# With an explicitly selected emulator:
.\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
.\gradlew.bat :app:connectedOptimizedCheckAndroidTest -PmemoraOptimizedTests=true --no-configuration-cache
```

From `server/`: `npm ci`, `npm run typecheck`, `npm test`, `npm run build`.

[GitHub Actions](https://github.com/RyanEid06/personal-library/actions/workflows/android.yml) checks core, Android, server, emulator and signing-workflow behavior. Fake-provider CI and emulator results remain separate from physical-phone, OEM, accessibility and live-provider acceptance. Private signing requires all five jobs to pass on the exact committed source; see [candidate build procedure](docs/QA_CANDIDATE_BUILD.md).

## Project structure

- `app/` — Kotlin/Compose Android app, Room storage, local indexing and native tests.
- `core/` — shared contracts and encrypted backup engine.
- `server/` — authenticated TypeScript AI proxy and synthetic provider tests.
- `docs/` — current procedures, active remediation and retained release evidence.
- `qa-reports/` — historical acceptance evidence; its original results are preserved.

[Original artwork and palette](docs/branding/memora/README.md) · [Backup format](docs/BACKUP_FORMAT.md) · [Permanent release procedure](docs/branding/memora/RELEASE.md)
