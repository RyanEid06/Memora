# Memora owner handoff

Current source is **1.0.6 / code 9**. Download published APKs and checksums from [the latest release](https://github.com/RyanEid06/memora/releases/latest). The records below describe the historical 1.0.3 handoff.

[Official release and both APK variants](https://github.com/RyanEid06/memora/releases/tag/v1.0.3). Requires Android10 or newer. Use ARM64 for ARM64 phones; the universal APK contains arm64-v8a/armeabi-v7a/x86/x86_64. Debug/optimizedCheck APKs use a different certificate.

Desktop: `C:\Users\Administrator\Desktop\Memora-v1.0.3-arm64-official.apk` (18,440,156 bytes), SHA256 `51A44435CF6F14F062DE562F1DB0A3200B6611BB51E079E6A465AAE011CCBFD3`. The guide is `Memora-v1.0.3-code6-Physical-Test-Guide.md`. Both signed variants passed identity/content/alignment checks; the Desktop copy was rehashed. Exact APK/tag source `f8376484aa5f0788a93ee7415d873ad09d6266f8` passed all five jobs in [CI41590418751](https://github.com/RyanEid06/memora/actions/runs/41590418751). [Current evidence and limits](STATUS.md) · [Permanent release procedure](branding/memora/RELEASE.md).

## Update installation

Export an encrypted backup before updating. Install the official APK over your existing installation. Version code 8, package `com.personallibrary.app`, and the permanent signing certificate support in-place upgrades from the signed 1.0.3 and 1.0.4 builds. If Android reports a signature conflict or asks to uninstall, stop and preserve the existing app and data.

## Remaining phone acceptance

Record PASS, FAIL or NOT RUN with actual device/Android version and observed behavior. Use disposable content, protect private library data, and follow the Desktop guide plus applicable cases in the retained [physical checklist](CODEX_V1_0_1_PHYSICAL_QA.md) and [QA coverage map](../qa-reports/memora-v1-final/COVERAGE_MAP.md).

- Real WhatsApp/other-app shares: text, link, photo/screenshot, sticker, video, audio/voice note, PDF/document, unknown file and multi-file batches. Check captions, previews, Open/Share, cancellation and partial failure. Leave during a large capture, overlap another capture, verify durable originals, and confirm deleting disposable source files does not remove library copies.
- WhatsApp exports: import a disposable text export and separately selected attachments; verify dates, matched/missing/unmatched counts, repeat-import deduplication/reference counts, and search refresh after supplying a missing attachment. Extract ZIP packages first.
- Library/folders/Trash: verify date basis and ranges, sort, paged list/grid selection, bulk membership, Undo, Restore All, shared-original protection, snippets/OCR and PDF page references.
- Backup/restore on a safe fresh-device/test setup: originals, memberships, duplicates, favorites, Trash, date-only reminders and rebuilt search. Wrong passphrase/corruption must preserve the live library; pair the device separately after restore. Keep the only good backup intact.
- Actual voice/model/provider fallback, microphone denial, cancellation and editable drafts; biometric/device-credential lock, background/share/picker/viewer handoffs and protected Overview; no automatic recording on return.
- Notifications/reminders: permission denial/grant, exact access, Snooze/Complete, navigation, reboot/timezone changes and OEM background behavior. Date-only reminders target09:00 local and remain calendar dates.
- Light/Dark/System persistence and restart, launcher,150%font, portrait/landscape, TalkBack, touch targets, keyboard/insets and empty/error states.
- Paired live AI: analyze disposable supported media, exclusion/network/pause/retry behavior, source-linked answers and honest outage states. Backend rollout and live provider success remain separate; F36/F37 persistence is still deferred.
- Retrieval/performance:20–30 realistic queries with known targets, at least90%top-five success and local-search p95 below300ms with10k realistic items/chunks. Record cold/warm method, actual query set, device and import/indexing load. Host synthetic measurements do not meet this device acceptance requirement.

## Retained history

Earlier release identities, hashes and CI belong to their original revisions: [release ledger](branding/memora/RELEASE.md), [V1.0.1 historical worklog](CODEX_V1_0_1_WORKLOG.md), [audit](CODEX_V1_0_1_AUDIT.md) and [original QA results](../qa-reports/memora-v1-final/CASE_RESULTS.md). Replacing stale handoff text does not change those observations or mark pending cases passed. Report the failing action, device/Android version and visible message without enrollment/API secrets or personal content.
