# Memora / Personal Library — Full Static Debug & Performance Audit

Audited source: `personal-library-codex-v1.0.1-final-qa.zip`

## Executive verdict

The app's core architecture is not fundamentally broken. Room + FTS4 is a sensible base for an offline personal library. The current implementation, however, has several compounding performance mistakes that make search and indexing feel much slower and less stable than they need to be.

The biggest problem is **not** a giant generated “resume” file. There is no such local-search file. Keyword search uses Room FTS4 (`items_fts`). AI retrieval uses `search_documents`. The main slowdown comes from:

1. UI search state is invalidated immediately on every keypress, before the 150 ms debounce, so the loading spinner flashes by design.
2. A no-hit FTS query falls back to an unbounded `%LIKE%` scan over six text columns, including potentially huge `extractedText`.
3. The app continuously observes the entire active library as `ItemWithCollections`, including large text fields, then re-runs search whenever any item changes.
4. Every update to `items`, including favorite/reminder/AI status changes, rewrites the FTS row because the FTS trigger is `UPDATE ON items`, not update-of-searchable-columns.
5. PDF indexing repeatedly copies the entire accumulated text and checkpoints the full text to Room once per page. Those updates in turn cause FTS rewrites, full-library invalidations, search refreshes, and storage scans.
6. Search results are unbounded and have no paging/limit.
7. Storage statistics recursively walk the blobs and cache directories after library emissions, with no coalescing.

These issues reinforce each other. A single large PDF being indexed can generate repeated database writes, FTS rewrites, Room emissions, search invalidations, UI spinner flashes, and filesystem scans.

---

# P0 — Fix first

## P0.1 Search loading flicker is explicitly coded into the state machine

**Evidence**
- `LatestLocalSearch.kt:60-68`: `refresh()` calls `onInvalidated()` immediately, then delays 150 ms for debounce.
- `MainViewModel.kt:175-188`: `onInvalidated` sets `_resultsReady=false` and cancels/clears AI search.
- `SearchStateComponents.kt:105-110`: `!resultsReady` renders `CircularProgressIndicator` + “Searching your library…”
- `MainViewModel.kt:325-330`: every changed trimmed query calls `refreshFilteredResults(debounceQuery=true)`.

**Impact**
Every typed character deliberately makes the UI enter loading for at least the debounce period. Even an instant FTS query therefore looks slow. This exactly explains the reported loading-on/loading-off behavior.

**Fix**
Keep the previous results visible during debounce and during short searches. Track separate states such as `queryPending`, `searchInFlight`, and `initialLoad`. Only show a blocking spinner if there are no prior results or a query has actually exceeded ~250–300 ms. A tiny progress indicator inside the search field is enough for slow requests.

---

## P0.2 The no-result path is the slowest search path

**Evidence**
- `LibraryRepository.kt:638-680`: FTS is tried first. If it returns an empty list, the repository executes `LIKE "%query%"`.
- `RoomEntities.kt:312-340`: fallback performs leading-wildcard `LIKE` against `title`, `content`, `originalFileName`, `extractedText`, `aiDescription`, and `aiConcepts`.
- There is no `LIMIT`.

**Impact**
A leading wildcard prevents a normal B-tree index from helping. SQLite must inspect large text fields row-by-row. Large OCR/PDF text makes this increasingly expensive. Searches that should simply say “no results” become full-library scans.

**Fix**
Do not automatically run a six-column `%LIKE%` scan after every FTS miss. Prefer FTS-only for normal typing. If substring search is a product requirement, run it as a bounded second-stage search after a longer idle delay, with a strict result limit, and preferably only on small metadata fields or a dedicated trigram/normalized index.

---

## P0.3 No pagination or result limit

**Evidence**
- `RoomEntities.kt:294-340`: both FTS and LIKE queries return every matching row.
- The UI then receives full `List<ItemWithCollections>` results.

**Impact**
Short prefixes such as `a`, `p`, `s`, etc. can materialize a huge list, fetch collection relationships, allocate many Kotlin objects, sort/filter them, and feed a large Compose lazy list. Lazy rendering does not prevent the database/list allocation cost.

**Fix**
Use a bounded first page (for example 50–100 rows) and Paging 3 or explicit cursor/offset paging. Search should feel instant even with tens of thousands of items.

---

## P0.4 The main library observer loads heavyweight full rows for the entire library

