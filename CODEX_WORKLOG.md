# Memora checkpoint — 4 November 2026

## Objective and owner scope

Deliver the official permanently signed **1.0.4/code7** APKs and leave a clean public Memora repository. Original comprehensive objective: attachment `96b8e2d5-fa9d-4c1c-b156-88f89d8b2e72/goal-objective.md`. Latest owner narrowed it: **skip backend persistence/deployment/AI and the rest; official APK and repository cleanup only**. Additional requests: full icon without black border; update stale files; reclaim28GB+ of unused local data. Quiet work, Luna/high subagents. No redesign/refactor/new feature, no new signing key, no physical-device operation or data wipe.

Canonical prior requirements remain [42-finding plan](docs/Memora_42_Finding_Remediation_Plan.md), [audit](docs/Memora_Debug_Audit.md), [rulings](docs/CODEX_REMEDIATION_REVIEW_2026_10_27.md). The immutable V1.0.1 docs remain historical specifications/evidence. This file is the only live progress ledger.

## Source, branch and exact next action

Working checkout: `main`, repository renamed successfully to **[RyanEid06/memora](https://github.com/RyanEid06/memora)**; origin is `https://github.com/RyanEid06/memora.git`. Production/icon source committed/pushed as `2d2b3a5edfca58ef9e97309666fca34db005c3f5`. Its run [41799169974](https://github.com/RyanEid06/memora/actions/runs/41799169974) has core/server/signing-workflow green; Android/emulator were still pending at the last snapshot. A documentation/space-cleanup checkpoint follows it; **require all five CI jobs on the final HEAD**, not only this earlier source.

**Live final hash/run/delivery state:** ignored `artifacts/final-release-20261104/release-state.json`. Update that receipt during waits and publication; it prevents changing committed source merely to record remote status. Matching logs, hashes, cleanup manifest and mask preview are in the same directory. Official signed pair receipt is `artifacts/release-v1/official-release.json`.

**Next:** finish/commit this documentation checkpoint, push main, record final exact-source CI id in the live receipt, wait for core/android/server/emulator/signing-workflow terminal success, then use the supported signing command below. Deliver both APKs/checksums to Desktop and verify byte hashes. Existing public-release authorization was not revoked; source/public APK publication remains permitted. Do not claim the original full production acceptance checklist passed: backend and new physical acceptance were explicitly excluded.

## Completed changes and compatibility

- Source1.0.4/code7 because public1.0.3/code6 was released3Nov and code6 is reserved. Keep `com.personallibrary.app`, Room9 with shipped migrations, preferences/backup contracts and permanent certificate unchanged.
- Full-bleed launcher background removes the old black export frame and inset badge. Derived source `docs/branding/memora/launcher-fullbleed.png`; immutable originals unchanged. Generator uses full legacy512px canvas and opaque1080px adaptive layer; central900px artwork covers the72dp viewport without clipping the emblem. Source circle/squircle preview is `launcher-mask-preview.png`. Native packaged-drawable regression updated for the new contract.
- Root Gradle project/workflow artifact/current canonical URLs use Memora. Old release records labeled historical; signing instructions use portable repository/Documents paths. Backend code and hosted service identity left unchanged by owner direction.
- `.gitignore` protects environment files, signing properties/private keys, runtime DBs and local/build output. Owner's unrelated `classes.jar` and `docs/qa/` preserved locally, excluded only in `.git/info/exclude`; initial8doc edits preserved in `pre-existing-docs.patch` before cleanup.
- Official Gradle9.3.1 distribution SHA pinned. Wrapper JAR SHA independently matches official `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`; receipt `gradle-integrity.json`.

## Observed verification

- Host gate PASS9m51s: app330 =328PASS/2explicit opt-in benchmark skips/0FAIL/0ERROR; core66/66; lint and debug/test APK assembly PASS. `android-host.log`, `host-test-counts.json`.
- After icon change, native test assembly/compilation and lint PASS1m34s (`icon-build.log`). No new local emulator install performed.
- Python QA/assets6/6 PASS. Old icon contract reproduced two failures (31,906 black-border pixels and transparent adaptive padding), then full-bleed regressions passed. `launcher-fullbleed-red.log`, `launcher-fullbleed-green.log`.
- Release/signing regression53/53 PASS: DPAPI15, release19, candidate10, APK9. Portable Documents lookup resolves to the exact existing permanent key; file checksum verified without exposing credentials.
- Checksum-verified Gitleaks8.30.1 scanned all104baseline Git commits/3.82MB. Three generic-rule matches were one published keystore **file checksum**, not a key/password. Exact checksum-only allowlist; repeat history and staged scans have zero real secrets. Redacted receipts retained. Current final staged scan must remain clean after docs checkpoint.
-63relative docs links resolve; whitespace diff check PASS. Luna/high cleanup review identified two stale historical/version phrases, corrected; subsequent icon review found no packaging/masking blocker.
- Final CI/R8/official artifact claims remain gated. Read the live receipt for subsequent outcomes.

## Space cleanup

Owner requested removal of unused28GB+. Before: **38.29GiB /41,117,089,360bytes**. Verified no active project emulator and no attached ADB device. Deleted **32076092963bytes** of obsolete snapshots, isolated QA disks, redundant debug APKs and installed-SDK download archives; observed C: free space increased **32017256448bytes** (about29.8GiB). Exact resolved targets, sizes and reasons: `space-cleanup.json`.

Retained source/Git history, installed SDK, official/private certified release APKs and receipts, QA logs/screenshots/reports, original artwork, unrelated owner files, and current original emulator userdata/config. Permanent key/DPAPI credential remain outside the repository. Removed `.tools/qa-avd/Memora-QA-API37.avd/snapshots/` and disposable `artifacts/remediation-20261027/phase3-native-runtime/`: **old snapshot names and clone paths in historical logs no longer exist**. Recreate an isolated test runtime before future local native QA; never assume a saved state remains. Current emulator userdata was preserved to avoid losing a potentially useful library. Do not remove working build intermediates until official signing finishes.

## Signing and local environment

Use JDK21 `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot`; SDK `.tools/android-sdk`; process `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<repo>/.tools`; Gradle flags `--no-daemon --no-configuration-cache --console=plain`. Real Python: `C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe -X utf8`. GitHub/credential/cache/socket/DPAPI/Desktop writes require sandbox escalation on this host. Never print secret values.

Official entry point after clean main matches fetched origin/main and exact-source CI is green:

```powershell
.\scripts\Build-MemoraRelease.ps1 -VerifiedSourceSha <full-green-HEAD> -OutputDirectory <repo>/artifacts/release-v1.0.4-official
```

Existing alias `memora-release`; certificate SHA256 `98686D31BFE4370CC30024439B56D9E72DE71C081951D31F5D4FDD31D4EB0598`. The script validates key/source/CI/version, decrypts DPAPI only afterward, builds minified ARM64/universal release, checks certificate/package/version/ABI/OCR/alignment/ELF and publishes a verified pair. Never bypass it or substitute debug signing. Prefer final Desktop names `Memora-v1.0.4-arm64-official.apk`, `Memora-v1.0.4-universal-official.apk`, checksums alongside. Verify copied and remote release hashes.

## Historical release and architecture provenance

Public1.0.3/code6: source/tag `f8376484aa5f0788a93ee7415d873ad09d6266f8`, five-job CI41590418751 success. Official ARM64 SHA256 `51A44435CF6F14F062DE562F1DB0A3200B6611BB51E079E6A465AAE011CCBFD3`; universal `B79B86ACC8C5464F16D337638A5BB3ABCB6CE402CF2CE5E526343532D561EFD1`. Stable files/receipts `artifacts/release-v1.0.3-official/`. Preserve older certified1.0.2/code5,1.0.1 and1.0.0 provenance. Complete previous checkpoint is retained in [Git history at2d2b3a5](https://github.com/RyanEid06/memora/blob/2d2b3a5edfca58ef9e97309666fca34db005c3f5/CODEX_WORKLOG.md); original historical audits/docs are not overwritten.

Room9 retains shipped1→8 migrations plus streaming8→9; indexed/keyset search and AI have bounded retrieval; restore uses staging/compensation/reconciliation, not filesystem/Room atomicity. F36/F37 backend durability remains deferred; no DB/deployment/provider work in this narrowed pass. Physical upgrade/data retention, realistic large-library performance, actual speech/media/OEM/accessibility/live-provider acceptance remain owner work.

Graphify is project-local and ignored; code-only scope excludes secrets/builds/artifacts. Updated at2d2b3a5:3174nodes/8109edges/156communities.14partial Kotlin parse warnings and8symbol-free files; navigation only, source is proof. No cloud backend, watcher or extra graph rebuild required for documentation-only cleanup.
