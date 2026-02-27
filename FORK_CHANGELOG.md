# Fork Changelog

All notable changes specific to this fork are documented here.

For upstream Komga changes, see [CHANGELOG.md](CHANGELOG.md).

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
