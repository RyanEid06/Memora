# Memora status — 4 November 2026

## Maintenance source 1.0.4 / code7

The owner limited this pass to a clean public repository and an official signed APK. Repository: [RyanEid06/memora](https://github.com/RyanEid06/memora). Changes cover the owner-requested full-bleed launcher icon, current repository/project metadata, secret-file exclusions, signing path portability and verified Gradle distribution pinning. Android behavior, application ID, permanent certificate and Room schema9 remain unchanged. Backend persistence/deployment and new physical-device acceptance are outside this pass.

Use [GitHub Releases](https://github.com/RyanEid06/memora/releases/latest) for published APKs/checksums. The supported signing script requires all five CI jobs to succeed on the exact source before producing official artifacts. Owner-local signed output metadata is recorded in ignored `artifacts/release-v1/official-release.json`; the root checkpoint links current validation evidence.

## Historical official 1.0.3 / code6

[Download 1.0.3](https://github.com/RyanEid06/memora/releases/tag/v1.0.3). Package `com.personallibrary.app` and the permanent signing certificate are unchanged. `main` was fast-forwarded to APK/tag source `f8376484aa5f0788a93ee7415d873ad09d6266f8`; later documentation cleanup changes no application, test, build or signing inputs.

- All five required jobs passed in [CI41590418751](https://github.com/RyanEid06/memora/actions/runs/41590418751).
- Hosted Debug and optimized native runs each finished 111 tests: 99 passed, 12 explicit environment/opt-in skips, zero failures. Separate local optimized100/100 has zero skips; local Debug101 passing unique executions span groups/reruns.
- The local host gate passed 328 Android JVM tests with two opt-in benchmark skips; core66; backend128/128 plus typecheck/build. Lint and debug/test APK assemblies passed.
- Both official APKs passed permanent-certificate, package/version, ABI/OCR, ZIP alignment, ARM64 ELF16KiB, checksum and size checks. They are byte-identical to the independently verified candidate pair. The release includes SHA256SUMS.txt.
- Desktop ARM64: `Memora-v1.0.3-arm64-official.apk`, 18,440,156 bytes, SHA256 `51A44435CF6F14F062DE562F1DB0A3200B6611BB51E079E6A465AAE011CCBFD3`. The version-specific test guide is alongside it.

The remediation includes paged indexed search, bounded indexing/retrieval, Room9 migrations, staged restore, folder/bulk operations, voice recovery, Video/GIF handling, adaptive layouts and privacy-safe diagnostics. Phases1–11 have verified local checkpoints; publication does not close all42 findings or physical acceptance.

## Remaining acceptance

Physical in-place update/data retention, realistic large-library responsiveness, fresh-device restore, actual speech, OEM picker/viewer/reminder behavior, TalkBack and live-provider operation remain pending. Backend deployment is separate; F36/F37 server restart/replica persistence remains owner-deferred, while F38 bounded cleanup is implemented. See [owner handoff](HANDOFF.md), the [active plan](Memora_42_Finding_Remediation_Plan.md), [review rulings](CODEX_REMEDIATION_REVIEW_2026_10_27.md) and [root checkpoint](../CODEX_WORKLOG.md).

## Historical releases and evidence

[V1.0.1/code3](https://github.com/RyanEid06/memora/releases/tag/v1.0.1) APK source `5ac62b4ccbcdacdf6144827c8f09a3cfdeca5125` passed [CI39389151689](https://github.com/RyanEid06/memora/actions/runs/39389151689). Its immutable [master](CODEX_V1_0_1_MASTER_PROMPT.md), [worklog](CODEX_V1_0_1_WORKLOG.md), [audit](CODEX_V1_0_1_AUDIT.md) and [physical checklist](CODEX_V1_0_1_PHYSICAL_QA.md) remain historical records.

[V1.0.0/code2](https://github.com/RyanEid06/memora/releases/tag/v1.0.0) was built from `1c42f958f3e663d2e547a266670bae4fb54e5214` after [CI38959696966](https://github.com/RyanEid06/memora/actions/runs/38959696966). Previous certified APKs and receipts are retained. Completed planning drafts remain recoverable from Git history; the [candidate procedure](QA_CANDIDATE_BUILD.md) records the private handoff preceding1.0.3 publication.
