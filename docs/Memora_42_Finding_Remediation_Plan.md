# Memora — 42-Finding Remediation Plan

**Status:** OWNER AUTHORIZED — PHASE 1 VERIFIED; PHASE 2 NEXT (27 October 2026)  
**Source:** historical audit ZIP has no commit/hash; current execution baseline is `314c2e9a16d038537f33762d3413fd2911efdb0a` on `codex/v1.0.1-final-qa`.  
**Base audit:** [Memora_Debug_Audit.md](Memora_Debug_Audit.md), preserved byte-for-byte.
**Review and amendments:** [CODEX_REMEDIATION_REVIEW_2026_10_27.md](CODEX_REMEDIATION_REVIEW_2026_10_27.md).
**Progress ledger:** [../CODEX_WORKLOG.md](../CODEX_WORKLOG.md), the single durable checkpoint.
**Authority:** owner explicitly authorized correcting, importing and starting this plan after the review. The four proposed owner additions are included with the compatibility/security amendments below; immediate app-lock protection remains unchanged.  
**Scope:** all 38 original audit findings + 4 owner additions from 27 Oct 2026.

## 0. Codex review gate

Before changing production code, Codex must:

1. Read `AGENTS.md`, `docs/CODEX_V1_0_1_MASTER_PROMPT.md`, `docs/CODEX_V1_0_1_WORKLOG.md`, and this plan.
2. Apply this owner-authorized revised plan as the new remediation addendum. GIF becomes Video at the user-facing category level, with legacy wire decoding retained. Preserve the immutable earlier master, existing data/backup compatibility and the approved 24 October safety/privacy decisions.
3. Challenge findings against the current checkout. The completed review records qualified diagnoses and safer fixes; inspect each phase's callers/contracts before implementation. Static acceptance does not establish measured severity or close a finding.
4. Review completed in CODEX_REMEDIATION_REVIEW_2026_10_27.md. Keep rulings, stage, evidence and exact next action in the root worklog, without a competing progress system.
5. Do not merge to `main`, tag, publish, deploy, sign, uninstall, or destroy user data as part of this remediation unless the owner separately authorizes it.
6. Review amendments and execution are now authorized. Implement in phase order, using the existing QA checkout. Commit only observed-green checkpoints with explicit file lists; preserve unrelated classes.jar/docs/qa/. No fresh approval is required between ordinary reversible implementation tasks.

---

# 1. Four new owner findings — verification

## N39 — Ask AI wastes an entire row — CONFIRMED

**Evidence:** `LibraryScreen.kt:228-245` creates a full-width `Column`, then a full-width `Row` whose only normal content is the right-aligned `Ask AI` button. `SearchScreen.kt` contains another implementation even though it is effectively dead production UI.

**Decision:** Remove the dedicated Ask AI row. Put the AI action inside the search control itself:
- non-empty query: compact sparkle/AI icon in the search field trailing actions, next to Clear;
- empty query: microphone remains the main trailing action;
- while AI is running: replace sparkle with a tiny inline progress indicator;
- AI answer/progress/error may appear below search, but the trigger itself never consumes a row.

This is also part of removing the overloaded search chrome in F27.

## N40 — Speech service is unavailable repeatedly — ROBUSTNESS DEFECT CONFIRMED; exact phone-side cause requires runtime evidence

**Evidence:** Android SpeechRecognizer visibility, on-device/default creation, auto-start, editable transcript, session guards, error messages and recreation on manual retry already exist. Remaining gaps are capability/model handling and runtime fallback when an on-device service is created successfully but fails to recognize. Exact owner-phone root cause remains unverified; do not describe existing retry as missing.

**Decision:** Keep Android-native speech, but make it a managed capability rather than a one-shot API call. See Phase 6.

## N41 — GIF behavior/category is not useful — PRODUCT DECISION CHANGED

**Evidence:** True `.gif` / `image/gif` files are currently classified as `GIF`, Coil GIF decoders are registered, and GIFs are routed through `AsyncImage`. WhatsApp-style “GIFs” can legitimately arrive as `video/mp4`, which the app intentionally classifies as `VIDEO`. This explains why the product distinction is unreliable from the user's point of view even though true GIF decoding exists.

**Decision:** Remove GIF as a user-facing item category. Normalize `GIF` rows to `VIDEO` in the next Room migration. Preserve MIME-aware rendering: real `image/gif` continues to animate through Coil, while MP4 uses video preview/playback. The user sees one Video category/filter/icon.