**Evidence**
- `RoomEntities.kt:6-31`: `ItemEntity` includes `content`, `extractedText`, AI text, metadata, etc.
- `RoomEntities.kt:286-288`: `observeItemsWithCollections()` does `SELECT * FROM items WHERE isTrash=0` and returns `ItemWithCollections`.
- `MainViewModel.kt:203-209`: every emission replaces `_items`, refreshes search, and starts storage-stat calculation.

**Impact**
Browsing the library means keeping all large text bodies in memory even though cards mostly need title/metadata/thumbnail information. Any observed item change causes a new heavyweight list to be delivered.

**Fix**
Create an `ItemSummary` projection for list/grid screens: ID, title, kind, favorite, dates, hash, filename, small AI summary if required. Keep full content/extracted text in a detail/search table or fetch it only for detail/snippet generation. Do not use `SELECT *` for library browsing.

---

## P0.5 Any item update rewrites its FTS document

**Evidence**
- `RoomEntities.kt:405-410`: Room FTS sync triggers are `BEFORE UPDATE ON items` and `AFTER UPDATE ON items`.
- They delete and reinsert all FTS text columns.

**Impact**
Toggling a favorite, changing reminder state, changing AI status, moving to trash, etc. can delete/reinsert the item’s whole FTS row, including large OCR text, even when no searchable field changed.

**Fix**
Decouple searchable text from the mutable item state, or create explicit synchronization only when searchable columns change. `isFavorite`, reminder state, status flags, and similar fields must not force a large FTS document rewrite.

---

## P0.6 PDF extraction is doing repeated whole-document work per page

**Evidence**
- `AndroidTextExtractor.kt:49`: every snapshot calls `bounded(text.toString(), partial)`.
- `AndroidTextExtractor.kt:80` and `87`: every page calls `text.toString().toByteArray(UTF_8).size`.
- `AndroidTextExtractor.kt:86`: a full snapshot is published every page.
- `LocalIndexer.kt:61-70`: every PDF progress callback checkpoints the accumulated full text into Room.

**Impact**
As the PDF grows, each page repeatedly copies and UTF-8 encodes all previous text. This approaches quadratic copy/encoding behavior. Then each checkpoint updates `items.extractedText`, causing FTS sync and Room invalidation. A 100-page PDF can trigger dozens of expensive full-text rewrites.

**Fix**
Maintain an incremental byte count. Do not call `toString()`/UTF-8 conversion on the whole buffer every page. Checkpoint only periodically (e.g. every 5–10 pages or every 500–1000 ms), or write chunks directly to a separate indexing table. Publish `items.extractedText` once at completion if possible.

---

## P0.7 PDF OCR creates a new ML Kit recognizer for every page

**Evidence**
- `AndroidTextExtractor.kt:99-118`: `TextRecognition.getClient(...)` is created and closed inside every `recognize(bitmap)` call.
- PDF OCR calls `recognize()` page-by-page.

**Impact**
Repeated construction/teardown adds avoidable overhead to multi-page documents.

**Fix**
Create one recognizer per extraction job (or a safely reused app-level client) and close it after the whole PDF/image batch.

---

## P0.8 Storage statistics recursively scan the filesystem after data emissions

**Evidence**
- `MainViewModel.kt:274-290`: `walkTopDown()` scans the entire blob tree and the entire app cache.
- `MainViewModel.kt:205-209`: every active-item emission launches `updateStorageStats()`.
- `MainViewModel.kt:220-224`: every trash emission also runs it.

**Impact**
Indexing/importing can cause many emissions and therefore multiple overlapping directory walks on `Dispatchers.IO`, competing directly with search/indexing/file reads.

**Fix**
Debounce/coalesce to a single job, compute stats on demand in Settings, or maintain byte counters transactionally. Never recursively scan all storage after every row update.

---

## P0.9 Search and filters are split across DB + Kotlin in a way that wastes work and can produce inconsistent fallback behavior

**Evidence**
- Repository searches the whole library first.
- `LatestLocalSearch.kt:85-124` then filters kind/collection/date and sorts in Kotlin.

**Impact**
The database may return hundreds/thousands of rows only for Kotlin to discard most of them. More subtly, the decision to run LIKE fallback is based on whether **global** FTS returned anything. If FTS finds a result outside the current filter but the desired in-filter result is only a substring hit, fallback is skipped and the visible result can be incorrectly empty.

