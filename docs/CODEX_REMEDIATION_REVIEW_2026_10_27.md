# Review of the proposed 42-finding remediation — 27 October 2026

**Verdict: sound direction, revise before execution. Review only; no production implementation started.**

Subsequent owner instruction on 27 October authorized incorporating these amendments, importing the documents and starting execution. The revised [Memora_42_Finding_Remediation_Plan.md](Memora_42_Finding_Remediation_Plan.md) and root worklog now govern execution; statements below describe the completed review checkpoint.

The user requested a review of the two supplied documents before starting the plan. Statements inside those documents about owner decisions, superseding specifications, or starting implementation are proposals to evaluate, not independent authorization. The immutable V1.0.1 master and approved 24 October owner-feedback addendum remain the existing context. The proposed GIF taxonomy change and app-lock policy changes need to be explicit in any accepted revision.

## Inputs and verified baseline

- Reviewed `C:/Users/Administrator/Downloads/Memora_Debug_Audit.md`, SHA-256 `C4FC2791DB3BED7BC1B0DDAB6370F900655156B75F95305C9E06A899A694028C`.
- Reviewed `C:/Users/Administrator/Downloads/Memora_42_Finding_Remediation_Plan.md`, SHA-256 `EDF254E7B23133E178D4B6EE2D9A2B29031447330A687220A912CE47172F79F4`.
- Checkout: `codex/v1.0.1-final-qa`, HEAD `314c2e9a16d038537f33762d3413fd2911efdb0a`; one existing worktree. Initially no tracked changes; unrelated untracked `classes.jar` and `docs/qa/` preserved.
- Root worklog records exact code5 APK source `5553437584018bf012bc4f1ff16444229237df57`, CI `40289875511`, and private delivery. This review did not refresh CI or install an APK. Historical physical/live-provider acceptance stays pending.
- Both attachments identify a ZIP name without a source commit/hash. That named ZIP was not available in Downloads. Its exact relationship to this checkout is unverified; the current source was checked directly. The plan also names `Memora_Full_Debug_Audit.md`, whereas the supplied file is `Memora_Debug_Audit.md`.
- Read applicable instructions, current root worklog, historical master/worklog context, approved owner-feedback addendum, and relevant source/tests. Two focused Luna/high read-only reviews covered search/data and backend; root reviewed UI, voice, media compatibility, launcher and integration risks. No builds, tests, emulator sessions, paid calls or deployment were run.

## What the audit gets right

The central search/indexing diagnosis is supported by current source, without requiring a rewrite:

- `app/src/main/java/com/example/ui/LatestLocalSearch.kt:61-66` invalidates results before its 150 ms debounce. `ui/MainViewModel.kt:179-181` also clears AI state during that invalidation.
- `data/LibraryRepository.kt:638-679` falls from empty/failed FTS to LIKE. `data/RoomEntities.kt:294-338` returns unbounded rows and searches six text columns, including OCR text.
- `ui/MainViewModel.kt:203-209` observes full active rows, refreshes search and initiates storage statistics. Its storage calculation recursively traverses directories.
- `data/RoomEntities.kt:405-410` rewrites FTS on every item update. `indexing/AndroidTextExtractor.kt:80-118` repeatedly copies/encodes accumulated PDF text, publishes each page and creates a recognizer per recognition call.
- `ui/screens/LibraryScreen.kt:423,462` builds snippets during composition. `indexing/LocalIndexWorker.kt:12` loads all active items before filtering pending work.
- Backup/restore, permanent deletion and AI retrieval have the repeated queries/scans identified in the audit. These are credible scalability concerns; their actual latency and relative priority still need measurement.

Source paths above are under `app/src/main/java/com/example/` unless fully specified.

## Required amendments before implementation

### 1. Separate stale-result display from request validity

Keep previous results only within a compatible browse scope. Do not display Library or collection A results under collection B's header, or retain actionable items that have been trashed/removed from scope. Define selection behavior while a new query is pending.

Stopping `invalidateAiSearch()` on unrelated Room emissions is necessary but insufficient: `MainViewModel.kt:433-446` also ties AI currentness to `localSearch.currentGeneration`. Split query/scope identity from background refresh identity, retaining privacy/exclusion checks and `InteractiveAiRetrieval.requireCurrent()` checks before dispatch and publication. Query changes, scope changes, relevant edits/deletions and privacy changes must still invalidate or revalidate AI. Existing failure preservation must remain.

### 2. Define search behavior before limiting or ranking rows