## N42 — Launcher is cropped/zoomed — CONFIRMED

**Evidence:** the owner original is `docs/branding/memora/originals/launcher-original.png` at 1254×1254. `scripts/prepare_memora_assets.py` detects visible bounds, crops to a 998×998 region, then resizes. That retains only ~79.6% of the original canvas before adaptive-icon masking, creating the zoomed result.

**Decision:** stop content-bound cropping. Build launcher assets from the original master with a deliberate safe-zone layout: smaller centered inner mark, stable border/gutter using the existing Memora color token, and previews under common adaptive masks.

---

# 2. Implementation strategy

The 42 findings are grouped by root cause instead of being fixed one-by-one. This reduces churn and prevents a search fix from being broken again by indexing, observer, or UI work.

## Phase 1 — Baseline, instrumentation, and safety harness

**Purpose:** measure the current behavior before changing architecture and make later regressions visible.

### Work
- Start with an opt-in synthetic Room/JVM harness for real repository/FTS search, result counts, SQL query/fallback counts and latest-query generation/debounce behavior. Record dataset, source, host, SDK/build mode, first/warm samples and p50/p95/p99. Use test-only callbacks where possible. Add observer/PDF/storage/AI/backup counters when their owning phase needs them; no broad telemetry subsystem.
- No raw note/OCR/query content in diagnostics.
- Build synthetic fixtures for 1k, 10k, and 50k items, including large OCR/PDF text rows.
- Initial cases: title hit, OCR/body hit, no-hit, broad one-character query, scoped kind/collection/date projection and PDF-text updates while searching. Add the following coverage at the owning phases:
  - title hit;
  - body/OCR hit;
  - no-hit search;
  - one-character query;
  - collection/date/type filtered search;
  - import while searching;
  - PDF indexing while searching;
  - 100-file import;
  - collection count screen;
  - empty trash;
  - backup/restore;
  - AI candidate retrieval.
- Capture pre-change typing/invalidation behavior without codifying it as desirable. Phase 2 adds the failing-then-passing regression that previous compatible results remain visible. No Android behavior change is needed for Phase 1.

### Files likely involved
- `app/src/test/java/com/example/ui/SearchPerformanceBaselineTest.kt` and existing Room/search tests; opt-in Gradle property in `app/build.gradle.kts`, ignored results under `artifacts/remediation-20261027/`; no new benchmark module or dependency for the initial harness;
- `MainViewModel.kt`, `LatestLocalSearch.kt`, repository/indexing code only for diagnostic hooks.

### Findings covered
F35, supports validation of all performance findings.

### Gate
Capture actual baseline numbers for 1k/10k/50k synthetic rows before Phase 2, plus deterministic generation/debounce characterization. JVM/Robolectric timing is host evidence only; emulator/physical latency and frame metrics remain separate and pending. Add benchmarks for imports/PDF contention and heavy operations in their owning phases. Fixture creation is outside query timing. Never label targets or unavailable measurements PASS.

---

## Phase 2 — Search UX/state machine + Ask AI placement

**Purpose:** eliminate the visible “loading on/off” problem immediately and simplify the Library search surface.

### Work
1. Replace `resultsReady` as a catch-all with explicit state:
   - `initialLoad`;
   - `queryPending`;
   - `searchInFlight`;
   - `hasPreviousResults`;
   - `searchError`.
2. A keystroke must **not** blank/cover existing results before debounce within the same compatible scope. Keep displayed-context identity separate from pending query identity; clear or safely replace results on collection/privacy/Trash changes. Disable stale selection actions while a replacement query is pending.
3. Keep old results visible through the 150 ms debounce and while the replacement query is fast.
4. Show blocking/central loading only for initial load with no prior content. For slow replacement searches, use a small inline progress affordance after a threshold (~250–300 ms).
5. Do not cancel/erase Ask AI because an unrelated Room item changed. AI currently checks localSearch.currentGeneration as well as its own generation: decouple background refresh from query/scope identity, while retaining privacy/exclusion, membership, Trash, content-version and before-dispatch/publication revalidation. Relevant changes still invalidate/revalidate.
6. Coalesce database-driven refreshes so background indexing/import cannot repeatedly flash search.
7. Put Ask AI inside the search field trailing actions; remove the dedicated button row.
8. Collapse type/date/group/sort controls behind one compact Filters/Sort action. Active filters remain visible as removable chips only when active.
9. Remove the dead duplicate `SearchScreen` implementation or refactor it to shared production components; tests must target the actual Library route.
10. Preserve local results when AI is running or AI fails.