**Fix**
Push kind, collection, favorite/reminder, date, sort and limit into the SQL search query. The same scoped query must decide whether a fallback is needed.

---

## P0.10 AI embeddings are stored as comma-separated text and reparsed on every AI search

**Evidence**
- `SearchDocumentEntity.embedding` is `String?` (`RoomEntities.kt:83-92`).
- `DurableAiWorker.kt:206`: float arrays are serialized with `joinToString(",")`.
- `MainViewModel.kt:516-523`: every candidate vector is `split(",").map { it.toFloat() }.toFloatArray()`.

**Impact**
A 768-float vector as text is much larger than a Float32 blob and parsing creates hundreds/thousands of temporary Strings/boxed Floats. This wastes storage, CPU, allocations, and GC time during AI search.

**Fix**
Store vectors as a binary BLOB (`Float32Array`/ByteBuffer), or use a proper vector index/storage layer. Decode once into primitive floats.

---

# P1 — High impact

## P1.1 Search snippets can scan huge text on the Compose/UI thread

**Evidence**
- `LocalMatchSnippet.kt:7-39`: iterates all candidate fields, including full `extractedText`, and repeatedly calls case-insensitive `indexOf`.
- Library list view caches it with `remember` (`LibraryScreen.kt:460-463`).
- Library grid calls `buildMatchSnippet` directly during composition (`LibraryScreen.kt:413-423`).
- `SearchResultsContent.kt:116-127` also calls the snippet function directly while composing.

**Impact**
Recompositions/scroll changes can repeatedly scan large PDF text on the main thread.

**Fix**
Generate snippets in the DB/search worker (`snippet()`/offset metadata) or precompute/cache them off-main keyed by item version + query. Never scan megabytes inside a composable.

---

## P1.2 Full-library data changes restart local search and also cancel AI search

**Evidence**
- `MainViewModel.kt:203-209`: every active-items emission calls `refreshFilteredResults()`.
- `MainViewModel.kt:175-182`: every local refresh calls `invalidateAiSearch()`.

**Impact**
Background indexing or unrelated metadata updates can flash local search and cancel an in-progress Ask AI operation. The user can experience an AI request apparently disappearing because some item status changed.

**Fix**
Separate query/filter generation from library-version invalidation. Do not clear AI answer/search for unrelated item updates. Re-run only when data relevant to the current result set changes, and debounce database-driven refreshes.

---

## P1.3 Batch imports amplify all observer-side work

`CaptureBatchProcessor.kt:24-47` saves files one at a time. Each commit can trigger a full active-library emission, search refresh and storage scan. Importing 50–100 files can therefore produce 50–100 UI/data refresh cycles.

**Fix**
Batch metadata commits where feasible, or at minimum debounce/coalesce expensive observer reactions until the import batch finishes.

---

## P1.4 LocalIndexWorker loads every active full item just to find pending items

**Evidence**
- `LocalIndexWorker.kt:12`: `getAllActiveItems().filter { aiStatus == PENDING || localIndexCoverage == PENDING }`.

**Impact**
Loads large text fields for the entire library into memory before processing a small subset.

**Fix**
Add a DAO query: `SELECT <needed fields> FROM items WHERE isTrash=0 AND (...) LIMIT :batchSize` with an appropriate index/status strategy. Process bounded batches and reschedule.

---

## P1.5 Blocking WorkManager futures while holding a global mutex

**Evidence**
- `LocalIndexer.kt:97+`: `queueMutations.withLock` wraps WorkManager checks/enqueues.
- Lines 114, 118, 143, 153, 161, 209 use `.get()` / `.result.get()`.

**Impact**
The coroutine runs on IO, so it does not directly block the main thread, but it ties up an IO thread and holds a global queue mutex while waiting on WorkManager. Multiple jobs serialize unnecessarily.

**Fix**
Use coroutine `await()` support for ListenableFuture/Operation and keep mutex scope around only the state transition that truly needs serialization.

---

## P1.6 Multiple independent Room database instances are created throughout the process

**Evidence**
`LibraryRepository(...)` is instantiated in MainViewModel, LocalIndexWorker, DurableAiWorker, BootReceiver, ReminderReceiver, CaptureService, CollectionDeletion, etc. Each repository builds its own Room database (`LibraryRepository.kt:52-60`) and enables multi-instance invalidation.

**Impact**
Extra connection pools, callbacks, invalidation plumbing, FTS trigger verification, memory and contention inside the same process.