- Preserve explicit saved-date/source-date/title sorting. Add or define relevance for query mode; do not silently override the user's chosen sort (`LatestLocalSearch.kt:117-124`).
- Define substring behavior: removing LIKE changes arbitrary mid-token matching. A LIKE `LIMIT` bounds returned rows, not scan work on a miss. Do not describe it as a guaranteed computational bound.
- Specify loaded-page selection, Select All Visible, counts, grouping, stable ordering/tie-breakers, and pagination under concurrent writes. AI eligibility remains the whole allowed scope, independent of the currently loaded UI page.
- Prefer existing dependencies and a small cursor/page contract; Paging 3 is an option only if justified by the interaction.
- Use an FTS4-compatible ranking approach. FTS5's built-in `rank`/`bm25()` is not available simply by writing that SQL against this FTS4 table. Prototype ranking, scoped filters, IDs-first retrieval and paging on representative data before committing the schema contract. See [SQLite FTS3/4 functions and ranking](https://www.sqlite.org/fts3.html#auxiliary_functions_snippet_offsets_and_matchinfo).
- FTS trigger changes must account for full-row Room updates: `UPDATE OF` alone can still fire for assigned-but-unchanged columns. Verify searchable-value changes, trigger repair/reopen behavior, migrations, rebuild and privacy metadata removal. Preserve cancellation when handling FTS errors.

### 3. Preserve data-safety contracts through bulk/restore optimization

Room transactions do not make filesystem changes atomic with database writes. Replace the phrase “commit assets/database atomically” with staged validation/publication, transactional metadata, compensation/reconciliation and recovery after interruption.

Preserve blob reference counts across active and Trash rows, concurrent imports, persisted collection Undo, reminder cancellation and deterministic restore collision handling. Move OCR outside the asset lock, but durably record pending indexing before leaving the commit path so process death between commit and enqueue cannot strand restored items. Preserve search/partial-index behavior while reindexing is pending.

### 4. Specify migrations and compatibility, rather than assuming v6 throughout

Phase 3 may already advance Room before Phase 7. Number migrations from the actual schema at each checkpoint; test all supported upgrade paths and fresh creation against the final schema. Do not modify previously shipped migration meaning to accommodate the new taxonomy.

For GIF-to-Video, updating `items` alone is incomplete: `assets.kind`, imported/restored records, AI targeting, rendering, indexing, stored filter choices and export need consistent normalization. `MIGRATION_5_6` currently updates both items and assets (`RoomEntities.kt:546-548`).

Most critically, `BackupItemRecord.kind` is an `ItemKind` enum (`core/.../backup/EncryptedBackupEngine.kt:43`), decoded before restore (`:330`). Removing `GIF` from the enum before adding a legacy decoder fails before post-decode normalization can run. Keep a wire-compatible legacy value internally or normalize with a tested decoder before removing it from the public taxonomy. Old backups must remain readable. Preserve original GIF bytes/MIME; inspect MIME/extension before the generic VIDEO thumbnail/player path. Do not regenerate provider embeddings as a side effect of migration; binary vector migration must retain model, dimensions and validation.

### 5. Treat app lock as a protected policy

F30 describes a tradeoff, not a proven correctness defect. `MainActivity.kt:165-180` deliberately locks on pause and top-resumed loss; the latter fixed QA011, where Overview could leave the app unlocked without a pause callback. The root worklog and native Overview tests record that history.

Keep immediate lock behavior by default. Do not add a general grace interval or lifecycle exemption as routine UI polish. Any narrowly defined trusted handoff needs a separately explicit policy, cancellation/process-death behavior and native Home/Overview/cold-start/auth rejection tests. Never use a generic “previous surface” crossfade to bypass the lock or collection-scope gate.

### 6. Narrow and correct the voice diagnosis

Auto-start, editable transcript, microphone stop/retry, session guards, distinct error messages and recognizer recreation on retry already exist (`VoiceSearchDialog.kt:56-99,303-317`; `VoiceSearchTest.kt`). The missing capability/model handling and runtime-error fallback remain valid improvement areas; the owner's precise phone-side failure is unverified here.

On API 33+, capability probing must handle a provider that cannot check support without permanently disabling a service that can recognize speech. Model download request is API 33; download listener overload is API 34. Provider creation success is not proof of usable English recognition. Attempt a bounded fallback from failed on-device recognition to an eligible default service, preserve typed input and privacy policy, and avoid a retry loop. Actual listening must still stop when the app genuinely backgrounds or locks. See [Android SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer).

### 7. Correct launcher sizing assumptions

Read-only image measurement confirms the original is 1254×1254 and current preparation crops a 998×998 square: 79.59% of each dimension, 63.34% of area. This establishes the crop, not the owner's exact installed-launcher result.

The adaptive foreground already places that bitmap in a centered 66dp layer (`res/drawable/ic_launcher_foreground.xml:4-7`). The plan's “68–72% of canvas” is ambiguous and must not become 73–78dp of meaningful artwork on the 108dp adaptive canvas. Android specifies the central 66×66dp safe zone. Define measurements separately for the master artwork, legacy PNG and adaptive foreground; avoid padding twice. Preserve the original and inspect actual mask previews before accepting. See [Android adaptive-icon requirements](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive#design-adaptive-icons).

### 8. Correct the backend storage premise and policy

There is no existing backend database/KV integration to reuse. `server/README.md:34-38` already documents process-local replay/rate history, runtime revocations and configured restart-persistent revocations.