### Main files
- `ui/LatestLocalSearch.kt`
- `ui/MainViewModel.kt`
- `ui/screens/LibraryScreen.kt`
- `ui/screens/SearchScreen.kt`
- `ui/components/SearchStateComponents.kt`
- `ui/components/SearchResultsContent.kt`

### Findings covered
F01, F12, F27, F29, F32, N39.

### Acceptance
- Typing 20 characters quickly produces no full-row/full-screen loading flash.
- Existing results remain visible until replacement results are ready.
- Background PDF/index updates do not restart visible search on every checkpoint.
- Ask AI consumes no dedicated vertical row.
- AI failure leaves local results untouched.
- Navigation between Library/collection scope does not flash a full-screen spinner.

---

## Phase 3 — Search database/query architecture

**Purpose:** make search scale instead of getting slower as the library grows.

### Work
1. Remove automatic unbounded six-column `%LIKE%` fallback from ordinary typing.
2. Define one consistent query semantics layer for punctuation/code terms (`C++`, hyphens, literal AND/OR/NOT, quotes).
3. FTS token/prefix search is the normal typing path. Preserve the distinction from arbitrary mid-token substring search; do not silently claim equivalent semantics. If an explicit secondary substring action is retained, cap selected metadata/work and explain its behavior; LIMIT alone does not bound a no-hit scan. In particular:
   - only after longer idle time;
   - strict bounded result count;
   - metadata fields first;
   - never a silent fallback after arbitrary FTS exceptions.
4. Push filters into SQL: type, collection, favorite/reminder, date basis/range, trash scope, and sort.
5. Define relevance mode using an FTS4-compatible implementation (matchinfo/IDs-first ranking or verified equivalent), with title > filename > body/extracted text > AI metadata and stable ID/date tie-breakers. Preserve explicit saved/source/title sort modes. Do not assume FTS5 rank/bm25 built-ins exist on FTS4.
6. Add bounded first-page queries and explicit keyset/cursor paging; add Paging 3 only if existing tools cannot support the chosen interaction. Define stable tie-breakers, grouping/counts, concurrent-write behavior and Select All Visible over loaded pages. AI searches the full eligible scope independently of UI pages. No hidden loss of bulk-action scope.
7. Replace browse `SELECT * ItemEntity` with lightweight `ItemSummary` projection. Large content/OCR/AI bodies are fetched only for detail/search needs.
8. Decouple FTS updates from unrelated mutations. Guard actual searchable value changes, since Room full-row UPDATE can assign unchanged columns even with UPDATE OF triggers. Test callback repair/reopen, fresh creation, migrations, rebuild and privacy metadata removal.
9. Generate match snippets in the query/search layer or an off-main cache, never by scanning megabytes during Compose.
10. Unexpected FTS errors are logged/diagnosed and may expose a rebuild action; they must not silently become a full scan.
11. Add/validate indices where query plans justify them:
   - `items(isTrash, createdAt)`;
   - `items(isTrash, trashTimestamp)`;
   - `items(contentHash)`;
   - possibly `items(isTrash, kind)`;
   - `reminders(itemId)`;
   - `analysis_jobs(status, updatedAt)`;
   - `search_documents(itemId, chunkIndex)`.

### Main files
- `data/RoomEntities.kt`
- `data/LibraryRepository.kt`
- `ui/LatestLocalSearch.kt`
- `ui/LocalMatchSnippet.kt`
- `ui/components/SearchResultsContent.kt`
- `ui/screens/LibraryScreen.kt`
- Room migration files/tests: number from the actual schema at each phase, preserve shipped migration meaning, and verify all supported upgrade paths/fresh schema equivalence.

### Findings covered
F02, F03, F04, F05, F09, F11, F14, F24, F25, F26.

### Acceptance
- No ordinary typed query can execute an unbounded `%LIKE%` across full OCR bodies.
- Search returns a bounded first page.
- Filtered search correctness is identical whether matches are title/body/subtype matches.
- Favorite/reminder/status-only mutations do not rewrite the FTS document.
- No full extracted-text scan occurs from a Composable.
- Warm on-device 10k-item reference benchmark target (query execution excluding the 150ms debounce; publish latency measured separately): p95 local result publication <=150 ms for normal FTS queries and no-hit queries; 50k target <=300 ms. If hardware differs, Codex must record device/emulator and compare against baseline rather than fake a pass.