**Fix**
Create one app-level singleton `LibraryRoomDatabase` and repository (Application/DI). Workers/services should reuse the same DB instance when in the same process.

---

## P1.7 Collection counts are O(collections × items) on recomposition

**Evidence**
- `CollectionsScreen.kt:55-57`: three full item counts.
- `CollectionsScreen.kt:158-160`: each collection runs `allItems.count { item.collections.any { ... } }`.

**Impact**
With many collections/items this becomes expensive and is rerun when the screen recomposes.

**Fix**
Precompute a count map once (`remember(allItems)`), or better return `COUNT(*) GROUP BY collectionId` from Room plus computed smart-folder counts.

---

## P1.8 Permanent delete and empty-trash scale badly

**Evidence**
- `LibraryRepository.kt:44-49`: for each candidate hash, `removeUnreferencedBlobs` may call `getAllItemsIncludingTrash()` and scan the entire list.
- `LibraryRepository.kt:452-456`: empty trash calls `deletePermanently` one item at a time.

**Impact**
Bulk trash deletion can become O(deleted × total items), with one transaction/scheduler operation per item.

**Fix**
Use SQL reference counts (`COUNT WHERE contentHash=:hash`) or batch counts, add an index on `contentHash`, perform bulk database deletion/asset refcount updates in one transaction, and clean files once afterward.

---

## P1.9 Backup snapshot has N+1 queries

**Evidence**
- `LibraryRepository.kt:683-695`: loads items, then calls `getCollectionIdsForItem()` for every item; it also repeatedly uses `allCols.any`.

**Fix**
Fetch all memberships once and use hash maps/sets.

---

## P1.10 Restore does repeated queries and repeated linear scans inside a long transaction

**Evidence**
- `LibraryRepository.kt:837-864`: for every collection, calls `getAllStoredCollections()` and `getPendingCollectionDeletions()`.
- Membership/reminder loops repeatedly call `preview.manifest.items.any { ... }`.

**Fix**
Load existing collections/hidden IDs once before the loop and maintain in-memory maps. Build a `manifestItemIds` set once.

---

## P1.11 Restore re-indexes every imported item synchronously while still holding the global asset mutex

**Evidence**
- `LibraryRepository.kt:800-802`: `commitRestore` enters `assetMutations.withLock`.
- `LibraryRepository.kt:993-999`: after restore, it calls `indexer.indexItem()` sequentially for every restored item before leaving the locked scope.

**Impact**
Large restores can hold the global asset mutex through PDF OCR and indexing. Other imports, deletes and backups are blocked for a long time.

**Fix**
Commit files/database atomically, release the asset mutex, then enqueue background local indexing in bounded WorkManager jobs.

---

## P1.12 Interactive AI retrieval uses an N+1 document query pattern

**Evidence**
- `InteractiveAiRetrieval.kt:87-102`: for each candidate item (up to 160), executes a query to retrieve up to four documents.

**Impact**
Potentially ~160 SQLite queries for one AI search before vector ranking.

**Fix**
Batch document retrieval for candidate IDs and rank/group in one query or one bounded read.

---

## P1.13 Interactive AI relevance SQL repeatedly concatenates/scans large text

**Evidence**
- `InteractiveAiRetrieval.kt:61-69`: builds `lower(title || content || extractedText || ...)` and runs `instr()` once for each search term to rank FTS candidates.

**Impact**
For large extracted text and many concepts this repeatedly allocates/normalizes/scans large strings.

**Fix**
Use FTS match metadata/column weighting or a small normalized metadata field for ranking. Do not concatenate megabytes of text per candidate and per term.

---

## P1.14 Search exceptions are silently converted into expensive fallback scans

**Evidence**
- `LibraryRepository.kt:640-645` and `663-668`: catches any `Exception` from FTS and treats it as an empty result.

**Impact**
FTS corruption/schema/runtime problems are hidden. Instead of surfacing a fault, the app silently enters the slowest path, making a broken index look like “search is slow.”

**Fix**
Catch only expected query-syntax exceptions if necessary. Log/report unexpected DB failures and expose a repair/rebuild path.

---

# P2 — Medium impact / UX / maintainability

## P2.1 Search result ordering is recency, not relevance

FTS queries order by `items.createdAt DESC`. A weak recent match can appear above a strong title match.

**Fix**
Add FTS relevance weighting (title > filename > body/extracted text > AI metadata), with date as tie-breaker.