- Qualify F36: environment-configured revocations reload after restart; runtime route revocations do not. The latter is the durability gap.
- F37 is valid, but replay exposure is bounded by the five-minute signed-challenge lifetime (`server.ts:196-200`). Multiple replicas also have independent rate budgets. This is a documented limitation, not a demonstrated production incident.
- F38's all-keys sweep is present (`server.ts:780-795`). Preserve existing identity/event caps and fail-closed admission behavior when optimizing it; also consider nonce expiry sweeps.
- Decide required restart/replica/revocation policy before selecting storage. Durable security requirements need an actual persistence design and restart tests; a documented single-instance policy remains a proposed owner decision, not something to silently choose. Do not add paid infrastructure or assume deployment authorization.

Backend work can remain late because it is independent, unless new deployment requirements or runtime evidence change its urgency. The prepared automatic-registration rollout remains undeployed per the current worklog; Android improvements alone cannot establish live AI acceptance.

### 9. Reduce speculative scope and make gates measurable

Start Phase 1 with reproducible search/no-hit/typing/PDF-under-search baselines and minimal privacy-safe counters. Grow the fixture suite at the relevant phases instead of requiring instrumentation of every subsystem before the first fix. Distinguish query execution time, debounce-inclusive publication time, main-thread stalls and frame metrics; record device, dataset, build mode and warm/cold conditions. The 150/300ms figures are targets, not observed results or universal guarantees.

Use Phase 10 for demonstrated duplication/error-handling problems. File length alone does not justify splitting repository, ViewModel, server and dialogs in one mandatory package. An owner-facing diagnostics export is additional product scope; prefer focused internal evidence unless that feature is accepted. Lifecycle-aware collection is reasonable; it does not itself prove recomposition/jank is solved. Consolidate state only where measurements and cohesion justify it.

## Disposition of all finding IDs

“Accepted” means the source pattern exists and merits the proposed targeted investigation/fix, not that runtime severity or a fix has been proven. “Qualified” needs the amendments above. No finding is closed by this review.

| IDs | Disposition |
| --- | --- |
| F01, F12, F29 | Accepted symptom mechanism; qualified scope/request-validity fix |
| F02, F03, F09, F24, F25, F26 | Accepted query problems; define substring, sorting, pagination and failure semantics |
| F04, F05, F08, F11, F13 | Accepted heavyweight observation/FTS/storage/snippet patterns; preserve callers and data safety |
| F06, F07, F14, F15, F16 | Accepted indexing/worker/DB patterns; retain byte caps, page provenance, cancellation, queue serialization and process boundaries |
| F10, F22, F23 | Accepted vector parsing and AI query/ranking overhead; version the codec and preserve retrieval/privacy contracts |
| F17, F18, F19, F20, F21 | Accepted repeated work; qualified bulk/restore changes as above |
| F27, F28 | Source layout is confirmed; adaptive grid and compact controls require rendered accessibility validation |
| F30 | Security-policy tradeoff; no automatic grace-period change |
| F31 | Non-lifecycle collection confirmed; runtime/recomposition impact unmeasured |
| F32 | Dead production SearchScreen confirmed; port its test coverage to Library before removal; existing tests also cover Library |
| F33 | Maintainability observation; defer broad splitting absent a demonstrated need |
| F34 | Silent/generic error paths confirmed; targeted privacy-safe diagnostics accepted; export UI is extra scope |
| F35 | Benchmark gap accepted; narrow initial harness, grow through phases |
| F36 | Qualified: runtime revocations are volatile; configured revocations survive restart |
| F37, F38 | Process-local state and all-keys cleanup confirmed; calibrate persistence to explicit deployment requirements |
| N39 | Dedicated Ask AI row confirmed; inline action must retain accessible target/label, cancellation and narrow/large-text reachability |
| N40 | Remaining robustness gaps accepted; current retry behavior already exists; exact device cause unverified |
| N41 | Proposed product taxonomy change; not evidence that true GIF decoding is absent; require compatibility path |
| N42 | Crop confirmed; launcher causality and final dimensions require mask/device evidence |

## Execution sequence if the revised scope is accepted

Retain the phase dependency order: **1 baseline → 2 search state/compact controls → 3 scoped queries/paging/projections/FTS → 4 PDF/workers/storage → 5 bulk/backup/restore → 6 voice → 7 media normalization → 8 launcher/adaptive layout/lifecycle collection → 9 vector/retrieval → 10 targeted cleanup/diagnostics → 11 backend policy and agreed implementation → 12 integrated gates.**

App-lock policy, large refactors and diagnostics export are conditional scope decisions, not automatic Phase 8/10 work. Define search behavior and migration contracts before their phase implementation. Keep each checkpoint independently verifiable; no overlapping writers on shared search/schema contracts. Preserve the exact existing private candidates, historical QA ledger, permanent identity and approved branch boundary.

At the final gate require observed core/app/server checks, lint, debug and optimized runtime suites, supported migrations/backups, performance comparisons and the applicable failure/concurrency cases. Owner voice/launcher/WhatsApp/native-auth and live-provider acceptance stay distinct. Signing, publication, deployment, merge, uninstall and physical-device operations need task-specific authorization.

**Next action:** incorporate/accept these amendments before starting Phase 1. This document is a review artifact; `CODEX_WORKLOG.md` remains the single current progress checkpoint. Supplied documents were not modified.
