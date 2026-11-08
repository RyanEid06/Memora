# Memora

Memora is a personal library for Android. Save notes, links, photos, videos, voice notes, PDFs, and other documents in one place, and find them again offline.

**Latest release: Memora 1.0.6 (version code 9).** Requires Android 10 or later.

[**Download the ARM64 APK**](https://github.com/RyanEid06/Memora/releases/download/v1.0.6/Memora-v1.0.6-arm64-official.apk) · [Universal APK](https://github.com/RyanEid06/Memora/releases/download/v1.0.6/Memora-v1.0.6-universal.apk) · [Release notes and SHA-256 checksums](https://github.com/RyanEid06/Memora/releases/latest)

Use ARM64 for an ARM64 phone. To update an existing officially signed installation, export an encrypted backup and install the APK over Memora. See the [installation guide](docs/HANDOFF.md).

## Features

- Save from the Android share menu and import WhatsApp exports.
- Organize your library with folders, Favorites, Inbox, and Trash.
- Search locally using text, filters, bundled Latin OCR, and PDF extraction.
- Use App Lock, reminders, editable voice input, and Light, Dark, or System appearance.
- Export and restore passphrase-encrypted `.plb` backups.
- Optionally use AI to find related content and get answers linked to library items.

## Offline and privacy

Your library and original files are stored on your device. Browsing, local search, and bundled OCR work offline. Optional AI uses the configured backend and provider to process eligible content. You can pause analysis or exclude items and folders. [Backend behavior and setup](server/README.md)

## Tech stack

The Android app uses Kotlin, Jetpack Compose, and Room. Tink provides backup encryption. The optional AI backend uses Node.js and TypeScript with Gemini.

[Documentation](docs/README.md) · [Backup format](docs/BACKUP_FORMAT.md) · [Release signing procedure](docs/branding/memora/RELEASE.md)