---

## P2.2 Search tokenization creates surprising edge cases

`SearchQuery.tokens()` strips `+`, `-`, quotes, operators and words `OR/AND/NOT`. Queries such as `C++`, hyphenated identifiers, or literal operator words can become very broad or semantically changed. The fallback then searches the original raw phrase, so FTS and fallback do not use identical semantics.

**Fix**
Define explicit product semantics for punctuation/code terms and keep fallback token semantics consistent with FTS.

---

## P2.3 The search UI is vertically overloaded

Before results, the Library screen can show:
- search input
- Ask AI button
- AI progress/error/answer
- type filter strip
- active-filter chips
- grouping/date/sort horizontal controls

On smaller screens/large text, the user must scroll through controls before seeing content. Search feels heavier than it should.

**Fix**
Keep search box + one compact Filters/Sort button visible. Move grouping/date/sort/type options into a bottom sheet. Make Ask AI a contextual action near the query or shown after local results.

---

## P2.4 Fixed 2-column grids are not adaptive

- `LibraryScreen.kt:385`: `GridCells.Fixed(2)`.
- `CollectionsScreen.kt:109`: `GridCells.Fixed(2)`.

This is fragile with small screens, split-screen, accessibility font scaling, tablets and landscape.

**Fix**
Use `GridCells.Adaptive(minSize=...)` and size-aware card content.

---

## P2.5 Navigation can flash a full-screen spinner while browse scope catches up

`MainActivity.kt:359-382` replaces Library/Collection content with a full-screen spinner when `selectedBrowseId` does not match the requested destination.

**Fix**
Keep the previous surface visible, crossfade, or make browse-context switching an atomic UI-state update.

---

## P2.6 App lock is extremely aggressive around external UI

`MainActivity` locks from lifecycle loss (`onPause` / top-resumed loss). File pickers, external viewers, permission/system UI or other expected handoffs can force another authentication on return.

**Fix**
If the security product requirement allows it, add a short grace period or distinguish app-backgrounding from app-launched trusted flows. Keep the current behavior as an optional “lock immediately” mode.

---

## P2.7 58 `collectAsState()` calls and no lifecycle-aware state collection

There are many independent StateFlows and zero uses of `collectAsStateWithLifecycle`.

**Impact**
Broad screens can recompose for many independent state changes, and state collection is less lifecycle-aware than current Android guidance.

**Fix**
Aggregate cohesive screen state into `LibraryUiState`, `SettingsUiState`, etc., and collect using `collectAsStateWithLifecycle()`.

---

## P2.8 Dead SearchScreen duplicates production behavior and tests a screen users cannot reach

`SearchScreen()` is referenced only by tests; production navigation uses LibraryScreen search. This creates two implementations and can let tests pass against a surface that is not shipped.

**Fix**
Delete the dead screen or extract one shared search-results surface and test the actual production route.

---

## P2.9 Several giant classes/files are regression magnets

Approximate line counts:
- `server.ts`: 1207
- `LibraryRepository.kt`: 1122
- `MainViewModel.kt`: 1003
- `DialogComponents.kt`: 928
- `SettingsScreen.kt`: 630
- `AiGateway.kt`: 601

This is not directly a runtime bug, but it makes ownership unclear and increases the chance that a UI change accidentally triggers storage/network/indexing work.

**Fix**
Split repository responsibilities (items/search/assets/backup/reminders), ViewModel responsibilities (browse/search/AI/capture/settings), UI dialogs, and server auth/provider/routing/rate-limit modules.

---

## P2.10 Silent exception handling destroys diagnosability

There are numerous `catch (_: Exception) {}` / generic fallback paths. Search is the worst example, but restore reindex/rebuild paths and other jobs also suppress failures.

**Fix**
Introduce privacy-safe structured diagnostics: operation name, error class, item type/version, duration, counts — never raw personal text. Add a Diagnostics screen/export for the owner.

---

## P2.11 No performance benchmark infrastructure

The project has many correctness tests (~69 test files) but no macrobenchmark/baseline-profile/jank suite and no meaningful large-library search latency test. Existing search fixtures are tiny. Some tests explicitly expect the loading state immediately after refresh, thereby codifying the spinner behavior.

**Fix**
Add automated benchmarks for at least 1k/10k/50k metadata rows, large extracted text, no-hit search, one-character prefix, import while searching, PDF indexing while searching, collections count, restore, and first-launch. Track p50/p95/p99 latency and dropped frames.