---

## Phase 4 — PDF/OCR/indexing/import/storage pipeline

**Purpose:** stop background work from attacking search and the database.

### Work
1. Rework PDF extraction to maintain incremental byte counts; no repeated full `toString().toByteArray()` on every page.
2. Reuse one ML Kit text recognizer for an entire extraction job/batch and close once.
3. Preserve byte-based UTF-8 caps (not String.length), page provenance, crash/partial-index recovery and cancellation-safe recognizer close. Checkpoint PDF progress at a controlled cadence (e.g. every 5–10 pages or >=750 ms), not every page.
4. Prefer chunk/progress storage that does not rewrite the entire `items.extractedText`/FTS document each checkpoint; publish final searchable text once on completion when possible.
5. Coalesce observer reactions during capture/import batches. 100 imported items must not cause 100 expensive search/storage refresh cascades.
6. `LocalIndexWorker` queries only pending items with the fields it needs, in bounded batches.
7. Replace blocking WorkManager `.get()` calls with coroutine `await()` and narrow mutex scope.
8. Create one app-level Room database/repository instance reused by UI/workers/services inside the process.
9. Stop recursive blob/cache `walkTopDown()` on every item/trash emission. Use debounced/on-demand storage stats or maintained counters.

### Main files
- `indexing/AndroidTextExtractor.kt`
- `indexing/LocalIndexer.kt`
- `indexing/LocalIndexWorker.kt`
- `capture/CaptureBatchProcessor.kt`
- `data/LibraryRepository.kt`
- `PersonalLibraryApp.kt`
- `MainViewModel.kt`

### Findings covered
F06, F07, F08, F13, F15, F16.

### Acceptance
- A 100-page PDF does not write the full accumulated OCR body 100 times.
- One recognizer instance per extraction job/batch.
- Indexing a large PDF while searching does not repeatedly flip visible search state.
- 100-file import produces bounded/coalesced observer-side heavy work.
- Storage-stat directory traversal cannot overlap repeatedly due to emissions.

---

## Phase 5 — Bulk library operations, collections, backup and restore

**Purpose:** eliminate O(n²)/N+1 behavior outside search.

### Work
1. Collection item counts come from one grouped Room query/map, not `collections × allItems` scans in Compose.
2. Permanent delete/Empty Trash becomes a bulk transaction:
   - batch item deletion;
   - SQL content-hash reference counts;
   - one asset cleanup pass;
   - one scheduler/reminder cleanup pass where possible.
3. Backup snapshot loads memberships once, builds maps/sets, no per-item collection query.
4. Restore loads collection/pending-deletion state once and uses ID sets/maps rather than repeated DAO calls and `.any` scans.
5. Restore uses validated staging, transactional metadata and compensation/reconciliation for filesystem publication; do not claim a Room transaction makes filesystem changes atomic. Persist pending local indexing before releasing the asset mutex, then enqueue bounded WorkManager jobs outside the lock. Recover if killed between commit and enqueue.
6. Validate interruption/cancellation, concurrent imports, active/Trash blob reference counts, persisted collection Undo, reminder cleanup and deterministic collision handling; committed data stays durable and abandoned staging is cleaned.

### Main files
- `ui/screens/CollectionsScreen.kt`
- `data/LibraryRepository.kt`
- Room DAO/query definitions
- restore/backup tests.

### Findings covered
F17, F18, F19, F20, F21.

### Acceptance
- Collection count work is O(items + memberships), not O(collections × items).
- Emptying a large Trash does not execute one whole-library scan per item.
- Backup/restore query count is bounded by logical batches rather than item count.
- Restore no longer holds the global asset mutex while OCR/indexing runs.

---

## Phase 6 — Voice search reliability and UX

**Purpose:** make voice either work reliably or fail honestly/actionably instead of permanently saying the service is unavailable.

### Work
1. Extract speech logic from Compose into a testable `VoiceRecognitionManager`/controller.
2. Preserve the requested interaction:
   - first opening from the small search-bar mic auto-starts after permission;
   - big mic tap while listening stops;
   - tap again starts a fresh recognition session;
   - transcript remains editable;
   - Search submits the same final text path as typed search.
