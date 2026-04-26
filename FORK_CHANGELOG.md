# Fork Changelog

All notable changes specific to this fork are documented here.

For upstream Komga changes, see [CHANGELOG.md](CHANGELOG.md).

---

## [0.1.4.1] - 2026-04-26 — Upstream merge: 1.24.4

### Upstream Fixes (from Komga 1.24.4)

| File | Change |
|------|--------|
| `infrastructure/mediacontainer/epub/Nav.kt` | EPUB TOC: XML-Parser für korrekte TOC-Verarbeitung |
| `infrastructure/kobo/KoboProxy.kt` | Kobo: Request-Body als `ByteArray` proxied |
| `interfaces/api/kosync/KoreaderSyncController.kt` | KOReader: akzeptiert auch `application/json` |
| `interfaces/api/opds/v2/Opds2Controller.kt` | OPDS2: `series/latest` Navigation-Link-Fix |
| `interfaces/api/OpdsGenerator.kt` | OPDS2: Auth-Logo-URL korrekt bei Base-URL |
| `interfaces/api/rest/dto/UserDto.kt` | API: `ageRestriction` wird ausgeblendet statt als `null` gesendet |

---

## [0.1.4.1] - 2026-04-12

### Bug Fixes
- **Page hashes wiped on every re-analyze — duplicate count collapses during scans** — `BookLifecycle.analyzeAndPersist` called `mediaRepository.update(media)` with the fresh `Media` returned by `BookAnalyzer.analyze`, which constructs brand-new `BookPage` objects in `analyzeDivina` without a `fileHash` (defaults to `""`). Any time a book was marked `OUTDATED` (mtime change without `hashFiles` enabled, or actual content change), all previously computed page hashes were dropped, `getBookIdsWithMissingPageHash` re-enqueued the book, and `hashPages` re-read every first/last N page from disk again. During a library scan this also caused the Duplicate Pages / Unknown view to collapse (e.g. 22×50 rows → 2) because the `findAllUnknown` query counts pages with `FILE_HASH != ''` and the re-analyze pass had just blanked them. Fix: `analyzeAndPersist` now reads the previous `Media` inside the transaction and runs `media.pages.restoreHashFrom(previous.pages)` before persisting — the same helper already used by `BookConverter` and `BookPageEditor` matches pages by `fileName + fileSize + mediaType` and copies the old `fileHash` forward. Pages that genuinely changed (different size or renamed) still get re-hashed; untouched pages keep their hash across scans.
- **Metadata plugin: search results incomplete** — `MangaDexMetadataPlugin.search()` did not pass `contentRating[]` to the API, so results were limited to a subset. Now includes all content ratings so every manga is found.
- **Metadata plugin: Apply only wrote to DB, not series.json** — Clicking Apply updated the database but never wrote `series.json`. On the next library scan `MylarSeriesProvider` read the old file and overwrote plugin-applied metadata. New `POST /api/v1/plugins/apply-metadata/{seriesId}` endpoint writes `series.json` (Mylar format) to the series folder.
- **Metadata plugin: cover applied on click instead of on Save, then reverted** — `applyMetadataResult()` immediately downloaded the cover and set it as thumbnail when clicking a search result. The dialog's poster management (`selectedThumbnail`/`deleteQueue`) didn't know about the new thumbnail, so Save Changes re-selected the old one. Cover was also only stored in the DB, not as a file on disk. Fix: cover download is now deferred to Save Changes via a separate `POST /api/v1/plugins/apply-cover/{seriesId}` endpoint. The backend saves `cover.jpg`/`cover.png`/`cover.webp` to the series folder and sets the DB thumbnail.
- **Metadata plugin: authors/artists not split by role in series.json** — `writeSeriesJson()` wrote only the first author's name as singular `"author"` key, ignoring the role field and all other authors. MylarMetadata expects separate `"authors"` (writers) and `"artists"` lists. Now splits by role (`author`/`writer` → `authors`, `artist`/`penciller` → `artists`) with deduplication.
- **Metadata plugin: alternative titles ignored** — `applyMetadata()` now maps `alternativeTitles` to `AlternateTitleDto[]` and includes them in the metadata update.
- **ComicInfoProvider crash on invalid release date** — `ComicInfoProvider.getBookMetadataFromBook` called `LocalDate.of(year, month, day)` without validation, so ComicInfo.xml with impossible dates (e.g. February 29 in a non-leap year) threw `DateTimeException` and aborted the entire metadata refresh for that book — including `tryImportChapterUrl`, which never ran. Now catches `DateTimeException` and falls back to the 1st of the month.
- **Chapter URL import blocked by `importChapterUrls` flag** — `BookMetadataLifecycle.tryImportChapterUrl` was gated behind `library.importChapterUrls`, but since Komga already reads ComicInfo.xml during normal metadata refresh, extracting the `<Web>` chapter URL from the parsed patch costs nothing extra. Removed the flag guard so chapter URLs are always imported from metadata. The flag now only controls the heavy bulk ZIP-comment scan in `ChapterUrlImporter`.
- **Oversized Pages: misleading "Split into N parts" preview in Double Page mode** — `OversizedPages.vue:splitPreviewParts` computed `ceil(width / (height × splitRatio))` in WIDE mode, so a 9.52:1 spread with the default Double Page preset (`splitRatio 1.0`) displayed "Split into 10 parts" in the card. But `ImageSplitter.splitWideImage` always halves a double page into exactly 2 parts (`for (i in 0 until 2)`) regardless of ratio — so the preview was off by up to 5× and suggested the split would shred a spread. Fixed the preview to return `2` if `width > height × splitRatio` and `1` otherwise, matching backend behavior. Also rewrote the `Split ratio` field hint ("Split threshold — halves in 2 when width > N × height") and the `Split All Double Pages?` confirmation body ("… in half (2 parts) whenever the ratio exceeds N:1") so the semantics match what actually happens.

### New Features
- **Oversized Pages: search filter by series/book name** — New text-field in the controls row filters the list server-side. `GET /api/v1/media-management/oversized-pages` now accepts `search=` (case-insensitive `contains` match against book name and series name); the frontend debounces input by 350 ms and resets pagination on each query. Lets you split or ignore everything in a single manga without scrolling through unrelated series.
- **Series view: persistent book sorting per library** — The book sort order chosen inside a series detail view (e.g. "Release Date, desc") is now remembered per library via `vuex-persistedstate` (localStorage). When opening any series in the same library again, the stored sort is restored instead of resetting to "Number, asc". URL sort params still take priority. Resetting filters/sort clears the stored preference. Follows the same pattern as `BrowseBooks`/`BrowseLibraries` sort persistence.

### UI Improvements
- **Oversized Pages: click anywhere on card to select + Shift-click range** — Selection checkbox was a 20px square pinned to the thumbnail's top-left corner (`position: absolute; top:4px; left:4px`), easy to miss and impossible to hit quickly on touch. Now the whole `v-card` is clickable: click anywhere outside the thumbnail/links/action-buttons toggles the selection (`.stop` modifiers added on `v-img`, series/book `router-link`s and preview/ignore/delete `v-btn`s so their own handlers are unaffected). Shift+click extends or collapses the selection from the last-clicked card across the grid (tracks `lastSelectedIndex`, direction of the new click determines select/deselect for the whole range), so picking 20 consecutive pages takes two clicks instead of 20. Selected cards get a 2px `primary` border in addition to the existing elevation bump so the active selection is obvious at a glance. The checkbox is now `readonly` and shares the same click handler, so clicking it behaves identically to clicking the card.
- **Oversized Pages: card-based layout with inline previews** — Replaced the cramped `v-data-table` with a responsive card grid mirroring the Duplicate Pages view. Each entry now renders a 220×320 `v-img` inline (click to zoom to full preview) beside series/book links, page number, dimensions, ratio, split-preview and file size — so oversized pages can be judged at a glance without opening the preview dialog for every row. Selection moved from the table's row checkbox to a per-card checkbox (with a "Select page" / "Deselect page" toggle button in the toolbar); sort is now an explicit `v-btn-toggle` (Ratio / File Size / Series / Book / Page #) with a direction toggle; pagination uses `v-pagination` plus a per-page selector. All existing functionality (Search, Split Selected/All, Ignore, Delete, Show ignored, preview dialog, confirmation dialogs) is preserved.
- **Mobile UI pass across fork-specific views** — Sweeping mobile-readability fixes so the fork's own screens behave on handheld viewports:
  - **Fullscreen dialogs on xs** — All confirmation/preview/install/config/logs/error dialogs in `OversizedPages`, `DownloadDashboard`, `Downloads`, `PluginManager` and `DuplicatePagesKnown` switch to `:fullscreen="$vuetify.breakpoint.xsOnly"`, so narrow viewports get an edge-to-edge sheet instead of a letterboxed card.
  - **Icon-only toolbar buttons on mobile** — Action buttons with long labels (`Split Selected (N)`, `Delete Selected (N)`, `New Download`, `Clear`, `Check Now`, `Sync to MangaDex`, `Save`, `Reload`, etc.) keep their icon + chip count on xs and reveal the text label from sm upward via `d-none d-sm-inline`, so the toolbar no longer wraps into three rows on a phone.
  - **DownloadDashboard stat cards** — Grid switched from `cols="12" sm="3"` (4 full-width cards stacked on mobile) to `cols="6" sm="3"` (2×2 grid on mobile); inner typography scales `text-h5 text-sm-h4` / `text-caption text-sm-subtitle-2` so numbers are readable but compact.
  - **Downloads.vue and DuplicateFiles.vue gain a mobile card layout** — `v-data-table` still renders on md+, but `smAndDown` now falls back to an outlined card per row (title + source URL + status chip + inline progress bar + library + date + icon actions for `Downloads.vue`; grouped-by-fileHash cards with URL, size, deleted chip and delete button for `DuplicateFiles.vue`). Desktop behavior is unchanged.
### Refactoring
- **Dead code removal: `MetadataSearchDialog.vue`** — The dialog was unreachable: `SeriesActionsMenu` opens `EditSeriesDialog` (Tab 6), not `MetadataSearchDialog`, and `BookActionsMenu` emitted `search-metadata` but no parent handled the event. Deleted the 267-line component and all references from `BrowseSeries.vue` (import, component registration, data property, template block, `onMetadataSelected` method) and `BookActionsMenu.vue` ("Search Online Metadata" menu item + `searchMetadata` method).
- **Dead code removal: `mobile-layout.ts` mixin** — Created in this version but never imported by any view (0 references). Deleted. The views use `$vuetify.breakpoint` inline instead. If needed during Vue 3 migration, recreate as a composable.
- **Dead code removal: unused repository methods** — Removed 4 methods from `PluginConfigRepository`/`PluginConfigDao` (`findById`, `findByIdOrNull`, `findAll`, `count`) and 6 methods from `PluginLogRepository`/`PluginLogDao` (`findById`, `findByIdOrNull`, `findAll`, `delete`, `deleteOlderThan`, `count`) — all with 0 callers.
- **Dead code removal: unused frontend service methods** — Removed `getPlugin()` and `deletePlugin()` from `komga-plugins.service.ts` (0 call sites).
- **`PluginController.kt`: replaced FQ annotation** — `@org.springframework.web.bind.annotation.PostMapping` → `@PostMapping` (already imported).

