# Memora appearance delivery ledger

## Current 1.0.4 launcher verification

The 4 November maintenance source replaces the black-framed launcher with full-bleed artwork. Original files are preserved. Python asset/QA checks pass6/6 after reproducing the prior frame in two failing regressions; AndroidTest assembly and lint pass. Circle/squircle source-mask previews show the complete emblem without the black border. Native CI and permanent signing remain gates for the exact source; per-release results and checksums belong to the published release and owner-local signed receipt, not earlier source results.

## Historical 1.0.3 release evidence

Official1.0.3/code6 APK/tag source `f8376484aa5f0788a93ee7415d873ad09d6266f8` passed [all five exact-source CI jobs](https://github.com/RyanEid06/memora/actions/runs/41590418751). The owner original artwork is preserved; the launcher uses its safe-zone correction. Local asset tests3/3 and the separate optimized100/100 functional gate passed. Phone/landscape/tablet/Overview screenshots and original-image/source hashes are recorded in `artifacts/remediation-20261027/phase12-final-source-images-verified.json` and the [root checkpoint](../../../CODEX_WORKLOG.md). Official hashes and receipts are in `artifacts/release-v1.0.3-official/`. Physical launcher/OEM/accessibility acceptance remains pending.

## Historical V1.0.1 header revision

The owner's later instruction removes the Memora logo/name from the Library header in both Light and Dark modes. Current item count and date basis appear directly alongside the existing controls. The updated appearance instrumentation verifies the information/action row, absent branding, actual mode colors, saved selection and Activity recreation, and captures both themes. Local Android JVM 54/54, lint 0 errors/80 existing warnings, debug/optimized builds and both test-APK compilations pass; actual split inspection preserves bundled OCR and the required ABIs. Final exact-source hosted runtime results, screenshots and conditional permanent signing are recorded in ignored `artifacts/release-v1/FINAL_VERIFICATION.md` under the [retained release status](../../STATUS.md).

## Original appearance checkpoint

Scope originally authorized by the owner on 16 October 2026: preserve the three final artwork originals, apply the shared launcher and matching Library wordmarks, rename the visible app to Memora, save Light/Dark/System, provide RGB colors, and replace the previous three Desktop handoff files after all automated gates pass. The final release-completion authorization and current header revision above supersede that earlier delivery checkpoint.

Starting commit: `944632929215f4a01f0404c85913acb52b80cc51`, owner-authorized `main`. The previous chat left only this appearance update uncommitted. Application ID, database, auth, preference store names and backup format stay compatible; version code is 2.

- The prior chat preserved byte-identical originals and prepared resources with `scripts/prepare_memora_assets.py`.
- Theme regression: prior chat recorded 3 tests / 2 failures before implementation, then 3 passed. Fresh continuation run: all 3 passed.
- New hosted checks: 2 preference tests, 3 theme tests, 1 API 35 interactive appearance/recreation test with light/dark screenshots.
- Independent read-only code review found no actionable issue. Fresh checksum comparison confirmed all three preserved originals match Downloads. `git diff --check` passed.
- Complete hosted core/Android/backend/emulator gates are pending at the candidate commit. The owner explicitly approved commit and push to main in this chat after automatic approval review requested fresh authorization. No completion claim is made until terminal CI results and downloaded APK checksum are verified.
- Final commit, CI URL, exact test counts, lint results, screenshots and Desktop artifact hash will be recorded in the delivery acceptance document.

Palette and asset provenance: [README](README.md). Owner testing: [HANDOFF](../../HANDOFF.md).