3. Capability selection:
   - API 31+: prefer on-device recognition only if actually available;
   - API 33+: call `checkRecognitionSupport()` for the chosen intent/service;
   - if English model is supported but not installed, use API 33 model-download requests / API 34 listener overload where supported, with explicit UI; treat ERROR_CANNOT_CHECK_SUPPORT as an unsupported probe rather than proof recognition is unavailable;
   - otherwise use default installed recognizer.
4. Add provider/service diagnostics (package/component, on-device/default path, Android error code) to privacy-safe diagnostics.
5. Handle `ERROR_SERVER`, `ERROR_SERVER_DISCONNECTED`, `ERROR_RECOGNIZER_BUSY`, `ERROR_CLIENT`, network errors and language errors separately. Recreate recognizer on retry; do not retry a poisoned instance.
6. If the normal recognizer repeatedly fails but a system speech-recognition activity is resolvable, provide/use a system-recognizer fallback rather than leaving a dead mic.
7. If no recognizer exists, disable/hide the mic and show a precise one-time explanation instead of presenting a feature that can never work.
8. Review lifecycle teardown. Do not destroy a healthy session merely because a permission/system surface causes a transient lifecycle event; clean up on actual dialog disposal/background conditions that require it.
9. Keep `RECORD_AUDIO` permission minimal and no always-listening behavior.

### Main files
- `ui/components/VoiceSearchDialog.kt`
- new voice controller/manager
- `AndroidManifest.xml` only if service/fallback visibility changes are required
- voice tests/instrumentation.

### Findings covered
N40; also strengthens F34 diagnostics.

### Acceptance
- On the owner's physical phone, first open auto-starts after permission.
- Big mic correctly toggles start/stop/retry.
- If an installed recognizer can serve English, the app must not remain permanently in “service unavailable.”
- If the device truly has no usable recognizer, UI states exactly that and does not fake a retry loop.
- Test on at least one API 31–32 device/emulator and one API 33+ device/emulator, plus the owner's phone before calling it closed.

---

## Phase 7 — Media model: merge GIF into Video

**Purpose:** make the product taxonomy match what users actually receive from apps like WhatsApp.

### Work
1. Remove GIF from the public taxonomy/filters/icons/colors while retaining a legacy internal enum/wire value or a tested pre-decode compatibility adapter. BackupItemRecord.kind decodes an ItemKind before restore, so post-decode normalization alone cannot handle a removed enum.
2. Add the next migration after earlier phase schema changes: normalize GIF to VIDEO in both items and assets, along with stored filter/search metadata. Test old DB upgrades and legacy backups before removing any wire representation.
3. `ContentClassifier`: `.gif` / `image/gif` becomes top-level `VIDEO` kind.
4. Rendering must be MIME-aware rather than kind-only:
   - `image/gif`: Coil animated image renderer;
   - `video/*`: existing video thumbnail/player/external-open flow.
5. Existing WhatsApp MP4 “GIFs” remain VIDEO naturally.
6. Update AI kind targeting, search filters, backup/restore compatibility, migration fixtures and presentation tests.
7. Old backups containing `GIF` remain readable and normalize to VIDEO on restore.

### Main files
- `core/.../ContentClassifier.kt`
- `core` ItemKind/model definitions
- `data/RoomEntities.kt` + migrations
- `ui/components/CommonComponents.kt`
- `ui/components/DialogComponents.kt`
- `ui/components/ItemPreviews.kt`
- `ai/AiGateway.kt`
- backup/restore tests and docs.

### Findings covered
N41.

### Acceptance
- No separate GIF filter/category remains.
- True `.gif` files still animate correctly while appearing under Video.
- MP4 GIFs play as video.
- Existing GIF database rows and old backups migrate without loss.

---

## Phase 8 — Launcher/branding correction + responsive UI/lifecycle polish

**Purpose:** fix the visibly wrong icon and remaining medium-impact UI/lifecycle problems.

### Launcher work
1. Use `docs/branding/memora/originals/launcher-original.png` as the immutable source.
2. Delete the automatic visible-bounds crop in `scripts/prepare_memora_assets.py`.
3. Generate a master launcher canvas with:
   - existing Memora navy/background token (`#1E293B`) as the outer border/gutter unless the owner later specifies a different existing token;
   - centered original artwork, with separately measured legacy PNG and adaptive foreground layouts;
   - adaptive layers 108dp, meaningful foreground inside the central 66dp safe zone;
   - do not apply the old 68–72% master ratio directly to the adaptive canvas or add padding twice; current foreground already uses a 66dp centered layer;
   - approve generated mask previews before physical launcher acceptance.
