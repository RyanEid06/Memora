# Installing and updating Memora

Download the published APKs and their SHA-256 checksums from the [latest release](https://github.com/RyanEid06/memora/releases/latest). Memora requires Android 10 or later. The ARM64 APK supports ARM64 phones. The universal APK supports ARM64, ARMv7, x86, and x86-64.

## Update installation

Export an encrypted backup before updating. Install the official APK over your existing installation. Package `com.personallibrary.app` and the permanent signing certificate support in-place upgrades from earlier official builds. The 1.0.5 to 1.0.6 update preserved a saved favorite note on the release emulator. If Android reports a signature conflict or asks to uninstall, stop and preserve the existing app and data.

## Remaining phone acceptance

Use disposable content for phone verification. Record the device, Android version, and observed behavior, keeping personal content and credentials private.

- Real WhatsApp/other-app shares: text, link, photo/screenshot, sticker, video, audio/voice note, PDF/document, unknown file and multi-file batches. Check captions, previews, Open/Share, cancellation and partial failure. Leave during a large capture, overlap another capture, verify durable originals, and confirm deleting disposable source files does not remove library copies.
- WhatsApp exports: import a disposable text export and separately selected attachments; verify dates, matched/missing/unmatched counts, repeat-import deduplication/reference counts, and search refresh after supplying a missing attachment. Extract ZIP packages first.
- Library/folders/Trash: verify date basis and ranges, sort, paged list/grid selection, bulk membership, Undo, Restore All, shared-original protection, snippets/OCR and PDF page references.
- Backup/restore on a safe fresh-device/test setup: originals, memberships, duplicates, favorites, Trash, date-only reminders and rebuilt search. Wrong passphrase/corruption must preserve the live library; pair the device separately after restore. Keep the only good backup intact.
- Actual voice/model/provider fallback, microphone denial, cancellation and editable drafts; biometric/device-credential lock, background/share/picker/viewer handoffs and protected Overview; no automatic recording on return.
- Notifications/reminders: permission denial/grant, exact access, Snooze/Complete, navigation, reboot/timezone changes and OEM background behavior. Date-only reminders target09:00 local and remain calendar dates.
- Light/Dark/System persistence and restart, launcher,150%font, portrait/landscape, TalkBack, touch targets, keyboard/insets and empty/error states.
- Paired live AI: analyze disposable supported media, exclusion/network/pause/retry behavior, source-linked answers and honest outage states. Backend rollout and live provider success remain separate; F36/F37 persistence is still deferred.
- Retrieval/performance:20–30 realistic queries with known targets, at least90%top-five success and local-search p95 below300ms with10k realistic items/chunks. Record cold/warm method, actual query set, device and import/indexing load. Host synthetic measurements do not meet this device acceptance requirement.

[Release and signing procedure](branding/memora/RELEASE.md)