| Modified/New Files | Purpose |
|-------------------|---------|
| `domain/service/BookLifecycle.kt` | `analyzeAndPersist` now calls `restoreHashFrom(previous.pages)` before persisting — preserves existing page hashes across re-analyze |
| `infrastructure/metadata/mangadex/MangaDexMetadataPlugin.kt` | Added all `contentRating[]` params to search query |
| `interfaces/api/rest/PluginController.kt` | `apply-metadata` writes series.json only; new `apply-cover` endpoint downloads cover to disk + sets DB thumbnail |
| `komga-webui/src/components/dialogs/EditSeriesDialog.vue` | Cover download deferred to Save via `pendingCoverUrl`; `applyMetadataResult()` only writes series.json |
| `komga-webui/src/services/komga-plugins.service.ts` | `applyMetadataToSeries()` + new `applyCoverToSeries()` method |
| `infrastructure/metadata/comicrack/ComicInfoProvider.kt` | `try/catch DateTimeException` around `LocalDate.of()` — falls back to 1st of month on invalid day |
| `domain/service/BookMetadataLifecycle.kt` | Removed `library.importChapterUrls` guard from `tryImportChapterUrl` — always imports from metadata patch |
| `komga-webui/src/views/OversizedPages.vue` | Rewritten from `v-data-table` to card grid (`v-row` + `v-slide-x-transition` + 220×320 `v-img`), custom pagination/sort controls; toolbar buttons hide text labels on xs, dialogs fullscreen on xs; `splitPreviewParts` returns `2` in WIDE mode to match `ImageSplitter.splitWideImage` halving behavior; split-ratio hint and confirm-dialog copy reworded accordingly; new debounced search field wired to `search=` query param; whole-card click selects, `.stop` on image/links/action buttons, Shift+click range selection via `lastSelectedIndex`, `primary` border on selected cards, `v-checkbox` now `readonly` and shares the card click handler |
| `interfaces/api/rest/OversizedPagesController.kt` | `search` query param (case-insensitive `contains` over book name + series name) |
| `komga-webui/src/services/komga-books.service.ts` | `getOversizedPages()` accepts `search` argument and forwards it as `search=` param |
| `komga-webui/src/views/DownloadDashboard.vue` | Stat cards `cols=6` on xs; queue toolbar + follow-txt actions hide text on xs; all dialogs fullscreen on xs |
| `komga-webui/src/views/Downloads.vue` | Mobile card layout for the download queue on `smAndDown`; desktop `v-data-table` unchanged; toolbar buttons icon-only on xs; all dialogs fullscreen on xs |
| `komga-webui/src/views/DuplicateFiles.vue` | Mobile card layout grouped by `fileHash` on `smAndDown`; desktop table unchanged |
| `komga-webui/src/views/DuplicatePagesKnown.vue` | Preview + matches dialogs fullscreen on xs (view already card-based) |
| `komga-webui/src/views/PluginManager.vue` | Install/uninstall/config/logs dialogs fullscreen on xs |
| `komga-webui/src/components/dialogs/MetadataSearchDialog.vue` | **Deleted** — unreachable dead code (267 lines) |
| `komga-webui/src/views/BrowseSeries.vue` | Removed all `MetadataSearchDialog` references (import, component, data, template, method) |
| `komga-webui/src/components/menus/BookActionsMenu.vue` | Removed "Search Online Metadata" menu item and `searchMetadata()` method |
| `komga-webui/src/mixins/mobile-layout.ts` | **Deleted** — 0 imports, dead on arrival |
| `komga-webui/src/services/komga-plugins.service.ts` | Removed unused `getPlugin()` and `deletePlugin()` methods |
| `komga-webui/src/plugins/persisted-state.ts` | Added `sortSeriesBooks` state, `getLibrarySortSeriesBooks` getter, `setLibrarySortSeriesBooks` mutation for per-library series-book sort persistence |
| `komga-webui/src/views/BrowseSeries.vue` | `loadSeries` awaits series fetch, applies stored sort from `persistedState.library.sortSeriesBooks[libraryId]` when no URL sort param; `setWatches` persists sort changes; `resetSortAndFilters` clears stored sort |
| `interfaces/api/rest/PluginController.kt` | Replaced FQ `@org.springframework.web.bind.annotation.PostMapping` with short `@PostMapping` |
| `domain/persistence/PluginConfigRepository.kt` | Removed `findById`, `findByIdOrNull`, `findAll`, `count` (0 callers) |
| `infrastructure/jooq/main/PluginConfigDao.kt` | Removed matching DAO implementations |
| `domain/persistence/PluginLogRepository.kt` | Removed `findById`, `findByIdOrNull`, `findAll`, `delete`, `deleteOlderThan`, `count` (0 callers) |
| `infrastructure/jooq/main/PluginLogDao.kt` | Removed matching DAO implementations |

---

## [0.1.4] - 2026-04-12