4. Generate all legacy density PNGs and adaptive icon layers from this one master.
5. Preview circle, squircle, rounded-square and OEM-style masks before accepting.
6. Add an automated asset test that prevents future crop-to-content behavior and checks expected dimensions/source provenance.

### Other UI/lifecycle work
- Replace fixed 2-column grids with adaptive grids.
- Move screen Flow collection to `collectAsStateWithLifecycle()` and aggregate cohesive screen UI state to reduce recomposition fan-out.
- Preserve immediate app-lock and QA011 top-resumed-loss/Overview protection. Review only narrowly defined handoffs; any policy relaxation remains separate scope requiring an explicit decision and native privacy regression proof. Do not add a general grace period as UI polish.

### Main files
- `scripts/prepare_memora_assets.py`
- `docs/branding/memora/originals/launcher-original.png`
- `res/drawable*/mipmap*` launcher resources
- `ui/screens/LibraryScreen.kt`
- `ui/screens/CollectionsScreen.kt`
- `MainActivity.kt`
- screen state collection call sites.

### Findings covered
F28, F30, F31, N42.

### Acceptance
- Launcher no longer looks zoomed on physical launcher.
- Inner mark is visibly smaller and centered with a stable border/gutter.
- No adaptive mask clips the meaningful artwork.
- 150% font, landscape, small phone and tablet/split-screen do not break grid/card layout.
- File picker/external viewer does not cause unreasonable repeated auth unless immediate-lock mode explicitly requires it.

---

## Phase 9 — AI retrieval/vector performance

**Purpose:** remove avoidable parsing, query chatter and giant-text ranking work.

### Work
1. Migrate embedding storage from comma-separated `String` to binary Float32 BLOB with a Room converter/explicit codec.
2. Version the embedding representation so existing text vectors can be migrated/rebuilt safely.
3. Batch retrieval of `search_documents` for candidate item IDs; no one-query-per-item loop.
4. Remove repeated `lower(title || content || extractedText || ...)` scans for each term. Use FTS rank/column weights or compact normalized metadata.
5. Bound candidate sets and chunk counts before expensive vector work.
6. Measure allocations/GC and query count in benchmark fixtures.

### Main files
- `data/RoomEntities.kt`
- `indexing/DurableAiWorker.kt`
- `ai/InteractiveAiRetrieval.kt`
- `MainViewModel.kt`
- vector migration/tests.

### Findings covered
F10, F22, F23.

### Acceptance
- No runtime `split(",").map { toFloat() }` for stored embeddings.
- One AI query does not issue ~160 document queries.
- Large OCR bodies are not repeatedly concatenated/lowercased for each ranking term.
- Grounding/citation behavior remains unchanged or improves.

---

## Phase 10 — Architecture, diagnostics and maintainability hardening

**Purpose:** prevent the same cross-layer regressions from returning.

### Work
1. Extract only responsibilities whose duplication or coupling caused a demonstrated defect. File length alone is not an implementation requirement. Possible boundaries when justified:
   - repository: items/search/assets/backup/reminders;
   - ViewModel: browse/search/AI/capture/settings;
   - server: auth/provider/routing/rate-limit modules;
   - UI dialogs/components by feature.
2. Replace silent generic catches with structured, privacy-safe diagnostics and specific expected-exception handling.
3. Use focused privacy-safe internal evidence with operation/duration/count/error class/build info. An owner-facing export/screen is deferred additional product scope unless separately requested; never include personal content.
4. Keep one source of truth for search UI and behavior.

### Main files
- `LibraryRepository.kt`
- `MainViewModel.kt`
- `DialogComponents.kt`
- `SettingsScreen.kt`
- `AiGateway.kt`
- `server/src/server.ts` or current server entry.

### Findings covered
F33, F34.

### Acceptance
- Search/index/media failures are diagnosable without exposing personal data.
- No dead duplicate production-equivalent screen remains.
- Refactoring does not change backup format, package ID, signing identity or existing user data semantics unless explicitly migrated/tested.

---

## Phase 11 — Backend durability/rate limiting

**Purpose:** fix the three server-scale/restart correctness findings.