---

# P3 — Backend / scale / reliability

## P3.1 Device revocation is in memory only

`server.ts:144-160, 391-399`: revoked device IDs are loaded from env and then stored in a process-local Set. A device revoked at runtime is no longer revoked after a backend restart unless someone updates the environment configuration.

**Fix**
Persist revocations (database/KV) or issue credentials with revocation/version state that can be checked persistently.

---

## P3.2 Challenge replay cache and all rate limits are process-local

`DeviceRegistry.consumedNonces` and `RateLimiter.requests` are in-memory Maps. Restarting the process resets them; multiple replicas would not share them.

**Fix**
For a tiny one-instance private deployment this may be acceptable, but it is not robust architecture. Persist/centralize if the backend is expected to scale or provide durable security controls.

---

## P3.3 Rate limiter does O(number of keys) cleanup on every request

`server.ts:780-795` iterates the entire requests map each time `checkRateLimit` is called.

**Fix**
Expire only the current key, use a token bucket/leaky bucket, or periodic cleanup.

---

# Database/index recommendations

The following normal indices are worth adding where the query plan benefits:

- `items(isTrash, createdAt)`
- `items(isTrash, trashTimestamp)`
- `items(contentHash)`
- possibly `items(isTrash, kind)` depending on SQL-filter migration
- `reminders(itemId)` (unique if product semantics guarantee one reminder per item)
- `analysis_jobs(status, updatedAt)`
- `search_documents(itemId, chunkIndex)`

Note: none of these makes `LIKE '%query%'` fast. The leading-wildcard fallback needs architectural change, not just an index.

---

# What is already good

- Using Room + FTS4 for offline keyword search is the right base.
- Content-addressed blob storage/reference counting is a solid design direction.
- Search uses a generation counter and coroutine cancellation to reject stale publication.
- File/OCR work is generally dispatched off the main thread.
- AI privacy guard/revalidation logic is unusually careful.
- Release builds enable minification/resource shrinking.
- The project has substantial correctness test coverage.

The performance problem is therefore fixable without throwing the app away.

---

# Recommended fix order

## Phase 1 — Make search feel fast immediately

1. Stop setting `resultsReady=false` on every keypress/debounce.
2. Keep old results visible while a new query is pending.
3. Add a hard search result limit/paging.
4. Remove automatic six-column `%LIKE%` fallback from ordinary typing.
5. Debounce/coalesce Room-driven search refreshes and storage-stat scans.
6. Stop unrelated library updates from clearing AI search/answer.
7. Cache/generate snippets off the UI thread.

Expected result: the visible loading flashes should largely disappear even before deeper DB work.

## Phase 2 — Fix scalability

1. Replace full `ItemEntity` library observation with `ItemSummary` projections.
2. Push filters/sort/collection/date into SQL.
3. Restrict FTS synchronization to actual searchable-field changes.
4. Batch PDF indexing checkpoints and reuse one OCR recognizer.
5. Use one singleton Room DB/repository.
6. Add proper DB indices.

## Phase 3 — Heavy operations

1. Store embeddings as binary primitives, not CSV strings.
2. Batch AI candidate document retrieval.
3. Remove backup/restore N+1 loops.
4. Release the asset mutex before post-restore indexing.
5. Bulk-delete trash/assets.
6. Add performance benchmarks and baseline profiles.

---

# Test/build status for this audit

- `scripts/test_memora_qa.py`: **3 tests passed** in this audit environment.
- Full Android Gradle tests could not be rerun because the sandbox has no network and the Gradle wrapper attempted to download Gradle 9.3.1 from `services.gradle.org`.
- Server tests could not be rerun because `node_modules` is not included in the zip and the sandbox is offline (`tsx` unavailable).

Therefore this report is a source-level/static architecture audit plus the available local QA script, **not** a substitute for on-device profiling with Perfetto/Android Studio.

---

# Bottom line

The search implementation is being sabotaged by its fallback and state/reactivity design, not by FTS itself. The most visible defect — loading constantly appearing/disappearing — is deterministic from the code. The most dangerous scalability defect is the combination of full-library heavyweight observation + unbounded `%LIKE%` + per-page PDF updates + FTS rewrite-on-any-update + repeated storage directory scans.

Fix those together and Memora should move from “works on a small library but becomes noisy/slow” to an architecture that can realistically handle a large personal archive.
