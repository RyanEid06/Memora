# Memora 1.0.4 finishing pass

Objective from `C:/Users/Administrator/.codex/attachments/0a98f738-46c5-4ef3-b3fe-5ddcb09b1fb1/goal-objective.md`. Keep **1.0.4 / code 7**, `com.personallibrary.app`, Room 9 and the permanent signing and `.plb` compatibility contracts. No app uninstall, data wipe, or physical device operation. Quiet execution. Future subagents use Luna/high. The preceding release and cleanup history is in [the baseline worklog](https://github.com/RyanEid06/memora/blob/a5ec3bcfb688eb2ecebe17f3a52f967d239fc59d/CODEX_WORKLOG.md).

## Source and decisions

- Finishing changes are based on `a5ec3bcfb688eb2ecebe17f3a52f967d239fc59d` on `main` in `RyanEid06/memora`. Record the finishing commit, exact CI run, and delivery state in ignored `artifacts/finishing-20261106/release-state.json`. Preserve ignored owner files `classes.jar` and `docs/qa/`.
- Launcher generator now packages one coherent layer with background edge extension and a larger legacy crop. The approved source and immutable original are preserved. Generated density PNGs, asset tests and branding docs changed. Android supplies the mask.
- `MainActivity` and `AppLockManager` gate native authentication by foreground/lock cycle. Native handoff, terminal errors, focus recovery, Home cancellation and stale callbacks are accounted for. Existing private content gating, task privacy and intent routing remain. `ReminderReceiver` redacts posted/new protected reminders and blocks previously issued mutation intents. Capture and reminder notifications share the monochrome icon.
- `LibraryScreen`, `SearchStateComponents` and `CommonComponents` keep collapsed chips and Clear together, scope separate, expanded selectors compact and empty states accurate. Compact Add is circular and disappears in selection. The final visual refinement fits a complete chip inside its scrolling viewport at large font and shortens the expanded toggle label. Existing cards and ViewModel scope logic remain.
- Settings use App Lock wording and the `Memora_Backup_<timestamp>.plb` filename. README describes current source separately from published downloads.

## Observed verification and exact next action

Evidence directory `artifacts/finishing-20261106/`. Baseline RED: Library native 3/3 targeted failures, auth/reminder host 24/34 targeted failures, launcher asset 2/5 targeted failures. Launcher asset GREEN 5/5, QA Python 3/3, signing/release safety 53/53. Actual native circle render and source circle/squircle previews inspected. Original launcher SHA-256 remains `E8B9DEDBFCF568FB2F32B3A0593330D3E5BEB2625120A0D8D985650E9E3C825E`.

API37 dedicated `Memora-Finish-API37` emulator5580: actual Android PIN sheet opened on enable, cold launch and Home return. Cancel left locked, manual retry reopened, correct PIN unlocked. Default-off launch opened Library directly. Android document picker showed `Memora_Backup_<timestamp>.plb`. Removed only the disposable PIN and turned off the test App Lock through Settings before instrumentation. Original QA AVD had a signature mismatch on an attempted update and was stopped unchanged, so all installs occurred on the separate test AVD.

Final host gate PASS (`host-gate.log`). App 349 passed and two opt-in skips, core 66 passed, lint and debug/test APK assembly successful. Native regression suite 61 passed with six opt-in skips (`native-debug-final.log`). Ran five opt-ins separately at actual 320dp/150% and wide window configurations. The Overview fixture is restricted to the original AVD and remains skipped. Visual review exposed clipped type text and a wrapped expanded toggle. Tightened the expanded controls and adjusted the grid inset. Final focused tests 8/8 passed (`native-grid-spacing.log`) and final real PIN replay passed (`native-credential-final.log`).

Graphify runtime recovered at pinned 0.9.101. Final project code-only graph rebuilt (3276 nodes, 8446 edges, 167 communities). Existing 14 partial parse and eight no-symbol warnings remain.

## Release gate

Use JDK21, `.tools/android-sdk`, and the `gradlew.bat` environment in `artifacts/finishing-20261106/gradle.ps1`. Release signing requires clean main and all five hosted CI jobs green for the exact SHA. Use `scripts/Build-MemoraRelease.ps1 -VerifiedSourceSha <full-green-sha> -OutputDirectory <workspace-artifact-path>` only after that gate. No production signing or publication has occurred in this pass. Prior official 1.0.3/code6 is retained in `artifacts/release-v1.0.3-official/`. Physical launcher/fingerprint, phone layout, and in-place signed upgrade/data retention are still required for full acceptance.

Next actions are to observe the optimized build and runtime check, and all five hosted jobs on the finishing SHA. The first optimized build encountered an Android Lint internal `AsyncExecutionService` crash while the source was changing. The stable-source retry uses two workers and keeps lint enabled. Before this finishing commit, remote main was `a5ec3bc` and GitHub Latest was v1.0.3.