### Work
1. Correct premise: no backend DB/KV store currently exists. Environment-configured revoked IDs already reload on restart; runtime route revocations are volatile. Define required restart/replica policy and prepare a concrete persistence design if durable runtime revocation is required. No new paid infrastructure or deployment is authorized.
2. Persist/centralize consumed challenge nonces and rate-limit buckets if the service is expected to survive restarts or run multiple replicas.
3. Replace per-request O(all-keys) limiter cleanup with per-key/periodic bounded cleanup, preserving identity/event caps and fail-closed admission. Account for nonce-expiry cleanup too. Signed challenges currently expire after five minutes; calibrate replay-history requirements to that window.
4. Add restart tests:
   - revoked device stays revoked;
   - consumed nonce obeys the explicitly selected restart policy within its five-minute validity window; durable replay prevention requires actual shared persistence;
   - rate-limit state behaves according to documented policy;
   - multiple instances share state if horizontal scaling is supported.
5. Preserve secrets/privacy and do not log enrollment/auth material.

### Main files
- server entry/modules (`server.ts` currently)
- database schema/migrations
- server tests.

### Findings covered
F36, F37, F38.

### Acceptance
- Security state no longer silently resets because one process restarted, unless Codex documents and the owner explicitly accepts a single-process-only policy.
- Rate-limit check complexity is O(1) or amortized O(1) for a normal request.

---

## Phase 12 — Final integrated performance/regression gate

Run after all previous phases are individually green.

### Required automated checks
- Android unit tests.
- Android instrumentation tests, debug + optimized/release-like verification used by this repository.
- Core JVM tests.
- Server typecheck/build/tests.
- Migration tests from every supported database/backup version.
- Search benchmark suite at 1k/10k/50k.
- PDF/index/import concurrency tests.
- Voice fake-provider/error-state tests.
- GIF->VIDEO migration/render tests.
- Launcher asset generation/provenance/safe-zone tests.
- Backup/restore/Trash/collection performance tests.

### Required physical checks
At minimum on the owner's main phone:
1. Search rapid typing, no-result, broad one-letter query, filtered query.
2. Search while importing many files.
3. Search while a large PDF is indexing.
4. Ask AI placement, progress, failure, answer, cancellation behavior.
5. Voice auto-start + big-mic toggle + retry + actual recognition.
6. Real WhatsApp “GIF” and true `.gif` if available; both must appear under Video and render correctly.
7. Launcher icon visual check on home screen/app drawer after cold install/update.
8. App lock around picker/viewer/permission handoffs.
9. 150% font + portrait/landscape.
10. Backup -> restore -> search/media verification.

### Release gate
Do not call the remediation complete merely because tests compile. Close each finding only with evidence tied to its acceptance criterion. Do not publish/sign/release unless separately authorized.

---

# 3. Coverage matrix — all 42 findings

| ID | Finding | Phase |
|---|---|---:|
| F01 | Search loading flicker before debounce | 2 |
| F02 | Unbounded six-column `%LIKE%` fallback | 3 |
| F03 | No search limit/pagination | 3 |
| F04 | Heavy full-library `ItemEntity` observation | 3 |
| F05 | FTS rewritten on any item update | 3 |
| F06 | PDF repeated whole-document copying/checkpoints | 4 |
| F07 | New ML Kit recognizer per PDF page | 4 |
| F08 | Recursive storage scan after emissions | 4 |
| F09 | DB search then Kotlin filter/sort | 3 |
| F10 | Embeddings stored as CSV strings | 9 |
| F11 | Search snippets scan huge text in Compose | 3 |
| F12 | Library changes restart search/cancel Ask AI | 2 |
| F13 | Batch import amplifies observer work | 4 |
| F14 | LocalIndexWorker loads all active full items | 3/4 |
| F15 | Blocking WorkManager futures under mutex | 4 |
| F16 | Multiple Room DB instances | 4 |
| F17 | Collection counts O(collections × items) | 5 |
| F18 | Empty Trash/permanent delete scales badly | 5 |
| F19 | Backup N+1 membership queries | 5 |
| F20 | Restore repeated queries/linear scans | 5 |
| F21 | Restore indexes while holding asset mutex | 5 |
| F22 | AI document retrieval N+1 queries | 9 |
| F23 | AI ranking repeatedly concatenates huge text | 9 |
| F24 | FTS exceptions silently become slow fallback | 3/10 |
| F25 | Search ordered by recency instead of relevance | 3 |
| F26 | Search tokenization edge cases | 3 |
| F27 | Search UI vertically overloaded | 2 |
| F28 | Fixed 2-column grids | 8 |
| F29 | Browse navigation full-screen loading flash | 2 |
| F30 | App lock too aggressive around external UI | 8 |
| F31 | Many non-lifecycle-aware Flow collectors | 8 |
| F32 | Dead duplicate SearchScreen | 2/10 |
| F33 | Giant classes/files | 10 |
| F34 | Silent exception handling/poor diagnostics | 10 |
| F35 | No real performance benchmark infrastructure | 1/12 |
| F36 | Device revocation in memory only | 11 |
| F37 | Nonce/rate state process-local | 11 |
| F38 | Rate limiter O(number of keys) cleanup/request | 11 |
| N39 | Ask AI button wastes a full row | 2 |
| N40 | Speech service repeatedly unavailable | 6 |
| N41 | GIF category unreliable/unwanted; use Video | 7 |
| N42 | Launcher cropped/zoomed | 8 |