### Bug Fixes
- **Oversized Pages: "Split Selected" still split every page + created tiny shards** — Two compounding bugs when splitting a single selected webtoon page: (1) `OversizedPages.vue:splitSelected` built the request with `paramsSerializer: params => qs.stringify(params, {indices: false})` but `qs` was never imported in the file. At runtime the serializer threw `ReferenceError`, the `pageNumbers` query list never reached the backend, and the backend fell back to re-scanning the whole book by ratio — so selecting 1 page triggered 32 splits and 64 new pages. Replaced the `qs` call with `URLSearchParams` which builds `?maxRatio=…&mode=…&pageNumbers=1&pageNumbers=2&…` natively — no dependency, no runtime surprise. (2) After the split completed, the new parts were ~800×80 px — far smaller than the Webtoon preset's `splitRatio 1.5` target should produce. Root cause: `PageSplitter.splitTallPages` reads `media.pages[x].dimension.width` from the DB when computing `effectiveMaxHeight = width × maxRatio`. When stored dimensions are stale or truncated (e.g. a thin strip at `53×200`), the target height collapses to `53 × 1.5 ≈ 80 px` while `ImageSplitter` loads the real image and slices its actual `800 px` width into 50+ shards of 80 px. Added `MIN_TARGET_DIMENSION = 300` sanity floor: if the computed target height/width falls below 300 px the page is skipped and a `WARN` naming the offending dimension/ratio is logged, so stale DB entries can no longer produce degenerate splits.
- **Library scan takes hours on non-manga libraries (Issue #25)** — `ChapterUrlImporter` opened every CBZ file twice per scan: once for the ZIP comment, then via `ZipInputStream` for `ComicInfo.xml`. Three fixes: (1) new `importChapterUrls` library flag (default `true`) gates the importer — disable for non-download libraries; (2) `ChapterUrlImporter` now only reads ZIP comments (central-directory lookup, no file extraction); (3) chapter URLs from `ComicInfo.xml <Web>` are now extracted by `BookMetadataLifecycle` during the normal metadata refresh — piggybacking on the `ComicInfoProvider` read that already happens, eliminating the redundant ZIP open entirely.
- **Duplicate chapters from trailing whitespace in MangaDex scanlation group name** — MangaDex returned the same scanlation group (e.g. `Bathhouse Scans`) with and without a trailing space for different chapters, so `ChapterMatcher.findSameGroupDuplicates` grouped by `Pair(chapterNumber, scanlationGroup)` saw two distinct groups and both versions were downloaded side-by-side (`v26 c248 [Bathhouse Scans].cbz` + `v26 c248 [Bathhouse Scans ].cbz`). `MangaDexApiClient.fetchChapterMetadata` / `fetchAllChaptersFromMangaDex` now `.trim()` the group name and treat empty strings as `null`, so the dedup logic and filename generation collapse the two variants into one.
- **ComicInfoProvider crash on invalid release date** — `ComicInfoProvider.getBookMetadataFromBook` called `LocalDate.of(year, month, day)` without validation, so ComicInfo.xml with impossible dates (e.g. February 29 in a non-leap year) threw `DateTimeException` and aborted the entire metadata refresh for that book — including `tryImportChapterUrl`, which never ran. Now catches `DateTimeException` and falls back to the 1st of the month.
- **Oversized Pages: "Split Selected" ignored the selection** — Clicking "Split Selected" only sent the unique `bookId`s to `POST /api/v1/media-management/oversized-pages/split/{bookId}` with no page list. The backend then re-scanned the entire book by ratio and split *every* matching page, not just the selected one — so selecting a single oversized page could split 10+ other pages in the same book as a side effect. Frontend now groups selected rows by `bookId` and passes `pageNumbers[]`; backend `PageSplitter.splitTallPages(..., pageNumbers: Set<Int>?)` respects the set verbatim (ratio filters are bypassed for explicit selections since the UI already vetted them, sanity filters still apply).

### New Features
- **Oversized Pages: Double Page preset** — New `Double Page` preset detects wide images that contain two facing pages (e.g. manga spreads) and splits them horizontally into single pages. Detection uses `width ÷ height` ratio (default 1.3:1), splitting creates parts with max width = `splitRatio × height` (default 1.0, i.e. 2 parts for a 2:1 spread). Tall-mode presets (Webtoon/Moderate/Aggressive) and wide-mode (Double Page) are fully isolated: selecting Webtoon never lists double pages and vice versa. Backend accepts `mode=tall|wide` on `GET /api/v1/media-management/oversized-pages` and both split endpoints.
- **Oversized Pages: image preview** — Each row now shows a thumbnail column and a preview action. Clicking the thumbnail or the preview icon opens a dialog with the full image, dimensions and ratio, so you can verify a page before splitting or ignoring it. Pattern mirrors the Duplicate Pages preview flow.
- **Oversized Pages: ignore list** — Pages you don't want to split can be marked as ignored (per-row icon, batch "Ignore Selected" button, or from the preview dialog). Ignored entries are persisted in a new `IGNORED_OVERSIZED_PAGE` table keyed by `(bookId, pageNumber, mode)` so tall and wide lists keep independent ignore states. A "Show ignored" toggle brings them back into view. After a successful split, ignored entries for that book+mode are cleared automatically since page numbers shift.
- **Oversized Pages: sanity filter for divider strips** — Webtoon divider/banner images with pathological dimensions (e.g. `720×1`, `1200×15`, `1200×25`) were matched by Double Page detection because their `width÷height` ratio is astronomical. Added two hard filters shared by the listing endpoint, `split-all`, and `PageSplitter`: images with either side below `MIN_VALID_DIMENSION = 50 px` are rejected outright, and in WIDE mode the ratio is capped at `MAX_WIDE_RATIO = 10.0` (real double pages are ~2:1, so anything beyond 10:1 is a strip, not a spread).
- **Post-download scan honors library analysis settings** — `LibraryContentLifecycle.scanSeriesFolder` (triggered after gallery-dl downloads) used to emit `analyzeBook` only, so newly downloaded books bypassed `hashFiles`, `hashPages`, `hashKoreader`, `repairExtensions`, `FindBooksToConvert` and `FindDuplicatePagesToDelete` — exactly the same per-library toggles that a normal `ScanLibrary` task honors. Now the targeted scan emits the same post-scan task set as `ScanLibrary` (all emitters already filter internally by library flag and book state, so existing books are not re-processed).
- **Oversized Pages: delete pages from book** — New delete action for unwanted frames (webtoon divider strips, blank pages, garbage frames) that are not duplicates and therefore cannot be removed via Duplicate Pages. Delete icon per row, "Delete Selected" batch button, and a "Delete this page" action in the preview dialog — all routed through a confirmation dialog since the operation is destructive. Backend adds `BookPageEditor.removePagesByNumber(book, pageNumbers)` (mirrors `removeHashedPages` but keys by 1-indexed page number instead of precomputed hash) plus `POST /api/v1/media-management/oversized-pages/delete-page` and `/delete-pages-batch` endpoints. The ignore list for the affected book+mode is cleared after a successful delete since page numbers shift, and if page 1 is removed the thumbnail is regenerated.
- **Split logging: quieter success path, richer errors** — Per-page `Splitting image 855x2641 into 3 parts …` in `ImageSplitter` (both tall and wide) and the redundant `Replaced original file with split version` in `PageSplitter` were at INFO level, producing one line per split page (e.g. 11 lines for an 11-page split). Demoted to DEBUG so only the per-book summary (`Found N pages to split`, `Successfully split N pages`) remains at INFO. When a single page fails to split, a new per-page `WARN` log names the offending page and its dimensions before the outer error handler triggers the rollback — so failures are easier to diagnose.
- **Download logging: demoted to DEBUG** — `GalleryDlWrapper` previously logged every chapter download step at INFO (`Downloading chapter 36 (49/58): …`, `Starting bulk download`, `Known chapter URLs`, `Auto-blacklisted …`, `Download completed`, `Repaired: …`, etc.), duplicating the progress information already shown in the WebUI download panel. All ~20 INFO-level calls demoted to DEBUG; `logger.warn`/`logger.error` left untouched so failures (timeouts, exit codes, missing CBZs, API errors) still surface in the main log.

### UI Improvements
- **Oversized Pages: more rows-per-page options** — Added `250` and `500` to `itemsPerPage` selector alongside existing `20/50/100`.
- **Oversized Pages: fix Split Selected count** — `item-key` was `bookId`, so Vuetify deduplicated rows when multiple pages from the same book appeared on one page — selecting all 100 rows ended up with fewer entries in `selectedPages`. Switched to composite `rowKey = bookId_pageNumber`; "Split Selected (N)" now reflects the real row count.

### Documentation
- **README: Docker `network_mode: bridge` as default** — `docker run` and `docker-compose.yml` now include `network_mode: bridge` / `--network bridge` by default; removed the separate VLAN footnote.
- **README: Removed pre-0.1.0 migration instructions** — The SQL cleanup for fork versions ≤ 0.0.9 is no longer relevant; section simplified to a two-line summary.
- **README: Chapter URL import note** — Added note under *Chapter URL Tracking* explaining that the toggle is enabled by default and should be disabled for libraries that don't use the download system.

| Modified/New Files | Purpose |
|-------------------|---------|
| `infrastructure/image/ImageSplitter.kt` | New `splitWideImage()` — horizontal slicing by `targetWidth` |
| `domain/service/PageSplitter.kt` | `SplitMode` enum (`TALL`/`WIDE`), `mode` param dispatches to `splitWideImage`/`splitTallImage`; `PageToSplit.effectiveMax` replaces `effectiveMaxHeight`; `MIN_VALID_DIMENSION`/`MAX_WIDE_RATIO` sanity filters; per-page WARN on split failure; quieter INFO on success path |
| `infrastructure/image/ImageSplitter.kt` | `Splitting image …` logs demoted from INFO to DEBUG (both tall and wide) |
| `interfaces/api/rest/OversizedPagesController.kt` | `mode` query/body param on list, `split/{bookId}` and `split-all`; `includeIgnored` filter; ignore/unignore/ignore-batch endpoints; delete-page/delete-pages-batch endpoints; auto-cleanup after successful split/delete; shares `PageSplitter` sanity filters |
| `domain/service/BookPageEditor.kt` | New `removePagesByNumber(book, pageNumbers)` method that removes pages by 1-indexed position (no fileHash required), logs `BookConverted` historical event |
| `interfaces/api/rest/dto/OversizedPageDto.kt` | `SplitRequestDto.mode` field; `IgnoreOversizedPageRequestDto`, `IgnoreOversizedPagesRequestDto`, `IgnoredPageKeyDto`; `DeleteOversizedPageRequestDto`, `DeleteOversizedPagesRequestDto`, `DeletePagesResultDto` |
| `domain/model/IgnoredOversizedPage.kt` | New domain model for ignored pages |
| `domain/persistence/IgnoredOversizedPageRepository.kt` | New repository interface |
| `infrastructure/jooq/main/IgnoredOversizedPageDao.kt` | jOOQ DAO implementing `IgnoredOversizedPageRepository` |
| `flyway/resources/db/migration/fork/sqlite/V20260401000000__ignored_oversized_pages.sql` | New `IGNORED_OVERSIZED_PAGE` table with composite PK on `(BOOK_ID, PAGE_NUMBER, MODE)` |
| `komga-webui/src/views/OversizedPages.vue` | `Double Page` preset + mode-aware labels/hints/dialog; `rowKey` composite key; rows-per-page `[20,50,100,250,500]`; thumbnail column; preview dialog; per-row and batch ignore buttons; "Show ignored" toggle; per-row and batch delete buttons with confirmation dialog; "Delete this page" action in preview dialog |
| `komga-webui/src/services/komga-books.service.ts` | `getOversizedPages()` takes `mode` + `includeIgnored`; new `ignoreOversizedPage()`, `ignoreOversizedPagesBatch()`, `unignoreOversizedPage()`, `deleteOversizedPage()`, `deleteOversizedPagesBatch()` |
| `domain/service/LibraryContentLifecycle.kt` | `scanSeriesFolder` now mirrors `ScanLibrary` post-scan tasks (`repairExtensions`, `findBooksToConvert`, `findBooksWithMissingPageHash`, `findDuplicatePagesToDelete`, `hashBooksWithoutHash`, `hashBooksWithoutHashKoreader`) |
| `infrastructure/download/GalleryDlWrapper.kt` | All INFO download-progress logs demoted to DEBUG (20 call sites); warn/error untouched |
| `infrastructure/download/MangaDexApiClient.kt` | `.trim()` scanlation group name from MangaDex API (both `fetchChapterMetadata` and `fetchAllChaptersFromMangaDex`); empty-after-trim collapses to `null` so dedup no longer splits on trailing whitespace |
| `domain/model/Library.kt` | New `importChapterUrls: Boolean = true` field |
| `infrastructure/jooq/main/LibraryDao.kt` | Insert/update/toDomain threads `importChapterUrls` through the `IMPORT_CHAPTER_URLS` column |
| `flyway/resources/db/migration/fork/sqlite/V20260412000000__library_import_chapter_urls.sql` | New `ALTER TABLE library ADD COLUMN IMPORT_CHAPTER_URLS boolean NOT NULL DEFAULT 1` |
| `interfaces/api/rest/dto/LibraryDto.kt` / `LibraryCreationDto.kt` / `LibraryUpdateDto.kt` | Expose `importChapterUrls` on API |
| `interfaces/api/rest/LibraryController.kt` | Create/patch pass `importChapterUrls` through |
| `domain/service/LibraryContentLifecycle.kt` | `scanRootFolder` and `scanSeriesFolder` call `chapterUrlImporter` only when `library.importChapterUrls` is `true` |
| `domain/service/ChapterUrlImporter.kt` | Removed ComicInfo.xml reading entirely; now only reads ZIP comments (central-directory lookup). `parseComicInfoXml`, regex constants, and `unescapeXml` removed |
| `domain/service/BookMetadataLifecycle.kt` | New `tryImportChapterUrl`: extracts chapter URL from `ComicInfoProvider` patch links during normal metadata refresh — no extra ZIP open |
| `komga-webui/src/components/dialogs/LibraryEditDialog.vue` | New metadata toggle row for chapter URL import with warning tooltip |
| `komga-webui/src/types/komga-libraries.ts` | `importChapterUrls` on `LibraryDto`/`LibraryCreationDto`/`LibraryUpdateDto` |
| `komga-webui/src/locales/en.json` / `de.json` | New `field_import_chapter_urls`, `label_import_chapter_urls`, `tooltip_import_chapter_urls` keys |
| `gradle.properties` | Fork version bump to `0.1.3.4` |

---

## [0.1.3.3] - 2026-04-09

### Changed
- **Oversized Pages: ratio-based detection and splitting** — Replaced fixed pixel thresholds (`minWidth`/`minHeight`/`maxHeight`) with aspect ratio (`height ÷ width`). Detection uses `minRatio` (find pages taller than N:1), splitting uses `maxRatio` (split into parts of at most N:1). Works consistently at any resolution. UI now offers presets (Webtoon 3:1, Moderate 2:1, Aggressive 1.5:1, Custom) instead of manual pixel inputs. Table shows ratio column and split preview. Removed "Total Pixels" and "Media Type" columns.

| Modified/New Files | Purpose |
|-------------------|---------|
| `domain/service/PageSplitter.kt` | `maxRatio` parameter, per-page `effectiveMaxHeight` from ratio × width |
| `interfaces/api/rest/OversizedPagesController.kt` | `minRatio`/`maxRatio` query params, ratio-based filtering and split-all |
| `interfaces/api/rest/dto/OversizedPageDto.kt` | Added `ratio` field, `SplitRequestDto` now has `maxRatio` |
| `komga-webui/src/views/OversizedPages.vue` | Preset selector, ratio inputs, ratio column, split preview column |
| `komga-webui/src/services/komga-books.service.ts` | `getOversizedPages()` takes `minRatio` instead of `minWidth`/`minHeight` |
| `komga-webui/src/types/komga-books.ts` | `OversizedPageDto.ratio` field |

### Docs
- **README: VLAN Docker note** — Added tip for Docker hosts using VLANs: `network_mode: bridge` may be needed for internet access.

### Housekeeping
- **Deleted `apple.cer`** — Apple certificate file that shouldn't be in the repository.

---

## [0.1.3.2] - 2026-04-04

### Bug Fixes
- **Duplicate pages known list not repaginating** — `pageHashRemoved()` called `loadData()` with no arguments (TypeError), so the list never reloaded after removing an item. Items from subsequent pages did not fill in. Fixed to pass correct `page`, `sort`, and `filter` args.
- **Category change not reflected in known list** — Changing an item's action (ignore/auto/manual) only updated the chip in-place. Items whose new action no longer matched the active filter stayed visible. Now triggers a full page reload after any action change.
- **Unknown list not repaginating after classification** — Classifying an item only hid it locally and reloaded only when the entire page was consumed. Now reloads immediately after each classification so items from the next page fill in. `actionRemaining` batches all requests via `Promise.all` and reloads once.

### New Features
- **"Remove all from list" button on Known page** — One-click button with confirmation dialog to remove all known page hash entries. Backed by new `DELETE /api/v1/page-hashes` endpoint.

| Modified/New Files | Purpose |
|-------------------|---------|
| `domain/persistence/PageHashRepository.kt` | Added `deleteAllKnown()` |
| `infrastructure/jooq/main/PageHashDao.kt` | Implemented `deleteAllKnown()` |
| `interfaces/api/rest/PageHashController.kt` | `DELETE /api/v1/page-hashes` endpoint |
| `komga-webui/src/services/komga-pagehashes.service.ts` | `removeAllKnownHashes()` |
| `komga-webui/src/views/DuplicatePagesKnown.vue` | Repagination fix, category-change fix, Remove All button |
| `komga-webui/src/views/DuplicatePagesUnknown.vue` | Repagination fix, removed `hiddenElements`, `actionRemaining` uses `Promise.all` |
| `komga-webui/src/locales/en.json` + `de.json` + 31 others | `action_remove_all`, `confirm_remove_all` keys |
| `komga-webui/src/views/LoginView.vue` | Fix hardcoded German "Als Gast durchsuchen" → i18n key |
| `komga-webui/src/locales/en.json` + `de.json` + 31 others | `login.browse_as_guest` key |

### Bug Fixes (ChapterChecker)
- **"No book" for auto-queued manga** — `ChapterChecker.checkAndQueueNewChapters()` always passed `libraryId = null` when queueing downloads. This caused downloads to go to `~/Downloads/komga/` instead of the library path, and suppressed the post-download scan (gated on `library != null`). Fixed by adding `libraryId` to `ChapterCheckResult` — resolved from `series.libraryId` when the series exists, or from the folder's parent library path otherwise.

| Modified Files | Purpose |
|---------------|---------|
| `domain/service/ChapterChecker.kt` | `libraryId` field added to `ChapterCheckResult`, derived in `checkSingleUrl`, used in `checkAndQueueNewChapters()` |

- **"No book" persists on library series card (display bug)** — `sortBooks()` used `books.size` for `bookCount` which counted soft-deleted books, causing incorrect counts. Also, `sortBooks()` was only called when new books were found in `scanSeriesFolder()`, so a `bookCount = 0` that ended up in the DB could never self-correct on subsequent scans. Fixed: count only non-deleted books; always call `sortBooks()` after every targeted folder scan.

| Modified Files | Purpose |
|---------------|---------|
| `domain/service/SeriesLifecycle.kt` | `sortBooks()` counts only non-deleted books for `bookCount` |
| `domain/service/LibraryContentLifecycle.kt` | `sortBooks()` called unconditionally in `scanSeriesFolder()` |
| `domain/service/ChapterUrlImporter.kt` | `syncMangaDexUuid()` re-reads series from DB before update to avoid overwriting `bookCount=0` |
| `domain/service/LibraryContentLifecycle.kt` | Full library scan now repairs existing series with `bookCount=0` that were missed by earlier bug |

### Changed
- **Suppress Windows connection-reset log spam** — Added `logback-spring.xml` to silence `[dispatcherServlet]` ERROR entries that fire when SSE/streaming clients disconnect abruptly (Windows: "Eine bestehende Verbindung wurde softwaregesteuert abgebrochen"). Not actionable; Spring MVC still logs real errors at the controller/service layer.
- **Removed unused Conveyor packaging files** — Deleted `conveyor.ci.conf`, `conveyor.conf`, `conveyor.detect.conf`, `conveyor.msstore.ci.conf`, `conveyor.msstore.conf`. These are upstream distribution configs for native installers not used in this fork.

| Modified/New Files | Purpose |
|-------------------|---------|
| `komga/src/main/resources/logback-spring.xml` | Suppress dispatcher servlet connection-reset IOExceptions |
| `conveyor*.conf` (5 files) | Deleted |

---

## [0.1.3.1] - 2026-03-31 Hotfix

### Bug Fixes
- **MangaDex rate limiter caused 51s waits** — The per-minute limit (40 req/min) was far too restrictive for MangaDex's actual 5 req/sec limit. After ~40 requests (~8 seconds), the rate limiter would calculate a ~51 second wait. Removed the per-minute limit entirely; only the 5 req/sec limit remains.
- **429 retry handler added phantom timestamps** — When a 429 response was received, the retry logic called `rateLimiter.waitIfNeeded()` again, which recorded a phantom request timestamp and could trigger the per-minute limit. Removed the redundant `waitIfNeeded()` call from all three 429 handlers.
- **Two independent MangaDex rate limiters** — `MangaDexClient` and `MangaDexApiClient` each had their own rate limiting. Requests from both counted against MangaDex's IP-based limit but neither knew about the other. Merged into a single `MangaDexApiClient` with one shared `MangaDexRateLimiter`.

### Improved
- **Logs page defaults to live view** — The server logs page now auto-starts the live stream on load instead of requiring a manual click on the "Live" button.

| Modified/New Files | Purpose |
|-------------------|---------|
| `infrastructure/download/MangaDexRateLimiter.kt` | Removed per-minute limit, simplified to 5 req/sec only |
| `infrastructure/download/MangaDexApiClient.kt` | Removed phantom `waitIfNeeded()` from 429 handlers, added `searchManga()` |
| `interfaces/api/rest/ChapterUrlController.kt` | Switched from `MangaDexClient` to `MangaDexApiClient` |
| `interfaces/api/rest/HealthCheckController.kt` | Switched from `MangaDexClient` to `MangaDexApiClient` |
| `infrastructure/mangadex/MangaDexClient.kt` | **Deleted** — consolidated into `MangaDexApiClient` |
| `komga-webui/src/views/LogsView.vue` | Auto-start live stream on mount |

---

## [0.1.3] - 2026-03-27

### Bug Fixes
- **Documentation fixes** — Fixed incorrect API reference and README. Fixed Docker gallery-dl update command (`-u 0`, `pip3`, `--break-system-packages`). Removed inline API snippets from README (api-reference.md is the single source).

### Changed
- **Merged `/follow-config` into `/scheduler`** — Removed separate `/api/v1/downloads/follow-config` endpoint. All scheduler settings are now managed via `/api/v1/downloads/scheduler`. Removed `urls` field from scheduler (URLs are managed per-library via `follow-txt`). Check-now moved to `POST /scheduler/check-now`.
- **UNIQUE constraint on mangaDexUuid** — `DownloadExecutor` crashed with `UNIQUE constraint failed: SERIES.MANGADEX_UUID` when two series folders pointed to the same MangaDex manga. Now checks `seriesRepository.findByMangaDexUuid()` before updating, and skips with a warning if the UUID is already assigned to another series.
- **External redirect chapters cause infinite re-queue** — MangaDex chapters that are external redirect links (`pages=0`) could never be downloaded by gallery-dl but were only blacklisted after 3 failed attempts. Now immediately auto-blacklisted on first encounter without a download attempt. Normal chapters keep the 3-failure threshold to avoid false blacklisting during MangaDex downtime.

| Modified/New Files | Purpose |
|-------------------|---------|
| `domain/service/DownloadExecutor.kt` | Pre-check before mangaDexUuid update |
| `infrastructure/download/GalleryDlWrapper.kt` | Immediate blacklist for `pages=0` chapters |

### New Features
- **Scan deleted chapters** — New "Scan deleted chapters" option in the library 3-dot menu. Compares tracked chapter URLs in the database against CBZ files on the filesystem. Removes stale entries for chapters whose files no longer exist, so re-downloads correctly detect them as missing. Runs as a background task.
- **Gallery-dl archive tracking for non-MangaDex** — Non-MangaDex downloads now use gallery-dl's built-in `--download-archive` option with a `.gallery-dl-archive.txt` file in the manga folder. This prevents duplicate folder creation and re-downloads when downloading from the same source a second time.

| Modified/New Files | Purpose |
|-------------------|---------|
| `application/tasks/Task.kt` | New `ScanDeletedChapters` task type |
| `application/tasks/TaskEmitter.kt` | `scanDeletedChapters()` submission method |
| `application/tasks/TaskHandler.kt` | Handler for scan deleted chapters task |
| `domain/service/ChapterChecker.kt` | `scanDeletedChaptersForLibrary()` logic |
| `interfaces/api/rest/LibraryController.kt` | `POST /api/v1/libraries/{id}/scan-deleted-chapters` endpoint |
| `infrastructure/download/GalleryDlWrapper.kt` | `--download-archive` for non-MangaDex downloads |
| `komga-webui/.../LibraryActionsMenu.vue` | Menu item |
| `komga-webui/.../komga-libraries.service.ts` | API client method |
| `komga-webui/.../en.json` | Translation |

---

## [0.1.2] - 2026-03-22

### New Features
- **Download resume after crash/restart** — Downloads stuck in DOWNLOADING status (e.g. after server crash) are automatically recovered to PENDING on startup via `ContextRefreshedEvent`. Combined with existing chapter URL tracking, downloads resume from where they left off instead of starting over. Resume progress is logged with "Resuming: X/Y chapters already downloaded, Z remaining".
- **Kitsu metadata provider** — New `OnlineMetadataProvider` plugin fetching series-level metadata from Kitsu API (kitsu.app). Provides titles, synopsis, genres, authors, age rating, cover images, and alternative titles in multiple languages. No API key required.

### Bug Fixes
- **Kitsu metadata search fails** — `PluginController.getMetadataProvider()` was missing the `"kitsu-metadata"` routing, so searching via Kitsu always returned "Failed to search metadata". Added KitsuMetadataPlugin constructor parameter and when-branch.
- **Download progress shows "45/45" incrementing together** — Non-MangaDex bulk downloads (mangahere, rawkuma etc.) showed current and total incrementing together (45/45 → 49/49) because `totalChapters` was set to the current count when unknown. Now defaults to 0, which maps to `null` in the WebSocket DTO so the frontend shows only the current count.
- **Cover image not loaded after download** — `scanSeriesFolder()` (targeted scan after download) did not trigger `refreshSeriesLocalArtwork`, so the `cover.jpg` downloaded by gallery-dl was only picked up on the next full library scan.
- **MangaDex feed missing chapters for certain content ratings** — Feed API calls did not include `contentRating[]` parameters, so the API applied its default filter which excludes some rating categories. Added all four content rating levels to feed requests in GalleryDlWrapper and MangaDexSubscriptionSyncer.
- **MangaDex subscription feed always fails with 400** — `publishAtSince` was formatted with `ISO_OFFSET_DATE_TIME` (`2026-03-21T08:43:24.6358255Z`) but MangaDex requires exact `YYYY-MM-DDTHH:mm:ss` without fractional seconds or timezone suffix. Also sanitizes old DB values on read.
- **Subscription dedup always empty** — `seriesRepository.findAll()` returned series with `mangaDexUuid = null` (toDomain doesn't read MANGADEX_UUID column), so all dedup maps were empty and every chapter was queued as "unknown". Now uses `findByMangaDexUuid()` per manga on-demand.
- **CustomList dead code removed** — `initializeList()` created a MangaDex CustomList on every startup that was never used for feed checking (feed uses `/user/follows/manga/feed`). Caused duplicate "Komga Subscriptions" lists.

- **ChapterChecker executor leak on exception** — Thread pool was only shut down in happy path. If `futures.map { it.join() }` threw, the 5-thread pool leaked. Wrapped in try-finally.
- **gallery-dl process leak in `isInstalled()`** — `process.waitFor(5s)` timeout left process running. Now calls `destroyForcibly()` on timeout.
- **Reader threads not interrupted after join timeout** — stdout/stderr reader threads continued running after `join(5000)` timeout. Now interrupted if still alive.

### New Features
- **Repair ComicInfo endpoint** — New `POST /api/v1/downloads/repair-comicinfo/{libraryId}` endpoint to retroactively inject missing ComicInfo.xml and ZIP comments into existing MangaDex CBZ files. Scans library directory for MangaDex folders (UUID-named or containing series.json with MangaDex ID), fetches chapter metadata from MangaDex API, and repairs each CBZ. Skips files that already have a ZIP comment.
- **Dynamic log level toggle** — New Debug switch in the web log viewer (`Settings → Logs`) to toggle between INFO and DEBUG log level at runtime via `GET/POST /api/v1/logs/level`. No server restart needed, resets to INFO on restart.
- **Live log streaming via SSE** — New `GET /api/v1/logs/stream` SSE endpoint tails the log file in real-time. Web log viewer has Live/Pause buttons replacing the old 5-second polling. Pause buffers incoming lines and flushes on unpause.
- **Target library selection for MangaDex Subscription** — New `Target Library` config field lets users choose which library receives downloaded manga by name. Falls back to the first library if empty or not found.

### Improved
- **Target library as dropdown selection** — MangaDex Subscription's `Target Library` config field is now a dropdown populated with existing library names instead of a free-text input. Uses `dynamicEnum: "libraries"` schema marker to fetch libraries at dialog open time. Clearable (falls back to first library).
- **Plugin config dialog shows only schema-defined fields** — Frontend config dialog now iterates schema properties instead of all DB values. Orphan config entries (e.g. removed `language` field from subscription plugin) no longer show as untyped text fields.
- **36 languages in plugin dropdown** — MangaDex Subscription and gallery-dl Downloader language selection expanded from 10 to 36 languages (added zh-hk, es-la, pt-br, pl, tr, nl, id, ms, th, vi, ar, uk, hu, ro, cs, bg, el, da, fi, sv, no, lt, ca, hr, tl, hi).

### Improved
- **Error logging across download system** — Replaced ~18 silent catch blocks and DEBUG-level logs with proper WARN-level logging including stack traces. Affected files: `DownloadExecutor`, `GalleryDlWrapper`, `ChapterChecker`, `ComicInfoGenerator`, `ChapterMatcher`, `MangaDexApiClient`, `GalleryDlProcess`, `MangaDexSubscriptionSyncer`, `ChapterUrlImporter`, `DownloadController`.

### Refactored
- **GalleryDlWrapper split into 4 focused components** — Extracted `MangaDexApiClient` (API calls, metadata fetching, caching, rate limiting via `MangaDexRateLimiter`), `ComicInfoGenerator` (XML generation, ZIP comment, CBZ metadata injection), `GalleryDlProcess` (subprocess management, config files, environment setup), and `ChapterMatcher` (filename regex, chapter URL extraction, duplicate detection). `GalleryDlWrapper` remains as the facade — all 6 consumer classes still reference only `GalleryDlWrapper`. `ChapterDownloadInfo` moved from nested class to top-level. Dead code `downloadCover()` (gallery-dl based) removed.

### Performance
- **Plugin config caching (60s TTL)** — `GalleryDlWrapper` now caches plugin config in memory instead of querying the database on every method call. Reduces DB queries during downloads.
- **Atomic series.json writes** — `series.json` is now written to a temp file first, then moved atomically (`ATOMIC_MOVE` with fallback). Prevents corruption if process crashes mid-write.
- **Background cache eviction** — Chapter cache, manga info cache, and plugin config cache are now evicted by a scheduled job every 10 minutes instead of only on access.
- **Pre-compiled regex constants in GalleryDlWrapper** — 5 regex patterns (`extractChapterId`, `extractChapterNumberFromFilename`, `parseGalleryDlProgress`, `extractChapterNumFromFilename`, scanlation group) moved from per-call compilation to companion object constants.
- **Single directory traversal after download** — Replaced 2× `walkTopDown()` + 1× `listFiles()` with a single `walkTopDown()` pass for CBZ file collection and empty directory cleanup.
- **Cached library list in ChapterChecker** — `libraryRepository.findAll()` called once in `checkUrls()` and passed through to `checkSingleUrl()`, `findSeriesForManga()`, and `buildFolderIndex()`. Eliminates ~300 redundant DB queries per chapter check run.
- **Blacklist filtered by series** — `blacklistedChapterRepository.findAll()` replaced with `findUrlsBySeriesId()` when series ID is known, avoiding loading the entire blacklist table on every download.
- **O(1) chapter lookup in ComicInfo injection** — `updateExistingCbzChapterUrls()` pre-indexes chapters by padded/plain number into a Map. Previously O(n×m) linear search per CBZ file (1M comparisons for 1000 chapters × 1000 CBZs), now O(n+m).
- **Hash set computed once in series restore** — `newBooksWithHash.map { it.fileHash }.toSet()` was recomputed inside `find` loop per deleted candidate. Now computed once before the loop.

### Modified Files
| File | Changes |
|------|---------|
| `MangaDexSubscriptionSyncer.kt` | `last_check_time` sanitized with `.take(19)`, `initializeList()` removed, batch dedup uses `findByMangaDexUuid()` on-demand |
| `PluginInitializer.kt` | 36 languages in both plugin configSchemas, CustomList removed from description, `dynamicEnum: "libraries"` for target_library |
| `GalleryDlWrapper.kt` | Refactored to facade pattern — delegates to 4 new components. `ChapterDownloadInfo` moved to top-level. Plugin config cache + orchestration retained. |
| `MangaDexApiClient.kt` | **New** — All MangaDex HTTP API calls, chapter/manga caching, uses `MangaDexRateLimiter` |
| `ComicInfoGenerator.kt` | **New** — ComicInfo.xml generation, ZIP comment, CBZ injection with retry |
| `GalleryDlProcess.kt` | **New** — gallery-dl subprocess management, config files, environment setup |
| `ChapterMatcher.kt` | **New** — Filename regex patterns, chapter matching, URL extraction from CBZ, duplicate detection |
| `PluginManager.vue` | Dynamic library dropdown via `resolveDynamicEnums()`, `clearable` v-select for dynamicEnum fields |
| `ChapterChecker.kt` | Executor try-finally, cached library list passed through call chain |
| `PluginController.kt` | Added Kitsu metadata routing (`"kitsu-metadata"` when-branch) |
| `DownloadController.kt` | New `repair-comicinfo/{libraryId}` endpoint, error logging |
| `LogController.kt` | Dynamic log level GET/POST endpoints, SSE log stream endpoint |
| `LogsView.vue` | Debug toggle, Live/Pause SSE streaming (replaced polling) |
| `DownloadExecutor.kt` | Error logging in findExistingMangaFolder, migrateLibrary, extractVolume, getFolderNaming |
| `ChapterUrlImporter.kt` | Error logging in ZIP comment, series.json, metadata link extraction |
| `LibraryContentLifecycle.kt` | Hash set computed once in series restore |
| `README.md` | CustomList references removed, auto-blacklist docs updated |

---

## [0.1.1] - 2026-03-16

### New Features
- **Series survives folder rename** — When a manga folder is renamed, Komga now detects the same series via `mangaDexUuid` (from `series.json` or UUID folder name) and restores it instead of creating a new one. Preserves browser URL, reading progress, collections, and metadata. Compatible with upstream Komga (no DB schema changes).
- **Remove known duplicate page hashes** — New `DELETE /api/v1/page-hashes/{pageHash}` endpoint and WebUI button to permanently remove entries from the known duplicate pages list. Previously only IGNORE was available, causing the list to grow indefinitely.
- **ZIP file comments in CBZ** — CBZ files now include metadata as ZIP file comments: `Title`, `Title UUID`, `Chapter UUID`, `Chapter`, `Volume`. Compatible with all manga downloaders (none use the ZIP comment field). Only Calibre's ComicBookInfo plugin uses this field, but with a different JSON format that doesn't conflict.
- **Auto-scan after download** — New chapters are automatically scanned after all downloads complete. Collects downloaded series folders during the download queue and scans them all via targeted `scanSeriesFolder()` once the queue is empty — no scan per individual download, no full library scan. `scanSeriesFolder` creates new series if needed (with `tryRestoreByMangaDexUuid` fallback), imports chapter URLs only for the affected series, and syncs MangaDex UUID per series. Full library scan remains unchanged for manual use.
- **Configurable folder naming** — New `folder_naming` plugin config for gallery-dl-downloader. Options: `uuid` (default, uses MangaDex UUID like `0c6fe779-...`) or `title` (uses manga title like `Roman Club`). Set in Plugin Manager → gallery-dl Downloader settings. Only affects new manga — existing folders are never renamed.
- **MangaDex Subscription Feed** — New `mangadex-subscription` plugin that watches the MangaDex follow feed (`GET /user/follows/manga/feed?publishAtSince=...`) for new chapters and auto-queues downloads. Uses OAuth2 personal client auth and checks the feed at a configurable interval (default 30 min). Deduplicates against existing DB state: checks `mangaDexUuid` → series → CHAPTER_URL IDs, blacklisted chapter IDs, and URL existence before queuing. Groups new chapters by manga to avoid duplicate downloads when both follow.txt and subscription are active. When a manga is newly added to the follow list, all chapters are downloaded (not just new ones since last check) — detected by checking `GET /user/follows/manga` against existing series in DB via `mangaDexUuid`. Disabled by default — requires MangaDex API credentials in Plugin Manager.

### Bug Fixes
- **Download progress counter includes auto-blacklisted chapters** — Progress showed e.g. "2/14" when 12 of 14 chapters were auto-blacklisted. Now excludes auto-blacklisted chapters from the total, showing "2/2" instead.
- **Chapter URLs falsely removed as "stale"** — `importFromSeriesPath` compared DB URLs against URLs extracted from ComicInfo.xml in all CBZ files. If `extractComicInfo` failed for any file (I/O error, missing Web tag, file rewritten by removeHashedPages), the URL was deleted as "stale" even though the CBZ still existed. Removed the stale URL cleanup entirely — it caused a vicious cycle of remove/reimport and forced full CBZ reads on every scan.
- **Excessive I/O during chapter URL import** — Every manual library scan opened all ~16,000 CBZ files to read ComicInfo.xml, overwhelming HDDs. Fixed: fast-path now uses `>=` instead of `==` (skip when DB has at least as many URLs as CBZ files), and URL extraction uses ZIP comments (200 bytes, no decompression) with ComicInfo.xml fallback for older files without comments.
- **In-CBZ duplicate page detection destroys CBZ files** — `addComicInfoToCbz` and `addComicInfoToCbzWithChapterInfo` grouped ZIP entries by page name (without extension) and removed "duplicates". When the same chapter was uploaded twice by the same group on MangaDex, gallery-dl downloaded both into the same directory, creating legitimate same-named files in different formats. The detection then removed most/all pages, producing empty CBZ files that were never properly tracked, causing infinite re-download loops. Removed the in-CBZ duplicate detection entirely.
- **Same-group duplicate chapters cause re-download loop** — When a scanlation group uploads the same chapter twice on MangaDex (different UUIDs, same chapter number, same group), both were downloaded into the same directory causing conflicts. Now auto-detects same-group duplicates across ALL API chapters (not just remaining), keeps the newest upload, auto-blacklists the older one. Also registers chapter URLs directly in the DB after successful download, bypassing the ChapterUrlImporter fast-path that skipped import when CBZ file count hadn't changed.
- **MangaDex feed missing chapters for certain content ratings** — Feed API calls did not include `contentRating[]` parameters, so the API applied its default filter which excludes some rating categories. Added all four content rating levels to feed requests in GalleryDlWrapper and MangaDexSubscriptionSyncer.
- **gallery-dl process output race condition** — `filesDownloaded` counter was a plain `Int` incremented by stdout-thread and read by stderr-thread. Changed to `AtomicInteger`. Reader threads are now joined after `process.waitFor()` to ensure all output is captured before checking results.
- **Date substring IndexOutOfBoundsException** — `publishDate.substring(0, 4)` crashed when the date string was shorter than expected. Now checks `length >= 4/7/10` before extracting year/month/day.
- **Temp config file leaked on exception** — gallery-dl config files created in `/tmp` were not deleted when `GalleryDlException` or generic `Exception` was thrown. Added cleanup in both catch blocks.
- **Blacklist insert race condition** — Concurrent downloads could try to insert the same blacklist entry simultaneously, causing duplicate-key crash. Wrapped both insert sites with try-catch, logging duplicates at DEBUG level.
- **DownloadExecutor processing flag outside submitted task** — `processing.set(false)` was called immediately after `submit()` instead of in the task's `finally` block, allowing duplicate download submissions. Moved back inside `finally`.
- **Folder index overwrite in ChapterChecker** — `buildFolderIndex()` used `index[uuid] = dir` for series.json entries, overwriting UUID→folder mappings from directory names. Changed to `putIfAbsent` so directory-name UUIDs take priority.

### Improved
- **Disabled metadata provider log spam suppressed** — `BookMetadataLifecycle` and `SeriesMetadataLifecycle` logged "skipping" messages at INFO level for every disabled provider (e.g. EpubMetadataProvider) on every book/series scan. Changed to DEBUG level.
- **Chapter check log spam reduced** — `ChapterChecker` and `GalleryDlWrapper` logged ~2000 lines per follow.txt check (5-6 lines per manga × 297 manga). Demoted to DEBUG: per-manga fetch counts, title resolution, metadata details, and "Up to date" confirmations. Only manga with missing chapters now appear at INFO level.
- **Chapter URL check uses DB → ZIP comment → ComicInfo.xml** — `GalleryDlWrapper` now queries the `CHAPTER_URL` database table first, then falls back to ZIP comment extraction, then ComicInfo.xml parsing. Previously opened every CBZ file to read ComicInfo.xml before each download.
- **Pre-compiled regex constants** — Moved `<Web>` regex, volume prefix regex, and bracket group regex from inline creation (per-file/per-match) to companion object constants, avoiding repeated compilation in loops.
- **Plugin config renders enum fields as dropdowns** — Plugin Manager config dialog now uses `v-select` for fields with `enum` in the JSON schema (e.g. `folder_naming`, `default_language`). Also uses schema `title` as label, `description` as hint, and `format: "password"` for password detection. Previously all fields were plain text inputs.
- **Plugin configSchema auto-updates** — `PluginInitializer` now updates the `configSchema` on existing plugins when it changes, instead of skipping them. New config fields (like `folder_naming`) appear immediately after restart without requiring a DB reset.
- **Chapter check uses ID comparison instead of count** — ChapterChecker now fetches all chapter IDs from the MangaDex feed and compares them directly against the DB (chapter_url + blacklist). Previously used inflated API total count which included duplicate uploads, causing permanent re-queuing for manga with duplicate entries.
- **MangaDex API call caching** — GalleryDlWrapper caches `/manga/{id}` metadata and `/manga/{id}/feed` chapter data for 30 minutes. ChapterChecker and download share the same cache, eliminating duplicate API calls. Previously each check+download cycle made 9+ requests per manga, now 2 (one feed, one metadata).
- **Feed pagination limit increased** — `fetchAllChaptersFromMangaDex` uses `limit=500` instead of `limit=100`, reducing pagination requests for large manga (e.g. 500 chapters: 1 request instead of 5).
- **Removed redundant pre-check in DownloadExecutor** — `processDownload` no longer calls `getMangaDexChapterCount` before starting a download. The ID-based check in ChapterChecker is more accurate and already cached.
- **Chapter URL import skips unchanged series** — `importFromSeriesPath` compares CBZ file count against DB URL count. If they match, the series is skipped entirely without opening any CBZ files. Previously every library scan re-read ComicInfo.xml from all ~16,000 CBZ files (15 min), now only series with changes are scanned.

### Performance
- **Shared HttpClient in GalleryDlWrapper** — Reuse a single `HttpClient` instance instead of creating one per request (5 occurrences). Reduces GC pressure and connection setup overhead.
- **Thread-safe MangaDex throttling** — `lastMangaDexRequestTime` uses `AtomicLong` + `@Synchronized` to prevent race conditions in concurrent API calls.
- **Cache eviction for MangaDex API data** — `chapterCache` and `mangaInfoCache` now evict expired entries on access, preventing unbounded memory growth over long-running sessions.
- **Bounded process output buffer** — Gallery-dl stdout/stderr capture is limited to 512 KB via `appendBounded()`, preventing OOM on extremely verbose downloads.
- **Safe file operations** — All `File.delete()` calls check return values via `deleteQuietly()` helper; `renameTo()` replaced with `Files.move()` for reliable cross-filesystem moves.
- **Plugin config loaded once per download** — `pluginConfigRepository` is queried once at the start of `download()` instead of per temp-config-file creation.
- **Silent exceptions logged** — 9 swallowed `catch (_: Exception)` blocks now log at DEBUG level for diagnostics.
- **Log level cleanup** — 20+ internal detail logs demoted from INFO to DEBUG (gallery-dl stdout/stderr, ComicInfo injection, ZIP comments, CBZ moves, series.json writes).
- **Folder index in ChapterChecker** — `buildFolderIndex()` scans libraries once and builds a `Map<String, File>`, replacing per-manga O(n) folder search with O(1) lookup.
- **Executor shutdown fallback** — `ChapterChecker` and `DownloadExecutor` thread pools use `shutdownNow()` fallback after timeout, plus `@PreDestroy` lifecycle management.
- **Race condition fixes in DownloadExecutor** — `processing` flag set after `submit()` (not in task's `finally`), `cancelledIds`/`activeDownloads` synchronized, `pendingScans` protected by dedicated lock.
- **Batch queries in MangaDexSubscriptionSyncer** — `isChapterKnown()` uses pre-loaded `HashMap` lookups instead of 3 DB queries per chapter (N+1 → O(1)). Library loaded once and passed through.
- **Single-pass XML parsing in ChapterUrlImporter** — `parseComicInfoXml()` iterates line-by-line with early-exit instead of 6 separate full-string regex scans.
- **O(1) findExistingMangaFolder in DownloadExecutor** — Replaced `findAllByLibraryId()` + iteration with direct `findNotDeletedByLibraryIdAndUrlOrNull()` DB query. Eliminates O(n) series scan on every download.
- **Progress DB-writes throttled to 5s interval** — Download progress callback wrote to DB on every gallery-dl output line. Now writes at most every 5 seconds. Websocket broadcast remains real-time.
- **Token refresh thread-safety in MangaDexSubscriptionSyncer** — `getValidToken()` annotated with `@Synchronized` to prevent duplicate token refresh requests. `scheduledTask` marked `@Volatile` for cross-thread visibility. Early-return with warning when library not found.

### Security
- **Spring Boot 3.5.11 → 3.5.12** — Fixes CVE-2026-22732 (Spring Security Web 6.5.8 → 6.5.9, severity 9.1) and CVE-2026-22737 / CVE-2026-22735 (Spring WebFlux 6.2.16 → 6.2.17).

### Modified Files
| File | Changes |
|------|---------|
| `libs.versions.toml` | Spring Boot 3.5.11 → 3.5.12 |
| `LibraryContentLifecycle.kt` | `tryRestoreByMangaDexUuid()` restores soft-deleted series on folder rename; `scanSeriesFolder()` creates series if needed, imports chapter URLs per-series only |
| `PageHashRepository.kt` | Added `deleteKnown()` |
| `PageHashDao.kt` | Implemented `deleteKnown()` — deletes from PAGE_HASH + PAGE_HASH_THUMBNAIL |
| `PageHashController.kt` | New `DELETE /{pageHash}` endpoint |
| `PageHashKnownCard.vue` | Remove button (mdi-close-circle) for known duplicate page hashes |
| `DuplicatePagesKnown.vue` | `@removed` event handler reloads data after hash removal |
| `komga-pagehashes.service.ts` | `removeKnownHash()` API method |
| `en.json` | Added `action_remove` translation |
| `ChapterChecker.kt` | ID-based comparison via `GalleryDlWrapper` cache instead of own API calls |
| `GalleryDlWrapper.kt` | Added `getChaptersForManga()`/`getMangaMetadata()` with 30min cache, feed limit 100→500, ZIP comments via `generateZipComment()` |
| `DownloadExecutor.kt` | Skip `getChapterInfo` when title known, removed `getMangaDexChapterCount` pre-check, deferred batch scan via `pendingScans`/`scanPendingFolders()`, configurable folder naming via `folder_naming` plugin config |
| `PluginInitializer.kt` | Added `folder_naming` config option (`uuid`/`title`) to gallery-dl-downloader plugin, auto-updates configSchema on existing plugins |
| `PluginManager.vue` | `v-select` for enum fields, schema `title`/`description`/`format` support in config dialog |
| `ChapterUrlImporter.kt` | Removed stale URL cleanup (was falsely deleting URLs), fast-path `>=` instead of `==`, ZIP comment extraction for minimal I/O |
| `GalleryDlWrapper.kt` (progress) | `totalChapters` excludes auto-blacklisted chapters, `downloadIndex` counter for accurate progress |
| `MangaDexSubscriptionSyncer.kt` | Rewritten: feed-based sync via `GET /user/follows/manga/feed?publishAtSince=`, DB dedup using mangaDexUuid/CHAPTER_URL/blacklist, CustomList auto-setup, OAuth2 auth with token refresh |

---

## [0.1.0] - 2026-03-15

### New Features
- **Manual blacklist URL entry** — Users can now manually paste MangaDex chapter URLs into the "Manage Blacklist" dialog. Useful for the rare edge case where MangaDex has duplicate uploads from the same scanlation group (e.g. manga cbce49c7 has 27 API entries for 11 unique chapters, all from the same group), causing permanent re-queuing since the API total can never match the DB/filesystem count. New `POST /api/v1/series/{seriesId}/blacklist` endpoint accepts `chapterUrl`, `chapterNumber`, and `chapterTitle`.

### Bug Fixes
- **CHAPTER_URL entries deleted on soft-delete** — `BookLifecycle.softDeleteMany` deleted CHAPTER_URL entries when books were soft-deleted (e.g. during library scan when CBZ files were modified by ComicInfo.xml injection). This caused `countDownloadedChapters` to return decreasing values over time, making the ChapterChecker think chapters were missing and re-queue downloads. Fixed by removing the CHAPTER_URL deletion from soft-delete — only hard-delete (via FK CASCADE on SERIES) and explicit API delete should remove entries.
- **mangaDexUuid overwritten with null on every series update** — `SeriesDao.toDomain()` can't read `MANGADEX_UUID` (jOOQ codegen not run), so every normal Komga series update (book count, metadata, etc.) wrote `mangaDexUuid = null` back to DB. Fixed by only writing `mangaDexUuid` in `insert()`/`update()` when the value is not null.
- **syncMangaDexUuid ran for all ~300 series every library scan** — Because `mangaDexUuid` was always null in the Series object (see above), `syncMangaDexUuid` re-read `series.json` and re-set the UUID for every series on every scan. Fixed by checking via `findByMangaDexUuid` whether the UUID is already correctly assigned before reading `series.json`.
- **CBZ file detection failed for volume-prefixed filenames** — After gallery-dl downloads a chapter, ComicInfo.xml injection couldn't find the CBZ because it only matched `c021`/`c21` but not `v4 c021 [Group]`. Fixed by stripping `v<N> ` prefix before matching.
- **Chapter URL import too late in library scan** — `scanAndImportLibrary()` ran at the very end of `scanRootFolder()`, after sidecars and trash cleanup. ChapterChecker saw stale DB counts because URLs hadn't been imported yet. Moved to right after series/book updates, before tasks are emitted.
- **Orchesc/a/ns duplicate CBZ files** — Gallery-dl created both `[Orchesc a ns].cbz` and `[Orchesc_a_ns].cbz` because slashes in scanlation group names were sanitized inconsistently across runs. Added `path-restrict: auto` and `path-replace: _` to mangadex gallery-dl config for consistent filename sanitization.

### Changed
- **Fork migrations separated from upstream** — Fork database migrations now use a dedicated `flyway_fork_history` table instead of the shared `flyway_schema_history`. This allows seamless switching between official Komga and the fork without manual database cleanup. Existing fork entries are automatically moved from `flyway_schema_history` to `flyway_fork_history` on first startup.

### Removed
- **Redundant download tables** — Dropped `DOWNLOAD_CHAPTER_HISTORY` and `DOWNLOAD_ITEM` tables (Flyway migration). Both were never used in code (repositories existed but were never injected). `CHAPTER_URL` is the single source of truth for downloaded chapter tracking. Also removed `DownloadChapterHistory.kt`, `DownloadChapterHistoryRepository.kt`, `DownloadChapterHistoryDao.kt`, `DownloadItemRepository.kt`, `DownloadItemDao.kt`, and `DownloadItem` from `DownloadQueue.kt`.

### Modified Files
| File | Changes |
|------|---------|
| `SeriesDao.kt` | `insert()`/`update()` only write `mangaDexUuid` when not null |
| `ChapterUrlImporter.kt` | `syncMangaDexUuid` checks DB before reading series.json |
| `BookLifecycle.kt` | Removed CHAPTER_URL deletion from `softDeleteMany` |
| `GalleryDlWrapper.kt` | CBZ detection strips `v<N> ` prefix, added `path-restrict`/`path-replace` for consistent filename sanitization |
| `LibraryContentLifecycle.kt` | Moved `scanAndImportLibrary()` earlier in scan, before task emission |
| `SeriesController.kt` | New `POST /api/v1/series/{seriesId}/blacklist` for manual URL blacklisting |
| `BlacklistDialog.vue` | Added URL input field for manual blacklist entries |
| `komga-series.service.ts` | Added `addBlacklist()` method |
| `FlywayForkMigrationInitializer.kt` | Separate fork migration history table, auto-migrates from `flyway_schema_history` |
| `V20260315000001__drop_redundant_download_tables.sql` | Drop `DOWNLOAD_CHAPTER_HISTORY` and `DOWNLOAD_ITEM` |

---

## [0.0.9] - 2026-03-14

### New Features
- **MangaDex UUID ↔ Komga Series ID mapping** — New `MANGADEX_UUID` column on the SERIES table (Flyway migration) enables direct DB lookup between MangaDex manga UUIDs and Komga series IDs. Eliminates filesystem scanning for series identification. `DownloadExecutor` sets `mangaDexUuid` on the series after successful download. `ChapterChecker` and `findExistingMangaFolder` use direct DB lookup as primary method, falling back to filesystem scan only when no DB mapping exists.
- **ChapterUrlImporter imports from ComicInfo.xml** — During library scan, reads `<Web>` tags from ComicInfo.xml inside CBZ files and imports chapter URLs into the `CHAPTER_URL` table with the correct Komga series ID. Enables accurate `countDownloadedChapters` via DB instead of filesystem counting. Also extracts chapter number, volume, title, language, and scanlation group from ComicInfo.xml.
- **MangaDex Subscription Feed Sync** — New plugin (`mangadex-subscription`) that watches your MangaDex follow feed for new chapters and auto-downloads them. Uses OAuth2 personal client auth, auto-creates and subscribes to a CustomList. Periodic feed checks (default every 30 min) query `GET /user/follows/manga/feed?publishAtSince=...` for new chapters and queue them for download. Requires a MangaDex personal API client (OAuth2 password grant). Completely independent from the existing follow.txt system. Disabled by default — configure credentials in Plugin Manager to enable.
- **`gallery_dl_path` plugin config** — New config option to point Komga at a local gallery-dl source checkout (e.g. `/path/to/gallery-dl/`). Sets `PYTHONPATH` on all gallery-dl subprocess calls so `python -m gallery_dl` loads from the local source instead of the system-installed package. Useful for running the latest gallery-dl with new extractors (e.g. weebdex.py) without reinstalling.

### Bug Fixes
- **7 missing chapters not downloading** — CBZ filename matching in `GalleryDlWrapper` falsely marked multi-group chapters as "already downloaded" when only a different group's version existed. Removed filename matching entirely — only URL-based matching (ComicInfo.xml `<Web>` tag) and blacklist are used now.
- **6 mangas unnecessarily queued every check** — `ChapterChecker.countDownloadedChapters` searched `url.contains(mangaId)` but chapter URLs don't contain manga IDs, so DB count was always 0. Fixed to use `findSeriesForManga` + `countBySeriesId`. Known count now uses `maxOf(dbCount, fsCount) + blacklistedCount`.
- **Guest browsing ("als Gast durchsuchen") broken** — `GuestAccessFilter` used `request.requestURI` which includes the `/komga` context path, so `isGuestPath()` never matched. Fixed to use `request.servletPath`.
- **CBZ file detection after download fails for volume-prefixed filenames** — After gallery-dl downloads a chapter, ComicInfo.xml injection couldn't find the CBZ because it only matched `c021` / `c21` but not `v4 c021 [Group]`. Now strips `v<N> ` prefix before matching.
- **BookControllerTest ConcurrentModificationException** — Unicode book file test failed with `ConcurrentModificationException` in Spring Boot actuator's `HttpExchangesFilter`. Fixed by making `HttpExchangeConfiguration` conditional on `management.httpexchanges.recording.enabled` property and disabling it in test profile.
- **Race condition: ChapterChecker reports false "new chapters"** — `countFilesystemChapters()` relied on reading `series.json` to find manga folders. When the download worker was writing `series.json` simultaneously, the file was briefly unreadable, causing the filesystem count to drop to 0. Now checks UUID folder name directly first (no file I/O needed), falling back to `series.json` scan only for non-UUID folders.
- **Downloads processed even when all chapters exist** — Full gallery-dl process ran even when nothing to download. Now performs a lightweight pre-check: compares CBZ count on disk against MangaDex API chapter count, and skips the download immediately if all chapters are already present.
- **BLACKLISTED_CHAPTER FK constraint violation** — `seriesId` was set to MangaDex UUID instead of Komga's internal SERIES.ID, causing every blacklist insert to crash. Now passes the correct Komga series ID from `findExistingMangaFolder`.
- **Unnecessary ComicInfo.xml rewrites on every run** — `hasMismatchedDates()` decompressed and recompressed every CBZ to check dates, even when nothing changed. Replaced with `hasComicInfoXml()` that only checks file existence.
- **gallery-dl compatibility with non-MangaDex sites** — `parseGalleryDlJson` only handled Queue messages (type 6) used by MangaDex extractors. Single-image sites like wallhaven.cc yield Directory (type 2) + Url (type 3) messages which were ignored. Now processes all three message types and uses a title fallback chain.
- **Title "Unknown" crash for non-MangaDex URLs** — `getChapterInfo()` threw `GalleryDlException` when the extracted title was "Unknown". Now derives a fallback title from the URL.
- **Downloads saved to "Unknown" folder when title not yet known** — `DownloadExecutor.processDownload()` used the queued title as the folder name before `getChapterInfo` resolved the real title. Now renames/moves files into the correct folder after download completes.
- **Publisher hardcoded to "MangaDex" for all sites** — Now derives the publisher from the source URL domain.
- **"Search Online Metadata" button not applying metadata** — Now calls `PATCH /api/v1/series/{id}/metadata` to apply metadata directly.
- **Non-MangaDex multi-chapter sites only downloaded 1 CBZ** — Now extracts chapter info from Queue messages and downloads each chapter individually.
- **MangaDex 429 rate limit crashes downloads** — Now retries up to 2 times with 5-second delays when rate-limited.
- **Re-downloads when MangaDex title changes** — `findExistingMangaFolder()` now searches both folder names and `series.json` content for the MangaDex UUID.
- **Bulk re-download when MangaDex chapter API returns empty** — Now returns early for MangaDex URLs when the chapter API returns empty.
- **Paid/unavailable chapters retried indefinitely** — Now tracks failures in `.chapter-failures.json` per manga folder and auto-blacklists chapters after 3 failed attempts.

### Changed
- **UUID folder names for MangaDex downloads** — Download folders now use the MangaDex UUID as folder name instead of the manga title. Eliminates re-downloads caused by MangaDex title changes.
- **No more CBZ file renaming** — gallery-dl's native filenames (e.g. `c005 [No Group Scanlation].cbz`) are kept as-is.
- **Simplified MangaDex folder naming** — Download destination is always `<libraryPath>/<mangaDexId>` directly.
- **Dockerfile fix** — Removed `dpkg-architecture` call that caused build failure (exit code 127), changed `WORKDIR app` to `WORKDIR /app`.
- **Backup files removed from git** — `.before_*`, `.2025*`, `.2026*` patterns added to `.gitignore`, existing tracked backup files removed.

### Performance
- **Docker build ~50% faster** — Pre-built kepubify binary instead of Go compilation (~6 min saved), runtime libs instead of -dev packages, removed `apt-get upgrade`, dropped arm/v7 (32-bit ARM), added GitHub Actions Docker layer cache (`cache-from/cache-to: type=gha`)
- **Sass 1.79 migration** — Bumped `sass` from `^1.32.13` to `~1.79.0` so `silenceDeprecations: ['slash-div']` takes effect, eliminating ~475 Vuetify SASS deprecation warnings during frontend build

### Removed
- `buildDesiredCbzName`, `sanitizeFsName`, `getEnglishTitleForFolderName`, `isUuidDerivedTitle` — dead code after removing CBZ rename logic
- **CBZ filename matching** — Removed entirely from chapter filter in `GalleryDlWrapper`. Only URL-based matching and blacklist remain.
- **arm/v7 (32-bit ARM) Docker platform** — barely used, extremely slow under QEMU emulation. arm64 and amd64 remain.
- **Go toolchain from Docker build** — kepubify is now downloaded as pre-built binary from GitHub releases

### Modified Files
| File | Changes |
|------|---------|
| `Series.kt` | Added `mangaDexUuid: String? = null` field |
| `SeriesRepository.kt` | Added `findByMangaDexUuid()` |
| `SeriesDao.kt` | Implemented `findByMangaDexUuid`, `mangaDexUuid` in insert/update |
| `V20260315000000__series_mangadex_uuid.sql` | Flyway migration: `MANGADEX_UUID` column + unique index |
| `ChapterUrlImporter.kt` | Full implementation: reads ComicInfo.xml from CBZ files, imports chapter URLs into DB |
| `ChapterChecker.kt` | `findSeriesForManga` with direct UUID DB lookup, `countDownloadedChapters` via `countBySeriesId`, `knownCount = maxOf(db, fs) + blacklisted` |
| `DownloadExecutor.kt` | Sets `mangaDexUuid` after download, `findExistingMangaFolder` uses UUID DB lookup first |
| `GalleryDlWrapper.kt` | Removed CBZ filename matching, fixed CBZ detection for volume-prefixed filenames |
| `GuestAccessFilter.kt` | `request.requestURI` → `request.servletPath` |
| `LibraryContentLifecycle.kt` | Pass `libraryId` to `scanAndImportLibrary` |
| `.gitignore` | Added `*.before_*`, `*.2025*`, `*.2026*` patterns |
---

## [0.0.8] - 2026-02-28

### Improved
- **Primary title added to alternate titles** — The main MangaDex `title` (e.g., romaji "Sagishi to Keisatsukan no Rennai Kyori") is now included in `alternate_titles` in `series.json`. Previously only `altTitles` from the API were collected, so the primary title was lost if an alt English title was used as the series name.
- **Background scanning** — `/check-new` and `/follow-txt/{id}/check-now` now return 202 Accepted immediately and run the chapter check in the background. Previously checking 200 mangas blocked the HTTP request for minutes and froze the UI. New PENDING items appear automatically via the existing 5-second poll.
- **Gradle build cache** — Enabled `org.gradle.caching=true` and `org.gradle.parallel=true` in `gradle.properties`, added `buildCache { local { enabled = true } }` to `settings.gradle`. Expected CI speedup of ~2-3 minutes per build.
- **Release workflow speedup** — Merged separate `version` job into `release` job (saves runner startup), replaced `fetch-depth: 0` with shallow clone + `gh release list`, removed unnecessary `Pull latest changes` step.
- **Docker image optimized** — Switched from Ubuntu 24.10 (EOL, needed `old-releases.ubuntu.com` hack) to 24.04 LTS (stable mirrors). Added `--no-install-recommends` and `pip3 --no-cache-dir` for smaller image and faster builds. Fixed `org.opencontainers.image.source` label to point to fork repo.

### New Features
- **Chapter Blacklist** — Blacklist specific chapters from the book 3-dot menu ("Blacklist & Delete") to prevent re-download. The chapter is added to the blacklist and the book file is automatically deleted. Persists even after book deletion. Skipped by downloader and chapter checker. Manage all blacklisted chapters via "Manage Blacklist" in the series 3-dot menu.
- **"All" option in page size selector** — Added "All" to the page size menu (20/50/100/200/500/All) in series and book browse views. The selected page size now persists correctly across page reloads, including when "All" is selected.

### Bug Fixes
- **ChapterChecker used wrong MangaDex API endpoint** — `fetchMangaDexAggregate()` used `/manga/{id}/aggregate` which deduplicates chapters by chapter number, returning incorrect counts when multiple scanlation groups upload the same chapter (e.g. 59 instead of 67). Switched to `/manga/{id}/feed?translatedLanguage[]={lang}&limit=0` which returns the correct `total` field including all chapter entries. Also reads the configured download language from plugin settings instead of hardcoding `en`.
- **"Rows per page" not persisted in data tables** — Page size selection in OversizedPages, DuplicateFiles, MediaAnalysis, MissingPosters, HistoryView, PageHashMatchesTable, and AuthenticationActivityTable was reset to default on every page reload. Added `dataTablePageSize` to Vuex persisted state so the selection survives page reloads and is shared across all data table views.
- **Series "date updated" sorting used filesystem timestamp instead of ComicInfo.xml date** — Sorting by `lastModifiedDate` used the Series entity's `LAST_MODIFIED_DATE` column (filesystem timestamp), so all series scanned together had nearly identical dates. Now uses a scalar subquery `MAX(book_metadata.RELEASE_DATE)` joined through BOOK to sort by the latest ComicInfo.xml publication date in the series.
- **Lucene range queries broken by search escaping** — The v0.0.8 search fix escaped all special characters including `[`, `]`, `:` in range queries like `release_date:[1990 TO 2010]`. The `]` in `2010]` was escaped to `2010\]`, breaking range query syntax. Now detects range queries (`field:[a TO b]`) as a unit before splitting by spaces.
- **Search broken with `-` and `:` characters** — Searching for titles like "Re:Zero" or "Sword Art Online - Alicization" returned no results because `-` (NOT operator) and `:` (field separator) are Lucene query syntax characters. The parser threw a `ParseException` which was silently caught, returning empty results. Now escapes all special characters via `QueryParser.escape()` before parsing.
- **Chapter CBZ matching grabbed wrong file** — After downloading a chapter, the fallback `recentCbzFiles.firstOrNull()` blindly grabbed any recently modified CBZ when the chapter-number match failed, causing chapters to contain images from completely different chapters (e.g. chapter 341 containing chapter 91's images). Removed the blind fallback; matching now strictly requires chapter number in the filename. Also tightened `startsWith` checks to require a space/delimiter after the chapter number to prevent false prefix matches.
- **Double bracket filenames causing duplicate downloads** — Gallery-dl creates filenames like `c054 [['group']].cbz` with double brackets. These didn't match the expected `c054 [group]` pattern, causing chapters to be re-downloaded. Added `normalizeDoubleBracketFilenames()` to rename `[['x']]` → `[x]` before chapter matching. Now also called after both bulk and per-chapter download paths.
- **ComicInfo.xml updated unnecessarily** — `updateExistingCbzChapterUrls` was processing the same file multiple times (once per scanlation group) and triggering updates even when dates were correct. Added `alreadyUpdated` set to prevent double processing, and improved `hasMismatchedDates()` to check Year+Month+Day (was only checking Year).
- **Fixed double brackets `[['group']]` in CBZ filenames** — gallery-dl's `{group}` returns a Python list, which when stringified produces `['group']`. Combined with the `[{group}]` format wrapper this created `[['group']]` directories. Fixed by using `{group:J, }` format specifier which joins list elements into a plain string.
- **Decimal chapter matching broken** — `54.2` was not zero-padded to `054.2`, causing ComicInfo.xml injection and date updates to silently fail for decimal chapters. Extracted shared `padChapterNumber()` helper that handles both integer (`5` → `005`) and decimal (`54.2` → `054.2`) chapter numbers. Replaced 4 inline padding blocks.
- **Bulk download path didn't rename CBZ files** — Only the per-chapter download loop called `buildDesiredCbzName` to rename files to `Ch. 001 - Title [Group].cbz` format. Bulk download path now also renames after ComicInfo injection.
- **`updateExistingCbzChapterUrls` missing `c$paddedNum` patterns** — Matching only checked `c$chapterStr` (unpadded) but gallery-dl creates `c054` (padded) filenames. Added `c$paddedNum` and `c$paddedNum ` patterns so padded filenames are correctly matched.
- **ChapterChecker false positives — all manga queued as PENDING** — `countFilesystemChapters()` searches `series.json` for the mangaDexId string, but `series.json` never stored it. Every manga returned 0 filesystem chapters, so all 184 were queued as needing download. Now stores `comicid` (mangaDexId) and `cover_filename` in `series.json` metadata via the `MangaInfo` data class.
- **Cover images re-downloaded every run** — `downloadMangaCover()` was called unconditionally with no existence check, re-downloading ~187 cover images. Each overwrite triggered Komga's sidecar detection → artwork refresh → metadata refresh cascade (~30,000 tasks). Now checks if cover file exists and `cover_filename` hasn't changed before downloading.
- **series.json rewritten unnecessarily** — `createSeriesJson()` always overwrote the file even when content was identical, triggering Komga's filesystem watcher cascade. Now compares new content with existing file and skips rewrite when unchanged.

---

## [0.0.7] - 2026-02-23

### New Features

#### Guest/Kiosk Mode (#1202)
- **Read-only browsing without login** — Toggleable in admin Settings → UI. When enabled, a "Als Gast durchsuchen" button appears on the login page, allowing unauthenticated users to browse series, books, and libraries without creating an account.
- **Per-library guest access** — Admins can select which libraries are visible to guests. Empty selection = all libraries. Backend enforces library restrictions via a virtual `KomgaUser` with `sharedLibrariesIds`.
- **Security** — Guest access is limited to GET requests on `/api/v1/series/**`, `/api/v1/books/**`, `/api/v1/libraries/**`. Admin, account, settings, downloads, and import routes are blocked for guests. Navigation drawer hides admin sections and shows a Login link instead of Logout.

#### Logs in Web UI (#80)
- **Admin-only log viewer** — New Settings → Logs page displays the last N lines of `komga.log` in a dark monospace viewer with color-coded log levels (ERROR=red, WARN=orange, DEBUG=grey).
- **Auto-refresh** — Toggle 5-second polling to watch logs in real time.
- **Search/filter** — Client-side text filter to quickly find log entries.
- **Download** — Full log file download via `GET /api/v1/logs/download`.
- **API** — `GET /api/v1/logs?lines=500` returns last N lines as `text/plain`.

#### Custom Color Themes (#1427)
- **7 predefined theme presets** — Default, AMOLED, Nord, Dracula, Solarized, Green, Red. Each preset defines both light and dark mode colors.
- **Persistent selection** — Theme preset is saved in browser local storage and applied on startup.
- **UI** — Clickable preset cards with icon, label, and color preview dots in Account → UI Settings.

#### Fork Version Check
- **Fork update notifications** — The Updates page now has two tabs: "Upstream (Komga)" and "Fork". Fork releases are fetched from `08shiro80/komga-enhanced` GitHub releases with a separate 1-hour cache.
- **Badge indicators** — The version badge in the nav drawer and the Updates nav item show a warning dot when either upstream or fork updates are available.
- **API** — `GET /api/v1/releases/fork` returns fork releases from GitHub (admin-only, 1-hour cached).

#### Configurable Download Scheduler
- **Single unified scheduler** — Removed the hardcoded `@Scheduled(cron = "0 0 */6 * * *")` annotation that ran every 6 hours alongside the dynamic `TaskScheduler`. Now only one dynamic scheduler runs, eliminating duplicate chapter checks.
- **Interval or Fixed Time** — New `scheduleMode` setting: `"interval"` (repeat every N hours, existing behavior) or `"fixed_time"` (run once daily at a specific `HH:mm` time using `CronTrigger`).
- **UI controls** — The Configuration tab now shows a radio group to pick Interval vs Fixed Time, with the appropriate input (hours slider or time picker) shown for each mode.

### Bug Fixes
- **Guest mode: read buttons grayed out** — `guestBrowse()` never populated the store's `me` user object, so `mePageStreaming` was always false and all read buttons were disabled. Now sets a synthetic guest user with `PAGE_STREAMING` role in the store. Also restores the guest user on page refresh in the router guard. Added `/api/v1/users/me` to `GuestAccessFilter` allowed paths.
- **Guest mode: read progress FK crash** — The guest filter's authentication leaked to the HTTP session, causing non-GET requests (like `markReadProgress`) to run as the virtual guest user (ID `"guest"`) which doesn't exist in the database, triggering `FOREIGN KEY constraint failed`. Fixed by: (1) clearing guest SecurityContext after each request so it never persists to the session, (2) skipping `markProgress` in DivinaReader and EpubReader when in guest mode.
- **ComicInfo.xml wrong dates** — Year/Month/Day were inconsistent: Year used the manga's start year (e.g. 2021) while Month/Day used the chapter's publish date (e.g. 01/19 from 2025-01-19), resulting in dates like 2021-01-19 instead of 2025-01-19. Chapter `publishDate` now takes priority for all three fields. Manga start year is only used as fallback when no chapter publish date is available.
- **Auto-fix dates in existing CBZ files** — `updateExistingCbzChapterUrls` now checks all existing CBZ files for mismatched dates (Year doesn't match publishDate year) and regenerates ComicInfo.xml with correct dates. Runs automatically during the next download for each manga.
- **Single-download path missing chapter metadata** — When the MangaDex API chapter list was empty on first attempt, the fallback single-download path injected ComicInfo.xml with series metadata only (no chapter title, publish date, scanlation group). Now retries fetching the chapter list from MangaDex API after gallery-dl completes, matches chapters by number extracted from filenames, and injects full chapter metadata.
- **Guest mode: libraries not loading** — Guest users always landed on the "No libraries" welcome page because the startup flow (which loads libraries) was skipped in guest mode. `guestBrowse()` now loads libraries before navigating. The router guard also loads libraries on page refresh for guests.
- **Guest mode: lost on page refresh** — `guestMode` was not persisted across page reloads. Now stored in `vuex-persistedstate` so guest sessions survive browser refresh. If guest access was disabled server-side, the guest is redirected to login.
- **Guest mode: login not clearing guest state** — Logging in via credentials while `guestMode` was persisted could leave stale guest state. `performLogin()` now clears `guestMode` before authenticating.

### Improved
- **Search: partial word matching** — Search now works with incomplete words beyond the first term. Previously, typing "tensei s" returned no results because single-character terms couldn't match the NGram index (minGram=3). Each search word is now treated as a prefix query (`tensei* s*`), so partial input immediately finds matches without needing to type full words.

### Security
- **Downloads page hidden for non-admin users** — The `/downloads` navigation link is now only visible to admin users (`v-if="isAdmin"`). Added `adminGuard` to the route so non-admin users navigating directly to `/downloads` are redirected to home. Backend already enforced `@PreAuthorize("hasRole('ADMIN')")` on all download API endpoints.

### New Files
| File | Purpose |
|------|---------|
| `GuestAccessFilter.kt` | `OncePerRequestFilter` — creates virtual guest `KomgaPrincipal` for unauthenticated GET requests when guest mode is enabled |
| `LogController.kt` | Admin-only REST controller for log viewing and download |
| `LogsView.vue` | Log viewer with auto-refresh, search, download, color-coded levels |
| `theme-presets.ts` | 7 theme preset definitions with light/dark color sets |

### Modified Files
| File | Changes |
|------|---------|
| `ReleaseController.kt` | Added `GET /api/v1/releases/fork` endpoint, separate GitHub API + cache for fork releases |
| `SecurityConfiguration.kt` | Register `GuestAccessFilter` before `UsernamePasswordAuthenticationFilter` |
| `komga-clientsettings.ts` | Added `WEBUI_GUEST_ACCESS` and `WEBUI_GUEST_LIBRARIES` setting keys |
| `UISettings.vue` | Guest mode checkbox + library multi-select for guest access |
| `LoginView.vue` | "Als Gast durchsuchen" button when guest mode is enabled |
| `router.ts` | Guest-aware auth guard, `/settings/logs` route |
| `store.ts` | `guestMode`, `forkReleases` state, `isForkLatestVersion()` getter |
| `HomeView.vue` | Logs nav item, fork releases fetch, combined update badges, guest-aware nav sections |
| `UpdatesView.vue` | Tabs for Upstream/Fork releases, fork version status alerts |
| `komga-releases.service.ts` | Added `getForkReleases()` method |
| `persisted-state.ts` | `themePreset` state + `setThemePreset` mutation |
| `vuetify.ts` | `applyThemePreset()` function |
| `App.vue` | Watcher for `themePreset` changes |
| `UIUserSettings.vue` | Theme preset selector with clickable cards |
| `LuceneHelper.kt` | Prefix wildcard query for each search term |
| `FollowConfig.kt` | Added `scheduleMode` and `checkTime` fields |
| `DownloadScheduler.kt` | Removed `@Scheduled(cron)`, added interval/fixed_time mode support via `CronTrigger` |
| `DownloadDto.kt` | Added `scheduleMode` and `checkTime` to scheduler DTOs |
| `DownloadController.kt` | Pass `scheduleMode`/`checkTime` through scheduler endpoints |
| `DownloadDashboard.vue` | Radio group for schedule mode, conditional interval/time inputs |
| `application.yml` | Removed hardcoded `cron` config line |
| `GuestAccessFilter.kt` | Added `/api/v1/users/me` to guest-allowed paths |
| `LoginView.vue` | Set synthetic guest user with `PAGE_STREAMING` role in store on guest browse |
| `router.ts` | Restore guest user info on page refresh |
| `DivinaReader.vue` | Skip `markProgress` in guest mode |
| `EpubReader.vue` | Skip `markProgress` in guest mode |

---

## [0.0.6] - 2026-02-22

### Performance
- **JPEG page hashing 10-50x faster** — Replaced full image decode/re-encode (`ImageIO.read` → `ImageIO.write`) with direct JPEG metadata byte stripping. EXIF, APP, and COM segments are removed at byte level without touching pixel data, eliminating the most expensive operation in `hashPage()`.
- **File hashing 2-4x faster on large files** — Increased hash buffer from 8 KB to 64 KB, reducing system calls per file by 8x. Affects both `compute hash for files` and `compute hash for pages` tasks.
- **Faster hex encoding** — Replaced `joinToString` with pre-allocated `StringBuilder` and lookup table for hash-to-hex conversion, eliminating intermediate string allocations.
- **Thumbnail resize: skip redundant stream** — `resizeImageBuilder()` only calls `detectMediaType()` when the image is smaller than target size (early-out check). Previously created 3 streams from the same bytes every time.
- **Transparency check via alpha raster** — `containsTransparency()` now reads the alpha raster directly instead of calling `getRGB()` per pixel, which avoids color model conversion overhead on every pixel.
- **RAR entry analysis: stream reuse** — RAR and RAR5 extractors now use a single buffered stream with `mark()`/`reset()` for media type detection and dimension analysis, instead of creating two separate `inputStream()` instances per archive entry.
- **Library scan O(n²) → O(n)** — Converted 4 List-based `contains()` lookups to Set-based O(1) lookups in `LibraryContentLifecycle` (series URLs, book URLs, sidecar URLs, file hash matching). Significant speedup for large libraries during scans.
- **Book sorting O(n²) → O(n)** — `SeriesLifecycle.sortBooks()` now uses a Map for metadata lookup instead of nested `first{}` search, eliminating O(n²) matching when sorting books in a series.

### Bug Fixes
- **Fix resume download 400 error** — Added missing "resume" action handler to `DownloadController`. Previously, clicking Resume in the UI returned HTTP 400 because only "cancel" and "retry" were handled. Resume now resets any failed/cancelled download back to PENDING without incrementing retry count.

### Changed
- **Chapter URL stored in ComicInfo.xml** — Chapter URLs are now stored in the `<Web>` tag of ComicInfo.xml inside each CBZ file, replacing the previous database-only tracking via `chapter_url` table. Download deduplication now reads URLs from existing CBZ files instead of the database, so deleting a CBZ file and re-running the download will correctly re-download it.
- **Auto-update old ComicInfo.xml with chapter URLs** — When a download runs and finds existing CBZ files without chapter URLs in their ComicInfo.xml, it automatically updates them with the correct MangaDex chapter URL. This backfills metadata for previously downloaded chapters.
- **Removed `chapterUrlRepository` dependency from `GalleryDlWrapper`** — Download deduplication no longer queries the `chapter_url` database table. CBZ files are the single source of truth for which chapters have been downloaded.

### Modified Files
| File | Changes |
|------|---------|
| `Hasher.kt` | Buffer 8 KB → 64 KB, optimized `toHexString()` |
| `BookAnalyzer.kt` | `hashPage()` uses `stripJpegMetadata()` instead of ImageIO roundtrip |
| `ImageConverter.kt` | `resizeImageBuilder()` lazy mediaType detection, `containsTransparency()` via alpha raster |
| `RarExtractor.kt` | Stream reuse with mark/reset instead of double stream creation |
| `Rar5Extractor.kt` | Stream reuse with mark/reset instead of double stream creation |
| `LibraryContentLifecycle.kt` | List→Set for URL/hash lookups (4 places), eliminates O(n²) during library scans |
| `SeriesLifecycle.kt` | `sortBooks()` metadata lookup via Map instead of O(n²) `first{}` search |
| `GalleryDlWrapper.kt` | Chapter URL in ComicInfo.xml `<Web>` tag, CBZ-based dedup instead of DB, auto-update old CBZ files |
| `DownloadExecutor.kt` | Added `resumeDownload()` method |
| `DownloadController.kt` | Added "resume" action handler |

---

## [0.0.5] - 2026-02-20

### Bug Fixes
- **Fix cancelled downloads continuing to process** — `cancelDownload()` and `deleteDownload()` now immediately kill the gallery-dl subprocess via `Process.destroyForcibly()`. Previously, cancellation was only checked inside the progress callback, allowing the subprocess to keep running between chapters.
- **Fix duplicate downloads every follow check** — Follow check now uses the new `ChapterChecker` service which compares MangaDex aggregate chapter counts against downloaded chapters (DB + filesystem). Downloads are only created when new chapters are actually detected, eliminating duplicate entries.
- **Remove `.chapter-urls.json` system** — The `.chapter-urls.json` file could contain entries for chapters that weren't fully downloaded (saved before CBZ was finalized). Duplicate detection now relies solely on the `chapter_url` database table and filesystem CBZ checks, which are both reliable. Existing `.chapter-urls.json` files are cleaned up during library scans.

### New Features
- **Fast parallel chapter checking** — New `ChapterChecker` service checks all followed manga for new chapters using the MangaDex aggregate endpoint (`/manga/{id}/aggregate`). Runs 5 concurrent checks, reducing check time for 200 manga from 6+ hours to under a minute.
- **Chapter naming with title** — Downloaded chapters are now named `Ch. 001 - Chapter Title.cbz` instead of `c001.cbz`. Falls back to `Ch. 001.cbz` when no title is available.
- **Multi-group scanlation support** — Same chapter from different scanlation groups is now downloaded separately. Group name is included in the gallery-dl directory pattern (`c{chapter} [{group}]`) to prevent file collisions. When multiple groups exist for the same chapter number, the CBZ filename includes the group name: `Ch. 001 - Title [GroupName].cbz`.
- **Check-new API endpoints** — `POST /api/v1/downloads/check-new` triggers a chapter check and queues downloads for manga with new chapters. `POST /api/v1/downloads/check-only` runs the check without queuing.
- **Cancellation check between chapters** — Download cancellation is now checked between each chapter download in addition to the progress callback, ensuring faster response to cancel requests.
- **Process tracking** — Active download processes are tracked via `ActiveDownload` data class, enabling immediate subprocess termination on cancel/delete.

### Performance
- **MangaDex aggregate endpoint** — Uses `/manga/{id}/aggregate` for quick chapter count comparison instead of the full `/manga/{id}/feed` endpoint. Much faster for checking if new chapters exist.
- **5-concurrent chapter checking** — Parallel checking with semaphore-based concurrency control, staying within MangaDex rate limits (~5 req/s).
- **Skip up-to-date manga** — Manga where the aggregate chapter count matches the downloaded count are skipped entirely, no download entry created.

### Technical Details

#### New Service
- `ChapterChecker` — Fast parallel chapter checking using MangaDex aggregate endpoint, replaces sequential `processFollowList()`

#### New API Endpoints
- `POST /api/v1/downloads/check-new` — Check for new chapters and queue downloads
- `POST /api/v1/downloads/check-only` — Check for new chapters without queuing

#### Modified Files
| File | Changes |
|------|---------|
| `DownloadExecutor.kt` | `ActiveDownload` data class, process tracking, subprocess killing on cancel/delete |
| `GalleryDlWrapper.kt` | Removed `.chapter-urls.json` system, added `isCancelled`/`onProcessStarted` params, new chapter naming, multi-group directory pattern, `extractMangaDexId` moved to companion object |
| `ChapterUrlImporter.kt` | Gutted — now only cleans up legacy `.chapter-urls.json` files |
| `DownloadScheduler.kt` | Uses `ChapterChecker` instead of `processFollowList()` |
| `DownloadController.kt` | Added `check-new` and `check-only` endpoints |
| `DownloadDto.kt` | Added `ChapterCheckResultDto` and `ChapterCheckSummaryDto` |
| `gradle.properties` | Version bumped to 0.0.5 |

---

## [0.0.4] - 2026-02-16

### Added
- `DELETE /api/v1/downloads/clear/pending` endpoint to clear pending downloads
- `isUrlAlreadyQueued()` public method on DownloadExecutor
- `existsBySourceUrlAndStatusIn()` on DownloadQueueRepository for status-aware duplicate checking
- 44+ new locale/translation files (ar, bg, ca, cs, da, el, eo, fa, fi, gl, he, hr, hu, id, ja, ko, nb, sl, th, ta, ti, tr, uk, vi, zh-Hans, zh-Hant, and more)
- Docker image reference and Docker Compose example in README
- `publisher` field in generated series.json for better metadata compatibility

### Fixed
- **Follow list duplicate prevention** — now includes COMPLETED status in addition to PENDING/DOWNLOADING, preventing re-queuing of already downloaded manga
- **Follow config duplicate check** — `processFollowConfigNow` checks for existing queue entries before adding URLs
- **Null safety in Mylar metadata** — status field mapping now handles null values correctly

### Improved
- **Shortest title selection** — when the English title exceeds 80 characters, automatically uses the shortest available English title from both main and alternative titles
- **Better cancellation handling** — dedicated `cancelledIds` tracking set, cancellation checked before processing starts and during progress callbacks
- **Improved filename sanitization** — enhanced regex removes all invalid Windows filename characters, trims trailing dots

### Changed
- Kotlin 2.2.0 → 2.2.21
- ktlint plugin 13.0.0 → 13.1.0
- ben-manes versions plugin 0.52.0 → 0.53.0
- JReleaser 1.19.0 → 1.21.0

---

## [0.0.3] - Initial Fork Release

### Added

#### MangaDex Download System
- **Download Queue** — Queue-based download management with priority support
- **gallery-dl Integration** — Download manga directly from MangaDex using gallery-dl
- **Real-time Progress** — SSE-based download progress updates in the UI
- **ComicInfo.xml Injection** — Automatic metadata injection into downloaded CBZ files
- **Crash Recovery** — Incremental chapter tracking, downloads resume from last completed chapter
- **Rate Limiting** — Respect MangaDex API limits with configurable throttling
- **Multi-language Support** — Download chapters in preferred language

#### Follow List Automation
- **follow.txt Support** — Per-library follow lists for automatic chapter checking
- **Scheduled Downloads** — Cron-based automatic new chapter detection
- **Configurable Intervals** — Set check frequency per library (default: 24 hours)
- **Duplicate Prevention** — Skip already-downloaded chapters automatically

#### Tachiyomi/Mihon Integration
- **Backup Import** — Import MangaDex URLs from Tachiyomi/Mihon backup files
- **Format Support** — `.tachibk` (Mihon/forks) and `.proto.gz` (Tachiyomi legacy)
- **Bulk Import** — Extract all MangaDex URLs in one operation
- **Duplicate Detection** — Skip URLs already in follow.txt

#### Page Splitting
- **Oversized Page Detection** — Scan for pages with configurable height threshold
- **Tall Image Splitting** — Split vertical webtoon pages into readable segments
- **Batch Processing** — Split all oversized pages in library at once

#### Metadata Plugins
- **MangaDex Metadata Plugin** — Multi-language titles, author/artist, genres, cover art
- **AniList Metadata Plugin** — GraphQL-based metadata with configurable title type

#### Chapter URL Tracking
- **Download History** — Track all downloaded chapter URLs in database
- **Import from gallery-dl** — Automatic import of `.chapter-urls.json` files
- **Metadata Tracking** — Store volume, language, scanlation group info

#### API Endpoints
- `GET/POST/DELETE /api/v1/downloads` — Download queue management
- `DELETE /api/v1/downloads/clear/*` — Clear completed/failed/cancelled
- `GET /api/v1/downloads/progress` — SSE progress stream
- `GET/PUT /api/v1/downloads/follow-config` — Follow list configuration
- `GET /api/v1/media-management/oversized-pages` — List oversized pages
- `POST /api/v1/media-management/oversized-pages/split/*` — Split pages
- `POST /api/v1/tachiyomi/import` — Import Tachiyomi backup
- `GET /api/v1/health` — System health check

#### Infrastructure
- `GalleryDlWrapper` — gallery-dl process management
- `DownloadExecutor` — Download queue processing
- `DownloadScheduler` — Background scheduled tasks
- `ChapterUrlImporter` — Import URLs from gallery-dl JSON
- `TachiyomiImporter` — Import from Tachiyomi backups
- `PageSplitter` / `ImageSplitter` — Page splitting
- `MangaDexRateLimiter` — API rate limiting
- `MangaDexMetadataProvider` / `AniListMetadataProvider` — Metadata fetching
- WebSocket + SSE progress handlers
- Chapter URL DAO for database persistence