---

# 4. Recommended execution order

**Do not parallelize phases that touch the same search/data contracts unless Codex explicitly coordinates them.** Recommended order:

**Review gate -> Phase 1 -> Phase 2 -> Phase 3 -> Phase 4 -> Phase 5 -> Phase 6 -> Phase 7 -> Phase 8 -> Phase 9 -> Phase 10 -> Phase 11 -> Phase 12.**

Search/state/query/indexing come first because they are the biggest user-visible problem and several later features depend on those contracts. Media/branding can then be fixed without destabilizing the search architecture. Backend durability is intentionally late because it is largely independent from the Android performance fixes.

# 5. Non-goals / things Codex must not do casually

- Do not replace Room/FTS simply because search is slow; FTS is not the root problem.
- Do not add a giant generated “resume file” search index.
- Do not fix `%LIKE%` with a normal B-tree index and claim the problem solved.
- Do not hide search delay with longer spinners.
- Do not load full OCR bodies for card/list rendering.
- Do not make GIF MIME data unreadable just because the user-facing category becomes Video.
- Do not redraw/AI-regenerate the launcher. Use the preserved owner original and controlled asset preparation.
- Do not send microphone recordings or library content to a new external provider without explicit owner approval.
- Do not weaken backup encryption, app identity, permanent signing compatibility, or user data safety.
- Do not mark physical voice/icon/media behavior PASS based only on emulator/unit tests.


# 6. Execution checklist and current phase contract

- [x] Review all 42 IDs against current source; preserve findings/qualifications in the review note.
- [x] Owner authorizes updating, importing and executing this revised plan (27 October 2026).
- [x] Preserve original audit under docs and link this plan to the single root worklog.
- [x] Phase 1: opt-in real Room/repository harness verified for 1k/10k/50k; first/warm percentiles and SQL counts captured in `artifacts/remediation-20261027/search-baseline.csv`. Twenty 25ms-spaced keystrokes invalidate 20 times but execute one query after 150ms debounce. Focused tests and FTS integrity pass; host evidence only.
- [ ] Phase 2: failing behavior tests first; preserve compatible results with immutable displayed/request scope, then decouple unrelated refresh from AI request identity while retaining safety checks; integrate compact accessible controls.
- [ ] Phase 3: define SQL/search/paging contracts above, test filtered/no-hit/broad queries and loaded-page actions; implement before schema changes are accepted.
- [ ] Phases 4–11: execute in order with the amended safety and compatibility contracts; record exact files, failing/passing checks and rulings in root worklog at each milestone.
- [ ] Phase 12: complete integrated automated gates and separately list pending physical/live acceptance.

Phase 1 files: create app/src/test/java/com/example/ui/SearchPerformanceBaselineTest.kt; update app/build.gradle.kts only to forward explicit opt-in/output properties to the unit-test JVM; extend existing LatestLocalSearchTest only for measurable baseline characterization when needed. Use the actual repository and Room callback, not a second search implementation. The harness creates no provider requests, user blobs, device operations or production diagnostics. Run it alone with -PmemoraPerformanceBaseline=true; ordinary checks skip the large fixture. Record synthetic-only evidence under ignored artifacts/remediation-20261027 and a concise measured summary in this plan/root worklog. Expected: correctness assertions pass, results include explicit environment/source/method, and timing is reported without claiming device acceptance.
