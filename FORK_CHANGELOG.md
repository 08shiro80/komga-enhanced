# Fork Changelog

All notable changes specific to this fork are documented here.

For upstream Komga changes, see [CHANGELOG.md](CHANGELOG.md).

---

## [0.1.5.1] - 2026-06-06

### 2026-08-09

**Fixes**
- **Webtoons downloaded a single `None.jpg` per episode instead of the full strip:** the webtoons site config used the `{page}` filename field, which that extractor doesn't provide (it numbers images via `num`) — so all ~60+ slices of an episode rendered as `None.jpg` and overwrote each other, leaving one page. Confirmed against gallery-dl directly (an episode yields 67 images with `num` 1…67); filename is now `{num:>03}.{extension}` → `001.jpg`…`067.jpg`.
- **Deleting a book now clears its chapter URLs for _all_ sources, not just MangaDex:** a deleted (e.g. broken single-page) chapter is re-fetched by the follow-check instead of being skipped as already-present. Blacklist still prevents re-download.

#### Modified / new files
| File | Change |
|------|--------|
| `infrastructure/download/GalleryDlProcess.kt` | webtoons `filename` `{page}` → `{num}`. |
| `interfaces/api/rest/BookController.kt` | `deleteBookFile` clears `CHAPTER_URL` for all sources (was MangaDex-only). |

### 2026-08-08

**Upstream merge — base 1.24.4 → 1.25.0** (`baseVersion` → 1.25.0), adopted selectively:
- **Solid RAR4 support** — dropped the "Solid RAR archives are not supported" (`ERR_1003`) guard in `RarExtractor` and bumped junrar 7.5.5 → 7.6.0. Solid `.cbr` archives now open.
- **Dependency alignment** — Spring Boot 3.5.12 → 3.5.14, jOOQ 3.19.24 → 3.19.32. **Requires `./gradlew jooq-codegen-primary` on the next build.**
- Skipped: Kobo content-restriction enforcement (conflicts with the fork's `KoboController` raw-body proxy; niche), OpenAPI schema flattening (API-doc only), webui dep/i18n/CI bumps.

**Features**
- **Link a follow URL to a specific series** (issue #40): a follow can now be tied to a Komga series (`FOLLOW.SERIES_ID`) — it then downloads **straight into that series**, deterministically, instead of relying on URL↔metadata-link matching. The series 3-dot menu gains **"Download sources…"** → a dialog to list/add/remove/toggle the URLs linked to that series. On series delete the link falls back to null so the follow survives library-wide.
- **Follow list — auto-name + batch delete:** adding a single follow without a name now resolves the title the way the downloader does (MangaDex API / gallery-dl `--simulate`, URL-derived fallback) instead of leaving `(no name)`; rows are multi-selectable with a `Delete (n)` batch action.
- **Single-page chapter check** on `/media-management/analysis`: a section listing books whose archive holds only one image (`PAGE_COUNT = 1`) — a safety net for chapters that downloaded as a single frame. Per-row 0/1 **Ignore** toggle plus **multi-select for bulk delete** (removes the CBZ from disk) **or bulk ignore**; ignored rows drop off unless "Show ignored" is on.

**Fixes**
- **Webtoons folder naming** (issue #40): the URL-derived fallback title (used when the source gives no title) took the last path segment, which for webtoons is always `list` → every series became `webtoons - list` and collided in one folder. `deriveTitleFromUrl` now skips trailing generic (`list`/`home`/`chapter`/…) and numeric segments, drops the site-name prefix, and title-cases the manga slug → `Kubera` (folder **and** series.json).
- **`SQLITE_BUSY_SNAPSHOT` no longer strands books in UNKNOWN:** `AnalyzeBook` could fail with this WAL stale-snapshot error (extended code 517) that the task retry didn't recognise (it matched only the primary `SQLITE_BUSY` enum) → the book stayed `UNKNOWN` and its metadata refresh was skipped, showing a wrong or missing chapter number. The retry now matches the error message too, so the task completes.

#### Modified / new files
| File | Change |
|------|--------|
| `RarExtractor.kt` · `build.gradle.kts` · `gradle/libs.versions.toml` · `gradle.properties` | upstream 1.25.0: solid-RAR, junrar/springboot/jooq bumps, version → 1.25.0. |
| `V20260608000000__follow_list.sql` · `domain/model/Follow.kt` · `FollowRepository.kt` · `infrastructure/jooq/main/FollowDao.kt` | `SERIES_ID`; `findAllBySeriesId`, `clearSeriesId`, `deleteByIds`. |
| `domain/service/FollowLifecycle.kt` | `add(seriesId)` + blank-title auto-resolve, `getBySeries`, `deleteBatch`, `checkNow` series override, `@EventListener onSeriesDeleted → clearSeriesId`. |
| `interfaces/api/rest/DownloadController.kt` · `dto/DownloadDto.kt` | `seriesId` in Follow(Creation)Dto, `GET follows/by-series/{seriesId}`, `DELETE follows/{libraryId}/batch`. |
| `V20260809000000__single_page_book_ignore.sql` · `SinglePageBookCandidate.kt` · `SinglePageBookDto.kt` · `SinglePageBookIgnoreRepository.kt`/`Dao` · `SinglePageBooksController.kt` · `MediaRepository.kt` · `MediaDao.kt` | single-page check: candidate query + 0/1 ignore store. |
| `infrastructure/download/GalleryDlWrapper.kt` | `deriveTitleFromUrl` skips generic/numeric path segments. |
| `application/tasks/TaskHandler.kt` | `isSqliteBusy` matches `SQLITE_BUSY_SNAPSHOT` via message. |
| `komga-webui` | `DownloadSourcesDialog.vue` (new) + `SeriesActionsMenu.vue`/`store.ts`/`ReusableDialogs.vue`/`komga-follows.*` (series-linked follows), `MediaAnalysis.vue` (single-page section), `DownloadDashboard.vue` (batch delete). |

### Fix: chapter progress in non-MangaDex single download — no more >100 % (2026-06-16)

The non-MangaDex single-download path derived live progress from gallery-dl's stdout file paths (distinct parent dirs) while `filesDownloaded` started at the count of CBZs already in the folder. On a multi-source resume — existing MangaDex chapters in the folder + one new chapter from another source whose `-j --simulate` lists only its own chapters — this showed e.g. "15 / 1" (1500 %). Progress now counts the `[komga] chapter-complete` markers the gallery-dl `komga` postprocessor prints per finished chapter (0-based, `total = max(sourceChapters, done)`), so it reflects the current operation and never exceeds 100 %. `filesDownloaded` no longer drives the single-download display; the final result still counts CBZs on disk.

#### Modified / new files
| File | Change |
|------|--------|
| `infrastructure/download/GalleryDlWrapper.kt` | single-download progress counts `[komga] chapter-complete` markers (0-based) instead of stdout parent-dir guessing; dropped the now-unused `filesDownloaded.set(existingCbzCountAtStart)`. |
| gallery-dl-fork `postprocessor/komga.py` | emits `[komga] chapter-complete` per finished chapter (`post-after` + `finalize`). |

### Feature: multi-source download — match a download URL against series links and download into that folder (2026-06-15)

A single series can carry several source links in its metadata (e.g. MangaDex + Mangafire for the same manga). When a non-MangaDex download is queued (follow.txt / follow list), the downloader now matches the source URL against `SERIES_METADATA_LINK` and, if a series already holds that exact link, downloads into **that series' folder** instead of creating a new title-named folder. This is the same folder-reuse the MangaDex path already did by UUID, generalised to any source via the stored links — so chapters from multiple sources land in one folder.

- Reuses the existing `findSeriesIdByLinkUrlContaining(libraryId, url)` lookup (no new DB method, no migration).
- Only the non-MangaDex branch changes; MangaDex UUID matching is untouched. Falls back to the title-named folder when no link matches.
- Replaces the removed "Other sources" listing, which was dead weight in this workflow (one manga = one series, so it never had a second series to show and never rendered).

#### Modified / new files
| File | Change |
|------|--------|
| `domain/service/DownloadExecutor.kt` | non-MangaDex branch matches `sourceUrl` against series links → downloads into the existing series folder. |
| `interfaces/api/rest/SeriesController.kt` · `domain/persistence/SeriesMetadataRepository.kt` · `infrastructure/jooq/main/SeriesMetadataDao.kt` | removed `getRelatedSeries` endpoint + `findRelatedSeriesIdsByLinks`. |
| `komga-webui/src/services/komga-series.service.ts` · `views/BrowseSeries.vue` · `locales/en.json` | removed `getRelatedSeries`, "Other sources" section, `browse_series.other_sources` key. |

### Feature: DB-backed follow list + per-library schedule + one-time follow.txt auto-import (2026-06-15)

Replaces the old per-library `follow.txt` text-area on `/downloads` with a DB-backed follow list as the single source of truth. Each library has its own follow list (URL + optional name + enabled toggle) and its own check schedule (interval or fixed daily time); the recurring new-chapter check reads the DB list per library instead of the global `FollowConfig` + `follow.txt` files.

- **Add via dedicated dialog** on the `/downloads` Configuration tab: single URL + optional name, plus a batch text-area (one URL per line). A per-row pencil opens an Edit-name dialog. (Was an inline text-area; moved to a dialog to avoid crowding.)
- **Per-library schedule card** (`GET/PUT /api/v1/downloads/follows/{libraryId}/schedule`), driven by `FollowScheduleLifecycle` (`@EventListener(ApplicationReadyEvent)`; interval or `fixed_time` cron).
- **One-time auto-import on the first start after the update:** `FollowLifecycle.autoImportFollowTxtOnFirstStart()` imports each library's existing `follow.txt` into the DB list, but only for libraries that have no follows yet — effectively once, and it never re-adds deleted entries. No marker file, no extra dependency; `follow.txt` is left untouched.
- **Manual re-import** stays available as a schema-driven `/settings/fixes` card (`import-follow-txt`, via `FixRegistry`, new `library` param type).
- The MangaDex-search Follow toggle + `refreshFollowedUuids` now read/write the DB list via `$komgaFollows` instead of `follow.txt`; "Sync to MangaDex" stays per-library. The old `follow-txt` read/write/check-now REST endpoints were removed (no remaining consumers); `sync-to-mangadex` moved under `follows/`.

### Change: Komga no longer injects ComicInfo.xml during download — gallery-dl's `komga` postprocessor does (2026-06-15)

The download loop previously back-filled `ComicInfo.xml` for any chapter CBZ that lacked it. In practice that only ever fired for pre-existing files (gallery-dl's `komga` postprocessor already writes ComicInfo for fresh downloads) and, due to number-only file matching across scanlation groups, could rewrite an unrelated older chapter with the wrong metadata. The automatic injection was removed; the manual "Re-inject ComicInfo" maintenance action (repair path) is unchanged, `chapterUrl` tracking is kept, and the now-unused `ComicInfoGenerator.hasComicInfoXml` was dropped.

### Fix: CbzSafeWriter could not replace a CBZ held open by the same process (Windows / CIFS) (2026-06-15)

`ComicInfoGenerator` and `DownloadExecutor` kept a `ZipFile` read handle open on the target CBZ across `CbzSafeWriter.safelyReplace`, so `Files.move(target → .bak)` failed with "used by another process" on Windows and on CIFS/SMB mounts, producing repeated retry warnings. `safelyReplace` gained an optional `releaseTarget` callback, invoked after the tmp file is written and verified but before the swap; the callers pass `{ zin.close() }`. Atomic swap + verify + rollback are unchanged. `BookPageEditor`/`PageSplitter` read only inside the write lambda and were untouched.

### Fix: ChapterMatcher crashed reading a STORED CBZ with a data descriptor (2026-06-15)

`ChapterMatcher.extractChapterUrlsFromCbzFiles` (the resume's embedded chapter-URL reader) used `ZipInputStream`, which throws `only DEFLATED entries can have EXT descriptor` on STORED entries written with a data descriptor (Komga sets `compression: store`). It now uses `java.util.zip.ZipFile` (central-directory based), which reads such archives fine; otherwise the chapter URL went unread and the chapter could be re-downloaded.

### Fix: FlareSolverr was not applied to gallery-dl metadata/simulate calls (2026-06-15)

Only the main download config carried the `flaresolverr` option; the `-j --simulate` info / chapter-mapping config (`createInfoConfigFile`) did not. On Cloudflare-protected sites (e.g. utoon.net) those calls returned `403 Forbidden`, so chapter detection failed ("0 chapters", URL-derived title) and an already-complete chapter was reported as a failed download. `flaresolverr_url` is now passed to the info/simulate config as well.

### Improvement: gallery-dl's output surfaced in the download error (2026-06-15)

`GalleryDlWrapper` now puts gallery-dl's stderr (or, when empty, the stdout tail) into the `GalleryDlException` message instead of a bare "exit code N:", so the real cause appears in `komga.log`.

#### Modified / new files (2026-06-15)
| File | Change |
|------|--------|
| `domain/model/Follow.kt`, `FollowSchedule.kt` · `domain/persistence/FollowRepository.kt`, `FollowScheduleRepository.kt` · `infrastructure/jooq/main/FollowDao.kt`, `FollowScheduleDao.kt` · flyway `V20260608000000__follow_list.sql`, `V20260615000000__follow_schedule.sql` | DB-backed follow list + per-library schedule (model / persistence / migrations). |
| `domain/service/FollowLifecycle.kt` | CRUD + `checkNow`, `importFromFollowTxt`, one-time `autoImportFollowTxtOnFirstStart`. |
| `domain/service/FollowScheduleLifecycle.kt` | Per-library scheduler (`ApplicationReadyEvent`). |
| `interfaces/api/rest/DownloadController.kt` · `dto/DownloadDto.kt` | Follow + schedule + `import-follow-txt` endpoints; removed `follow-txt` read/write/check-now + `FollowTxt*Dto`; `sync-to-mangadex` moved under `follows/`. |
| `domain/service/LibraryLifecycle.kt` · `infrastructure/download/MangaDexSubscriptionSyncer.kt` · `domain/service/TachiyomiImporter.kt` | FOLLOW cleanup on library delete; DB-follow reads instead of `follow.txt`. |
| `infrastructure/maintenance/FixRegistry.kt` | `import-follow-txt` migration card (`library` param type). |
| `infrastructure/util/CbzSafeWriter.kt` | `releaseTarget` callback before the move. |
| `infrastructure/download/ComicInfoGenerator.kt` · `domain/service/DownloadExecutor.kt` | Pass `releaseTarget = { zin.close() }`; drop unused `hasComicInfoXml`. |
| `infrastructure/download/GalleryDlWrapper.kt` | Remove download-time ComicInfo injection; surface gallery-dl output in the exception; pass `flaresolverr_url` to the info config. |
| `infrastructure/download/GalleryDlProcess.kt` | `createInfoConfigFile` accepts/sets `flaresolverr`. |
| `infrastructure/download/ChapterMatcher.kt` | `ZipInputStream` → `ZipFile` (STORED+EXT CBZ). |
| `komga-webui/src/views/DownloadDashboard.vue`, `HomeView.vue`, `SettingsFixes.vue` · `services/komga-follows.service.ts`, `types/komga-follows.ts`, `plugins/komga-follows.plugin.ts`, `main.ts` | DB follow-list UI (Add/Edit dialogs, schedule card), Fixes `library`-param renderer + import card, sidebar migration marker. |

### Feature: GigaViewer (tonarinoyj) scrambled-page unscrambling enabled in the gallery-dl config

Cherry-picked from sfai05 (`sfai05/gallery-dl-komga` postprocessor + the wrapper wiring in `sfai05/komga-enhanced`). The gallery-dl-fork ships a `gigaviewer_unscramble` postprocessor that reverses the 4×4 block transposition Shueisha's GigaViewer applies to some pages (flagged `_scrambled` by the `tonarinoyj` extractor). `GalleryDlProcess.createTempConfigFile` now lists it as the first postprocessor — before `zip` — with `condition = _scrambled`, so scrambled tonarinoyj pages are descrambled before they are written into the CBZ. The postprocessor self-guards on the `_scrambled` flag, so it is a no-op for every other source. Pillow (the postprocessor's only runtime dependency) is installed into the Docker image alongside gallery-dl.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlProcess.kt` | `gigaviewer_unscramble` postprocessor added before `zip` in the generated config (`condition = _scrambled`). |
| `komga/docker/Dockerfile.tpl` | `pip3 install Pillow` added after the gallery-dl install (runtime dependency for the unscramble postprocessor). |

### Fix: `BookPageEditor` produced empty CBZ files when semantic verification ran after the file swap

`removeHashedPages` and `removePagesByNumber` rewrote a CBZ via `CbzSafeWriter.safelyReplace`, then on the **replaced** target file ran `fileSystemScanner.scanFile` + `bookAnalyzer.analyze` and checked that all pages-to-keep were still present. `CbzSafeWriter`'s built-in verification (`verifyFile`) only asserts the archive is structurally readable with at least one entry — it does not understand what an "image page" is. So an output containing only `ComicInfo.xml` (~1.6 KB) and no images passed structural verification, the move-rotate swap completed, then the analyzer reported "Book does not contain any pages", a `BookConversionException` was thrown — and the original was already replaced with the 1.6 KB husk.

This was observed on 2026-06-08: a wave of `FindDuplicatePagesToDelete` tasks ran auto-removal across several series, and for at least five books the swap completed while `pagesToKeep` was effectively empty (cause TBD — possibly page-hash collisions or stale auto-delete page hashes after a re-analyze). Each affected file dropped from several MB to 1669 bytes.

Fix: `CbzSafeWriter.safelyReplace` now accepts an optional `verifyContent: (Path) -> Unit` callback that runs **after structural ZIP verification but before any move-rotate**. `BookPageEditor.removeHashedPages` and `removePagesByNumber` pass the analyze + pages-contained-in-output + files-contained-in-output checks into this callback, so a semantically broken rewrite is now caught while the original target is still untouched — the `tmp` file is deleted and the original CBZ stays as-is. The `BookConversionException` is still thrown so the book is added to `failedPageRemoval` and the user sees the error, but no data is destroyed.

The two callsites also drop the now-redundant post-swap analyze pass and reuse the `Media` already produced by the in-flight verification (`verifiedMedia`), saving one full archive analyze per page-removal task.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/util/CbzSafeWriter.kt` | `safelyReplace` gains an optional `verifyContent: ((Path) -> Unit)?` parameter; invoked after `verifyFile(tmp)` and before the move-rotate sequence. On throw, `tmp` is deleted and the original target is never moved. |
| `komga/src/main/kotlin/org/gotson/komga/domain/service/BookPageEditor.kt` | `removeHashedPages` and `removePagesByNumber` pass the existing semantic checks into `verifyContent`. The post-swap `bookAnalyzer.analyze` is removed; instead the in-flight verified `Media` is captured and reused for the DB update. A final `fileSystemScanner.scanFile(book.path)` still runs after the swap to pick up the new `fileLastModified` / `fileSize`. |

---

### Fix: `libarchive` native library not loadable in Docker (`libarchive.so` symlink)

Same root cause as the WebP / HEIF / JXL symlinks in the 0.1.5.1 NightMonkeys fix below: `debian:bookworm-slim` ships `libarchive13` as `libarchive.so.13` only, and `System.loadLibrary("archive")` (used by `c.github.gotson.nightcompress.Archive` for `CBR` / `RAR` / `7z` extraction) searches for the unversioned `libarchive.so` and fails with `no archive in java.library.path`. The reporter on #32 still saw this warning after the locale fix.

Fix: extend the per-arch symlink block in `Dockerfile.tpl` with `ln -sf libarchive.so.13 libarchive.so`. Same pattern, same multiarch dir, same `LD_LIBRARY_PATH` resolution.

#### Modified files
| File | Change |
|------|--------|
| `komga/docker/Dockerfile.tpl` | Each of the per-arch `RUN` blocks gains `ln -sf libarchive.so.13 libarchive.so` next to the existing webp / heif / jxl symlinks. |

---

### Fix: Non-roman series paths crash in Docker (selective `locale-gen` for common UTF-8 locales)

Slimming the Docker image in 0.1.4.3 silently dropped the locale setup: `debian:bookworm-slim` only ships the glibc built-ins (`C` / `POSIX` / `C.UTF-8`) and none of the `ja_JP.UTF-8` / `ko_KR.UTF-8` / `zh_*.UTF-8` archives. As long as `LANG=C.UTF-8` is honoured the JVM can in principle encode UTF-8 paths, but on hosts like Synology DSM the container env is partially overridden by the host (e.g. with `POSIX` or with a locale that is not actually generated inside the image), and `sun.jnu.encoding` falls back to `ANSI_X3.4-1968` (ASCII). `UnixPath.encode()` then throws `InvalidPathException: Malformed input or input contains unmappable characters` for any path containing Korean, Japanese or Chinese characters. The visible regression was `Series.path` (`url.toURI().toPath()`), called from `ChapterUrlImporter.extractUuidFromSeriesJson` during `scanRootFolder` — the warning was logged for every non-roman series and the chapter URL import was skipped for those series (see #32).

Fix: install the `locales` package and selectively run `locale-gen` for the eleven UTF-8 locales most likely to be set by hosts (`en_US`, `de_DE`, `ja_JP`, `ko_KR`, `zh_CN`, `zh_TW`, `fr_FR`, `es_ES`, `it_IT`, `pt_BR`, `ru_RU`). Each generated locale costs ~1 MB unpacked, so the total layer delta is ~15 MB instead of the ~220 MB that `locales-all` would add. Glibc now finds a valid UTF-8 locale regardless of the host env override and `sun.jnu.encoding` reliably initialises to `UTF-8`.

#### Modified files
| File | Change |
|------|--------|
| `komga/docker/Dockerfile.tpl` | `locales` added to the apt-install line (after `zip`); a `sed` step un-comments the eleven UTF-8 locale lines in `/etc/locale.gen`, followed by `locale-gen`. |

---

### Performance: NightMonkeys native image plugins now actually load in Docker (`libwebp` / `libheif` / `libjxl` symlinks)

Komga registers two WebP `ImageReaderSpi` providers at startup: TwelveMonkeys' pure-Java decoder (`com.twelvemonkeys.imageio:imageio-webp`) and NightMonkeys' Panama-FFI wrapper around the native libwebp (`com.github.gotson.nightmonkeys:imageio-webp`). `ImageConverter.chooseWebpReader` then deregisters every WebP provider except NightMonkeys when both are present — the native path is several times faster on real-world page decodes and avoids allocating a full `width × height × 4`-byte `BufferedImage` in the Java heap for every frame. The same architecture applies to the HEIF and JPEG-XL providers (`com.github.gotson.nightmonkeys:imageio-heif`, `imageio-jxl`).

In the 0.1.5 Docker image none of the three NightMonkeys plugins could actually load. Debian bookworm's runtime packages (`libwebp7`, `libheif1`, `libjxl0.7`) ship only the versioned filenames (`libwebp.so.7`, `libheif.so.1`, `libjxl.so.0.7`); the unversioned `libwebp.so` / `libheif.so` / `libjxl.so` symlinks only come with the corresponding `-dev` packages. `System.loadLibrary("webp")` searches for the unversioned name and failed: `Could not load libwebp, plugin will be disabled. no webp in java.library.path: /usr/lib:/usr/lib/x86_64-linux-gnu:/usr/java/packages/lib:/usr/lib64:/lib64:/lib:/usr/lib`. NightMonkeys deregistered itself, `chooseWebpReader`'s `providers.size > 1` branch never fired, and every WebP decode silently fell back to TwelveMonkeys' pure-Java path. For libraries with WebP-heavy series (most webtoons, modern manga rip dumps) this meant slow thumbnails and heap pressure under concurrent decodes. `libjxl` was not installed in the image at all, so NightMonkeys' JPEG-XL plugin never had a chance.

Three things changed in `Dockerfile.tpl`:

1. `libjxl0.7` is now apt-installed alongside the existing `libheif1` / `libwebp7`. The `0.7` suffix is the binary-package name on Debian bookworm; the package also ships `libjxl_threads.so.0.7` for threaded JXL decode, so no second install is needed.
2. Each arch-specific stage now creates the three missing unversioned symlinks in one `RUN` block: `cd /usr/lib/<multiarch> && ln -sf libwebp.so.7 libwebp.so && ln -sf libheif.so.1 libheif.so && ln -sf libjxl.so.0.7 libjxl.so`. Relative targets resolve inside the same multiarch dir; the `LD_LIBRARY_PATH` entry already declared two lines above (`ENV LD_LIBRARY_PATH=...`) picks them up.
3. The change lives in the per-arch stages (`build-amd64`, `build-arm64`), not in the common `build-linux` stage, so each `ln -sf` only ever runs on its target architecture and there is no risk of cross-arch library names.

The exact `libjxl0.7` / `libwebp7` / `libheif1` package contents were verified against `packages.debian.org/bookworm/<pkg>/filelist` before pinning the versioned `.so` targets; the symlinks are stable across bookworm point releases (a bookworm-to-trixie base bump would invalidate `libjxl.so.0.7` → `libjxl.so.0.11`, but `libwebp.so.7` and `libheif.so.1` survive).

Expected effect: native WebP / HEIF / JXL decode whenever the plugin is exercised, smaller Java heap for image-heavy operations (thumbnail generation, page splitter, oversized-pages scan), and `Supported read formats` in the startup log now includes `JPEG-XL` and `HEIF` instead of just JPEG/PNG/WebP.

#### Modified files
| File | Change |
|------|--------|
| `komga/docker/Dockerfile.tpl` | apt-install line: `libjxl0.7` added between `libwebp7` and `libarchive13`. Each `FROM build-linux AS build-{amd64,arm64}` stage gains a `RUN cd /usr/lib/<multiarch> && ln -sf libwebp.so.7 libwebp.so && ln -sf libheif.so.1 libheif.so && ln -sf libjxl.so.0.7 libjxl.so` block right after the `ENV LD_LIBRARY_PATH=...` line. |
| `gradle.properties` | `forkVersion=0.1.5` → `0.1.5.1`, `version=1.24.4-fork-0.1.5` → `1.24.4-fork-0.1.5.1`. |

---

## [0.1.5] - 2026-06-06

### Fix: PageSplitter race condition destroyed CBZ files under concurrent `/split-all` calls

`OversizedPagesController.splitAllTallPages` had no concurrency guard — two HTTP requests (browser double-click on `Split All`, or a Tomcat-side retry after a slow first request) entered `splitTallPages` in parallel for the same book. The splitter writes through three filesystem steps:

1. `Files.copy(book.path, backupPath, REPLACE_EXISTING)` → creates `<name>_backup.cbz`.
2. Builds `<name>_split.cbz` from page bytes.
3. `book.path.deleteIfExists()` → `Files.move(tempPath, book.path, REPLACE_EXISTING)` → `backupPath.deleteIfExists()`.

Two threads interleaving over these steps produce three different data-loss paths:
- Thread A finishes step 3 (move + backup cleanup) → Thread B is mid-write on its own `<name>_split.cbz`, hits `book.path.deleteIfExists()` against A's freshly-moved file and `Files.move` fails because A's backup is gone → catch block can't restore → **file vanishes**.
- Thread A's `<name>_split.cbz` move succeeds and Thread B's later `Files.copy(book.path, backupPath, REPLACE_EXISTING)` overwrites the backup with the **already-split** content; if B then fails partway, restore brings back the split version, not the original.
- Both threads write to the same `<name>_split.cbz` → inflater sees mismatched deflate block lengths → `java.util.zip.ZipException: invalid stored block lengths` → file written successfully looks like a CBZ but cannot be unzipped → Komga shows "media type is not supported" / "Unknown error while getting book's entries".

Concrete fallout (2026-06-04 user incident): ~360 `Failed to split pages in book` errors in `komga.log` over a single run; 710 CBZ paths recorded with `NoSuchFileException` and at least 6 with `Error reading Zip content` across the user's library. Affected series included `Tomb Raider King`, `Magic Emperor`, `Moe and Friends`, `ReadManga.Today`, `White Cloud Pavilion`, `Paragon Scans`, `White Devil scans`, `Akuzenai Arts`, `Mangasushi`, `ManhwaFreak`, `Roselia Scanlations`. Many of the lost chapters are from dropped scanlations not re-downloadable from any source.

A per-book lock is now held for the entire `splitTallPages` invocation:

- `PageSplitter.companion` holds `ConcurrentHashMap<String, Any>` keyed by `bookId`. Every entry into `splitTallPages` does `synchronized(bookLocks.computeIfAbsent(book.id) { Any() })` before any filesystem operation. Concurrent calls for the **same** book queue and run serially; calls for different books still run in parallel.
- The lock is intentionally a **wait**, not a fail-fast (no 409, no early `SplitResult(success=false)`). When a user selects 150 pages in the UI, `splitSelected` iterates books in a sequential `await` loop — backend lock acquisition is instant in that case. If a retry or a second `Split All` arrives it queues behind the active job instead of starting a second iteration that could touch the same book.
- The pre-existing exception handler that restores from `<name>_backup.cbz` on any mid-write failure is unchanged.

Plus frontend disable: both top-level `Split Selected` / `Split All` buttons in `OversizedPages.vue` and the final `Split All` button inside the confirmation dialog now carry `:disabled="splitting"` (in addition to the existing `:loading="splitting"`, which on Vuetify 2 does not disable the button). Double-click and click-during-job no longer fire a second HTTP request.

The split itself is unchanged. There is no migration; existing damaged CBZs are not recoverable by this fix.

### Fix: Oversized-pages listing made N×3 single-row DB queries per request, fronted timed out as 500s

`GET /api/v1/media-management/oversized-pages` walked `bookRepository.findAll()` and for every book issued three round-trips: `mediaRepository.findByIdOrNull` (pages join → MEDIA_PAGE), `seriesRepository.findByIdOrNull`, `seriesMetadataRepository.findByIdOrNull` (latter expands internally to five sub-queries — genres, tags, sharing labels, links, alternate titles). For a 3000-book library that's ~21000 individual SQL round-trips per filter/sort/page click. The endpoint also re-ran the entire scan for every search keystroke and pagination change since there was no caching. Browsers hitting it during a parallel `splitAllTallPages` (which itself occupies Tomcat exec threads with its own `findAll` walk) timed out at the Axios layer and surfaced in the UI as "An error occurred while trying to retrieve oversized pages" / `Request failed with status code 500`.

A new `MediaRepository.findAllOversizedPageCandidates(minDimension)` performs a single JOIN over `MEDIA_PAGE → BOOK ← SERIES ← SERIES_METADATA`, pre-filtered by `WIDTH >= MIN_VALID_DIMENSION AND HEIGHT >= MIN_VALID_DIMENSION`. Each row already carries `bookId`/`bookName`/`seriesId`/`seriesName`/`seriesTitle`/`pageNumber` (mapped from the 0-based `MEDIA_PAGE.NUMBER` to the 1-based UI page number) plus dimensions, size, and mediaType. `OversizedPagesController.getOversizedPages` consumes that flat list and does the ratio / wide / search / ignored filtering and pagination in memory — same semantics as before, but the database side collapses from O(books × 3) round-trips to one statement. `seriesRepository` and `seriesMetadataRepository` are removed from the controller constructor since they were only used by this endpoint.

### Fix: `Split All` ignored the active UI filter, processed the whole library

`POST /api/v1/media-management/oversized-pages/split-all` walked `bookRepository.findAll()` and only honoured `mode` + `maxHeight`/`maxRatio` from the request body. The UI's `search`, `includeIgnored` and `minRatio`/`minWidth`/`minHeight` filters that scoped the visible listing (`getOversizedPages`) were never sent and never read. Clicking `Split All` after filtering for one series ran the split across the entire library — the same iteration that triggered the race in the fix above, but also a UX trap: users assumed they were splitting what they could see.

`SplitRequestDto` now carries `search`, `includeIgnored`, `minWidth`, `minHeight`, `minRatio`. `splitAllTallPages` runs the same candidate-filtering pipeline as the listing endpoint — one `findAllOversizedPageCandidates` bulk query, then ratio/wide/mode/search/ignored filtering in memory — and builds a `Map<bookId, Set<pageNumber>>` of pages that actually match the current view. It then calls `pageSplitter.splitTallPages(book, …, pageNumbers = matchingPages)` once per book. The explicit `pageNumbers` set short-circuits the per-page ratio re-check inside `PageSplitter` (see `PageSplitter.kt:88`: *"If an explicit page set was provided, only split those pages"*) so what gets split is exactly what was on screen, no more no less.

`splitAll()` in `OversizedPages.vue` now sends `search: this.searchQuery`, `includeIgnored: this.includeIgnored`, `minRatio: this.detectRatio` alongside `mode`/`maxRatio`.

#### Modified files (covers the three oversized-pages fixes above)
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/domain/service/PageSplitter.kt` | `companion` holds `ConcurrentHashMap<String, Any>` keyed by `bookId`. `splitTallPages` wraps the filesystem-touching body in `synchronized(bookLocks.computeIfAbsent(book.id) { Any() })`; the body itself moved into a private `runSplit` helper. `java.util.concurrent.ConcurrentHashMap` import added. |
| `komga/src/main/kotlin/org/gotson/komga/domain/persistence/MediaRepository.kt` | New `findAllOversizedPageCandidates(minDimension: Int): Collection<OversizedPageCandidate>`. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/jooq/main/MediaDao.kt` | Implementation: single JOIN over `MEDIA_PAGE → BOOK ← SERIES ← SERIES_METADATA` with `WIDTH/HEIGHT >= minDimension` predicate, maps to `OversizedPageCandidate` rows. |
| `komga/src/main/kotlin/org/gotson/komga/domain/model/OversizedPageCandidate.kt` | New flat data class carrying the joined columns (bookId/bookName/seriesId/seriesName/seriesTitle/pageNumber/width/height/fileSize/mediaType). |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/dto/OversizedPageDto.kt` | `SplitRequestDto` gains 5 optional fields: `search`, `includeIgnored`, `minWidth`, `minHeight`, `minRatio`. |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/OversizedPagesController.kt` | `getOversizedPages` now calls `mediaRepository.findAllOversizedPageCandidates(PageSplitter.MIN_VALID_DIMENSION)` and iterates the flat list directly. `splitAllTallPages` rewritten to run the same candidate-filtering pipeline (bulk query → ratio/wide/mode/search/ignored filter → `Map<bookId, Set<pageNumber>>`) and call `splitTallPages` once per book with the explicit `pageNumbers` set; old `bookRepository.findAll()` + per-book `media.pages.any` scan removed. `seriesRepository` and `seriesMetadataRepository` constructor parameters removed (no other callers). |
| `komga-webui/src/views/OversizedPages.vue` | Three buttons gain `:disabled="splitting"`: top `Split Selected`, top `Split All`, confirm-dialog `Split All`; confirm-dialog button additionally gains `:loading="splitting"`. `splitAll()` POST body extended with `search`/`includeIgnored`/`minRatio` from current UI state. |

### Fix: Re-inject ComicInfo fix overwrote non-MangaDex ComicInfo.xml

`repairMissingComicInfo` (`Settings → Fixes → Re-inject ComicInfo.xml`) walks every series folder that is MangaDex-tracked — both UUID-named directories and name-based directories whose `series.json` carries `comicid`/`mangadex.org/title/…` — and re-writes the ComicInfo.xml of every CBZ inside, matching files by chapter number from the filename. With `force=true` the only guard was the ZIP-comment presence check, which a non-MangaDex source CBZ does not satisfy. CBZs that the user had hand-copied from another source (mangabuddy, cubari, Mihon exports) into a MangaDex-tracked series folder were silently rewritten — their original `<Series>`, `<Number>`, `<Web>` and Mihon `<ty:…>` tags were lost, and downstream metadata refreshes then carried the MangaDex chapter UUID instead of the source URL.

Re-inject now reads `<Web>` from the existing ComicInfo before doing anything else. If `<Web>` is present and does not contain `mangadex.org`, the file is left alone and counted as `skipped` — even under `force=true`. The check runs ahead of the ZIP-comment short-circuit so non-MangaDex CBZs are protected against accidental force-runs. CBZs with no ComicInfo or with a MangaDex `<Web>` continue to be repaired as before.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/ComicInfoGenerator.kt` | New `readWebUrl(cbzFile)` — same shape as `readChapterNumber`, extracts `<Web>` via regex with `DOT_MATCHES_ALL`. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlWrapper.kt` | `repairMissingComicInfo` calls `readWebUrl` first and `continue`s with `skipped++` when the URL is present and not `mangadex.org`. |

### Fix: PageSplitter parallel image decodes exhausted JVM heap

The per-book lock added by the race-condition fix above prevented two threads from corrupting the *same* book, but did not cap simultaneous splits across *different* books. A user-triggered `Split All` run kicked off 10+ Tomcat exec threads, each holding its own page in memory as a `BufferedImage` (width × height × 4 bytes). Webtoon pages routinely run into hundreds of MB per decode; the heap exhausted within seconds and the OOM cascade killed the JVM — every stack trace ended in `JPEGImageReader.read` → `DataBufferByte.<init>`.

A global `Semaphore(MAX_PARALLEL_SPLITS = 2)` in `PageSplitter.companion` now caps simultaneous splits across all books. `splitTallPages` acquires it around the per-book `synchronized(lock)` block, so concurrent splits on the *same* book still queue on the per-book lock (no behaviour change), but concurrent splits on *different* books are throttled to 2 — enough to keep the pipeline moving without pushing the heap over the edge. The acquire is a blocking wait, not a tryAcquire, so a Tomcat thread that would have started the third split sits idle until one of the active two finishes; this matches the existing UX of `splitSelected` which already iterates books sequentially.

### Fix: SQLite RO pool starved under concurrent reads, now tracks `taskPoolSize`

When the JVM came back up from the OOM described above, the browser frontend immediately reloaded every visible view, queueing dozens of simultaneous read-requests against `SqliteMainPoolRO`. Hikari ran the pool at `total=1, active=1, idle=0` with every waiting request timing out at 30 s. From the user perspective the server was unresponsive even though it was back up.

Two root causes stacked:

1. `KomgaProperties.Database.maxPoolSize` defaults to `1` — even with a host that gives `availableProcessors() = 8`, the existing `coerceAtMost(maxPoolSize)` clipped the pool back to 1.
2. There was no way to raise the RO pool from the running UI; tuning required editing `application.yml` and restarting.

The RO pool now mirrors the `/settings/server` → "Task threads" setting (`KomgaSettingsProvider.taskPoolSize`). Initial sizing during bean construction stays at `availableProcessors().coerceAtMost(maxPoolSize)` (the existing fallback) because the settings DB isn't readable yet — the pool has to come up before `KomgaSettingsProvider` can be queried. Once Spring fires `ApplicationReadyEvent`, `DataSourcesConfiguration` reads `taskPoolSize` and runtime-resizes the HikariCP pool via `HikariDataSource.maximumPoolSize = N`. A `@EventListener(SettingChangedEvent.TaskPoolSize)` repeats the same resize whenever the user changes the value in the UI, so the running server picks it up without a restart.

Sizing is skipped when `komga.database.poolSize` is set explicitly (user opted out of dynamic sizing) or when `shouldSeparateReadFromWrites()` returns false (RO pool falls back to the RW pool which is locked at 1 by SQLite write semantics anyway). The write pool stays at 1 unconditionally.

`KomgaSettingsProvider` is injected via `ObjectProvider<>` to avoid the circular dependency (the provider reads from `serverSettingsDao`, which depends on the very RW data source built by this configuration).

**Why upstream kept these two pools separate.** `maxPoolSize = 1` was introduced in `76e62414` (Jul 2022, *fix: add configuration to set the database pool size*) together with the optional `poolSize: Int? = null` — at that point SQLite ran without WAL, every read serialized behind every write, and a connection pool larger than 1 deadlocked the JDBC driver. The `1` is historical "fixed pool size", not a deliberate ceiling. Read/write split arrived later in `f9d9139b` (*perf: separate database reads from writes*), but only activates under WAL; the default-1 ceiling stayed for backward compatibility with non-WAL setups. `taskPoolSize` itself landed independently in `9ef319b7` (*feat(api): configure number of task processing threads*) as background-job concurrency at the application layer — semantically separate from DB pooling at the infrastructure layer, so neither commit had a reason to wire them together. In practice the layers are coupled: every task thread eventually issues SQL, and `taskPoolSize=N` against `poolSize=1` queues all N tasks single-file through one connection regardless of WAL. The fork wires them. Per-connection cost (~50KB heap + one WAL snapshot) is negligible, and the RO pool is capped at exactly `taskPoolSize` so the user's "Task threads" slider stays the single knob.

### Fix: ComicInfo inject re-deflated already-compressed page images on every write

`injectComicInfo` and `injectComicInfoWithRetry` rewrote the CBZ by copying every source entry through `ZipOutputStream` at its default DEFLATE level. JPEG, PNG, and WebP page images are already compressed; running them through deflate a second time yields ~0% size reduction (often a few bytes *more* due to deflate framing overhead) at significant CPU cost — measured 3-5× the wall-time of a STORED copy. The slowdown matters most when the Settings → Fixes → "Re-inject ComicInfo.xml" job sweeps the whole library: a full sweep over a sizeable collection ran for hours and blocked other inject work behind the global ZIP-rewrite contention.

Image entries (`.jpg .jpeg .png .webp .gif .avif .heic`) are now written as `ZipEntry.STORED` with `size`, `compressedSize`, and `crc` lifted directly from the source `ZipEntry` in the central directory. ComicInfo.xml and any non-image entries (`series.json`, embedded fonts, ...) continue to be DEFLATED at the default level. No source bytes are re-read or re-hashed; the STORED path is a straight `InputStream.copyTo` once `putNextEntry` has accepted the pre-known CRC.

Failsafe: STORED is unforgiving — if the source central-dir CRC or size is wrong, `ZipOutputStream.closeEntry` throws `ZipException` and the entire output stream is dead. Both inject paths handle that explicitly:

- `injectComicInfo`: refactored into a thin wrapper around a private `writeInjected(useStored: Boolean)`. The outer method runs `writeInjected(useStored=true)` and, on `ZipException`, logs a warning and runs `writeInjected(useStored=false)` against a fresh temp file. The original CBZ is never touched until `Files.move` succeeds on the second pass; if both passes fail the inject is a no-op and the original CBZ is left intact.
- `injectComicInfoWithRetry`: `var useStored = true` lifted to the outer scope of the retry loop. On `ZipException` the flag flips to `false` and the next loop iteration writes DEFLATED. `FileSystemException` keeps its existing exponential sleep; `ZipException` does not consume the retry sleep budget (it's a code-path retry, not a lock-contention retry).

### Feature: ChapterMatcher accepts `Chapter <num>` and `Chapter_<num>` prefixes

`ChapterMatcher` was matching `c<num>` (gallery-dl default template) or `ch. <num>` only. Users who wanted to pick a more human-readable `chapter_naming` template like `Chapter {chapter:>03}` were blocked by the plugin description's hard warning that ChapterMatcher would no longer find the CBZ for post-download ComicInfo injection — the immediate consequence being that the `<Number>` tag never lands in the CBZ, so the resume path (`GalleryDlWrapper.kt:520`, `comicInfoGenerator.readChapterNumber`) cannot tell the chapter is finished and re-downloads it on every restart.

A third regex `chapterNumChapterRegex = ^chapter[\s_]+(\d+(?:\.\d+)?)` is added alongside the C-prefix and Ch-prefix variants. `extractChapterNumberFromFilename`, `extractChapterNumFromFilename`, and `matchesChapterNumber` all probe it (after the existing two) so existing libraries with `c<num>` filenames keep their fast path. The plugin field's WARNING is dropped and the description lists the three accepted prefixes (`c<num>`, `ch. <num>`, `Chapter <num>`).

### Fix: ChapterMatcher dropped letter-suffix chapters (`5.5a`, `5.5b`) causing post-download CBZ matching to fail

Source-side chapter numbers like `5.5a` / `5.5b` (MangaDex convention for split releases of the same decimal chapter — e.g. KittyBlue9 / Aesthethicc scans of `My Wife is from a Thousand Years Ago`) round-tripped through gallery-dl as the literal string `"5.5a"` and produced an on-disk filename `v1 c005.5a [KittyBlue9 Scans].cbz` from the default `c{chapter}` template. Two downstream paths broke:

1. **Post-download CBZ matching** (`GalleryDlWrapper.kt:841`). After each per-chapter `gallery-dl` invocation the wrapper enumerates the destination directory and tries to find the just-written CBZ to inject ComicInfo and run cleanup. The lookup keys are `paddedChapter = chapterMatcher.padChapterNumber(chapterStr)` and the raw `chapterStr`. `padChapterNumber` called `chapterNumStr.toDouble()` which throws `NumberFormatException` on `"5.5a"` — the catch returned the input unchanged, so `paddedChapter == chapterStr == "5.5a"`. `matchesChapterNumber` then probed the candidate name `c005.5a [kittyblue9 scans]` for `startsWith("c5.5a ")` / `startsWith("c5.5a-")` etc. — none matched (`c005.5a` ≠ `c5.5a`). The CBZ was downloaded successfully but the wrapper logged `Could not find CBZ file for chapter 5.5a (expected c5.5a or c5.5a)` (note the doubled echo, a direct symptom of the failed padding) and skipped ComicInfo injection. Resume then could not see a `<Number>` tag and re-downloaded the chapter on the next run.

2. **Resume false-positive skip** (`ChapterMatcher.extractChapterNumberFromFilename`). The three regexes capture-grouped `^c(\d+(?:\.\d+)?)` / `^ch\.?\s*(\d+(?:\.\d+)?)` / `^chapter[\s_]+(\d+(?:\.\d+)?)` — letter suffix is dropped. A library with both `c005.5a` and `c005.5b` extracted as `5.5` twice; the resume `Set<String>` thus contained one entry instead of two, and the next-not-yet-downloaded probe in `GalleryDlWrapper.download` considered MangaDex's `5.5a` AND `5.5b` "already on disk" after only one of them was fetched, skipping the second.

**Fix in `ChapterMatcher.kt`:**

- All three prefix regexes get an optional trailing `[a-z]?` inside the capture group: `^c(\d+(?:\.\d+)?[a-z]?)` etc. Single lowercase letter — matches MangaDex convention (`a`, `b`, `c`) without expanding scope to arbitrary suffixes.
- `padChapterNumber` is rewritten to split the input on a new private regex `chapterNumericSplitRegex = ^(\d+(?:\.\d+)?)([^\d.].*)?$`. The numeric prefix is padded with `String.format("%03d…")` as before; the suffix (`a`, `b`, …) is appended unchanged. Inputs without a leading numeric prefix bypass the try-block entirely instead of throwing — eliminates the spurious `Could not pad chapter number` debug-log path for non-numeric inputs.
- `<Number>5.5a</Number>` flows through Komga's existing BookMetadata parser unchanged (`numberSort = 5.5`, `number = "5.5a"`) — no Komga-side changes needed for sort/display correctness.

**Fix in `GalleryDlWrapper.kt:863`:** the warn message now collapses to `expected c<padded>` when `paddedChapter == chapterStr`, avoiding the misleading doubled-echo that masked this bug for weeks.

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/ChapterMatcher.kt` | Three prefix regexes gain `[a-z]?` suffix capture; `padChapterNumber` splits numeric prefix from alpha suffix, pads only the numeric part; new private `chapterNumericSplitRegex` companion. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlWrapper.kt` | "Could not find CBZ" warn collapses doubled `c$padded or c$chapterStr` echo when both strings are equal. |

### Performance: SQLite pragma set tuned for a 768 MB multi-tab library DB

`application.yml` pins three pragmas per DB that together replace the SQLite embedded-defaults assumed for a single-user single-connection app:

- **`busy-timeout: 30s`** (both `komga.database` and `komga.tasks-db`). Default is 0 ms — any lock-contention surfaces as immediate `SQLITE_BUSY`. With WAL + the dynamic RO pool, lock-contention is short (write-pool=1) but real; 30 s lets the JDBC driver retry transparently instead of bubbling `SQLITE_BUSY` up to `TaskHandler`.
- **`synchronous: NORMAL`** (both DBs). Default is `FULL` — fsync on every transaction commit. `NORMAL` fsyncs only at WAL-checkpoint boundaries, ~3-5× faster on bulk writes (library scan, mass-rehash, Re-inject sweep). The trade-off: on power-loss the last ~1 s of commits may be lost; **no corruption** (WAL guarantees the file stays consistent).
- **`cache_size: 2000`** (`komga.database` only — `tasks-db` is small and doesn't benefit). Positive = pages × `page_size=4096` = 8 MB per connection (default `-2000` = 2 MB). 8 MB holds the working set of the dominant indexes (BOOK, BOOK_METADATA, MEDIA_PAGE, SERIES, SERIES_METADATA) for the lifetime of the connection. At pool size 8 (mirrors `taskPoolSize`, see "SQLite RO pool tracks /settings/server") that totals 64 MB committed — negligible against the JVM's `-Xmx3g`. With the read-pool dynamic, the 2 MB default evicted hot pages within milliseconds and every additional connection re-walked the B-trees from disk on each query burst.

The pragmas are injected by `DataSourcesConfiguration.buildDataSource` as URL parameters on the SQLite JDBC URL. The separator is `?` for the first pragma and `&` for subsequent ones — `buildDataSource` checks whether `databaseProps.file` already contains a `?` (in-memory test DBs use `file:database?mode=memory`) and picks the right separator. Without the check, the in-memory test path produced the invalid URL `file:database?mode=memory?synchronous=NORMAL`, breaking `DataSourcesConfigurationTest > MemoryMode`.

| File | Change |
|------|--------|
| `komga/src/main/resources/application.yml` | `komga.database.busy-timeout: 30s` + `pragmas.synchronous: NORMAL` + `pragmas.cache_size: 2000`. `komga.tasks-db` gets `busy-timeout: 30s` + `pragmas.synchronous: NORMAL` (no `cache_size`). |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/datasource/DataSourcesConfiguration.kt` | `buildDataSource` picks the URL-pragma separator (`?` or `&`) based on whether `databaseProps.file` already contains a `?` — required for the in-memory test DB URL to stay valid when pragmas are appended. |

### Fix: `TaskHandler` propagated `SQLITE_BUSY` to ERROR instead of retrying

`busy-timeout: 30s` (above) handles the common case at the JDBC driver level — the driver waits up to 30 s for the lock to clear. But the timeout is per-statement: a write that *enters* the busy state right at the 30 s ceiling still throws `SQLiteException(SQLITE_BUSY)`, and `TaskHandler.handleTask` previously logged this as a generic task failure and bumped the failure counter, even though the task's logical work hadn't failed at all.

`handleTask` now wraps `runTask(task)` in a retry loop scoped to `SQLiteException` with `resultCode == SQLITE_BUSY`. `MAX_DB_BUSY_RETRIES = 5` with linear backoff (`DB_BUSY_BACKOFF_MS * attempt`, starting at 500 ms — 500 ms, 1 s, 1.5 s, 2 s, 2.5 s). All other exceptions propagate immediately as before. With the 30 s busy-timeout, a single retry should suffice in practice — the loop is defensive for edge cases (concurrent VACUUM, WAL checkpoint stall under heavy load).

The pre-existing `catch (Exception)` blocks *inside* `runTask` were removed: exceptions now propagate up to `handleTask`, which is the single point that decides retry-vs-fail-final. Previously, swallowed exceptions inside `runTask` masked the underlying `SQLITE_BUSY` from the new retry logic.

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/application/tasks/TaskHandler.kt` | `handleTask` wraps `runTask` in a retry loop guarded by `SQLiteException(SQLITE_BUSY)` with linear backoff. `MAX_DB_BUSY_RETRIES = 5`, `DB_BUSY_BACKOFF_MS = 500L` companion constants. Inner `runTask` catches removed so the exception path is centralised. |

### Performance: Docker image layer-split — typical fork-version pulls drop from ~200 MB to ~10-25 MB

`Dockerfile.tpl` previously installed system packages (`apt-get install …`), kepubify, and gallery-dl in a single `RUN` step. `release.yml` passes `ARG GALLERY_DL_REV=${{ github.run_id }}-${{ github.run_attempt }}` so every release-rebuild invalidates the layer's cache (intentional — gallery-dl is pinned to a commit-SHA tarball per build, BuildKit's wheel-cache would otherwise freeze an old SHA). Consequence: each new fork-version release shipped a fresh ~150-180 MB layer; clients pulling the update re-downloaded the whole stack.

Split into two `RUN` steps. **Layer 1** = `apt-get install … && curl-fetch kepubify` (stable, cached across rebuilds because the input — `gradle.properties`'s base + Kepubify-version — rarely changes). **Layer 2** = `pip3 install gallery-dl-komga` gated by `ARG GALLERY_DL_REV`. Per-release the invalidated layer is only the small Python install (~5-15 MB compressed); the apt + kepubify layer stays cached on both the build host and the client pull. Client-side delta drops to ~10-25 MB.

Trade-off: the prior `apt-get purge curl` was removed because purging in Layer 2 would create filesystem whiteouts that bloat the layer. `curl` stays in the final image (~10 MB), accepted as the cost of the much-larger per-release delta savings.

| File | Change |
|------|--------|
| `komga/docker/Dockerfile.tpl` | Single `RUN` split into two: Layer 1 (`apt-get install … && curl/tar kepubify`) and Layer 2 (`ARG GALLERY_DL_REV` + `pip3 install gallery-dl-komga`). `apt-get purge curl` removed. |

### Fix: state-altering download events were logged at DEBUG/INFO and disappeared under the default WARN level

The fork pins `org.gotson.komga: WARN` as the baseline log level (see "log-level persists across restarts" below). Several auto-blacklist / auto-retry / stale-recovery events that change persistent state — and that an operator must see in order to understand later behaviour — were emitted at DEBUG or INFO and therefore vanished on the default install.

- `GalleryDlWrapper.autoBlacklistDuplicates` → `logger.warn` (same-group duplicate blacklist insertion).
- `GalleryDlWrapper` chapter loop → `logger.warn` for `Auto-blacklisted external redirect` (pages=0 MangaDex external chapter) and `Auto-blacklisted chapter after N failed attempts`.
- `GalleryDlWrapper.repairMissingComicInfo` → `logger.warn` for the per-file `skip … existing ComicInfo Web is non-MangaDex` decision, so the user-triggered repair shows in the log exactly which files were left alone and why.
- `DownloadExecutor.recoverStaleDownloads` → `logger.warn` for both `Recovering N stale DOWNLOADING entries from previous run` and per-entry `Reset to PENDING …`. This event only fires when the previous JVM died mid-download; operator visibility is mandatory.
- `DownloadExecutor.autoRetryFailedDownloads` → `logger.warn` for the batch-trigger and per-entry `Auto-retry queued …`. Distinguishes server-initiated retry from user-initiated retry (`retryDownload(id)` remains INFO).

User-initiated subprocess-kill / cancel / pause / delete / resume stays at INFO — those are routine UI actions and surface in the UI itself, not a log anomaly.

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlWrapper.kt` | 4 log statements elevated from DEBUG/INFO to WARN (auto-blacklist × 3, repair-skip × 1). |
| `komga/src/main/kotlin/org/gotson/komga/domain/service/DownloadExecutor.kt` | 4 log statements elevated from INFO to WARN (stale recovery × 2, auto-retry batch + per-entry). |

### Feature: gallery-dl plugin exposes a FlareSolverr URL

Cloudflare-protected manga sites (mgeko.cc, mangaclash.com, deatte5.com, tritinia.org, manhwatop.com) cannot be fetched by gallery-dl's requests-based session — the server returns the Cloudflare challenge HTML and gallery-dl reports HTTP 403 or an empty page. The companion gallery-dl-komga fork now supports a FlareSolverr endpoint that solves the challenge in a headless Chrome session and hands back the resulting cookies plus the User-Agent that solved them (cf_clearance is bound to the UA that issued it; restoring cookies without restoring the UA invalidates the clearance). Per-host cookies + UA are cached on disk for 20 minutes so subsequent runs go direct; the FlareSolverr round-trip only kicks in on a Cloudflare challenge or a cold cache.

The Komga plugin config gains an optional `flaresolverr_url` string field (Settings → Plugins → Configure gallery-dl Downloader). When non-blank, `GalleryDlProcess.createTempConfigFile` writes it into the generated gallery-dl JSON config as `extractor.flaresolverr`. The extractor-side parsing and cache lives in the gallery-dl-fork; see its CHANGELOG.

#### Modified files (covers the five fixes above)

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/domain/service/PageSplitter.kt` | **In addition to** the per-book lock from the race-condition fix above: `companion` gains `MAX_PARALLEL_SPLITS = 2` and `globalSplitSemaphore: Semaphore`. `splitTallPages` acquires the semaphore around the existing per-book `synchronized(lock)` block, releases in `finally`. `java.util.concurrent.Semaphore` import added. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/datasource/DataSourcesConfiguration.kt` | `KomgaSettingsProvider` injected as `ObjectProvider<>` (lazy, breaks the circular settings-DB dependency). New `@EventListener` handlers for `ApplicationReadyEvent` + `SettingChangedEvent.TaskPoolSize` call a private `resizeRoPool()` that sets `HikariDataSource.maximumPoolSize = taskPoolSize`. Skipped when `database.poolSize` is set explicitly or RO/RW pools aren't separated. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/ComicInfoGenerator.kt` | **In addition to** the `readWebUrl` helper from the Re-inject fix above: new `companion` `IMAGE_EXTS` list + private `copyEntry(zin, source, zout, useStored)` helper. `injectComicInfo` body extracted to private `writeInjected(useStored)`; outer method runs STORED, catches `ZipException`, retries DEFLATED on a fresh temp file. `injectComicInfoWithRetry` lifts `useStored` to the loop scope and flips it on `ZipException`. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/ChapterMatcher.kt` | New `chapterNumChapterRegex`. `extractChapterNumberFromFilename`, `extractChapterNumFromFilename`, and `matchesChapterNumber` updated to also accept `Chapter NNN` and `Chapter_NNN`. |
| `komga/src/main/kotlin/org/gotson/komga/application/startup/PluginInitializer.kt` | `chapter_naming` description rewritten (WARNING dropped, lists three accepted prefixes). New `flaresolverr_url` field in the gallery-dl plugin schema. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlProcess.kt` | `createTempConfigFile` reads `pluginConfig["flaresolverr_url"]` and, when non-blank, sets `extractor.flaresolverr` in the generated gallery-dl JSON config. |

### Fix: COMPLETED downloads showed `-` after a page reload

Symptom: a completed download (reported by the user against *I Started Working a Housekeeping Job…*, one chapter, MangaDex `ad75039d-686c-457f-b478-e56fc3b3c069`) rendered `COMPLETED  100%  -` after reloading the Download Manager page. Live progress arrives over the WebSocket stream so the row's chapter count is visible mid-session, but a reload refetches the queue through the REST API, which returns whatever is persisted in the `DOWNLOAD_QUEUE` row.

The COMPLETED handler called `updateDownloadStatus` with `status`, `progressPercent`, `completedDate`, and `destinationPath` only — `totalChapters` and `currentChapter` were never written back. If the listing call at queue-creation time had not populated `totalChapters` (the row's only earlier chance), the DB stayed `null` even when `result.filesDownloaded` reported the actual number. `Downloads.vue` then evaluated `v-if="item.totalChapters"` → false → the caption rendered blank.

Both fields are now persisted:

- `finalTotalChapters = download.totalChapters ?: result.filesDownloaded.takeIf { it > 0 }` — keeps the listing-reported count when present, otherwise falls back to what was actually downloaded.
- `finalCurrentChapter = result.filesDownloaded.takeIf { it > 0 } ?: finalTotalChapters` — non-null current count for the frontend's `N/M` template.

They go into the `download.copy(…)` passed to `updateDownloadStatus` (so the DB row carries them post-reload) and into the `completed` WebSocket broadcast (so live-streamed UIs match the post-reload state).

Existing COMPLETED rows are *not* migrated — they were persisted with `null` before this fix and remain blank after the upgrade. Verification requires a new download (live chapter release).

Frontend caption switched from `N chapters` to `{currentChapter || totalChapters}/{totalChapters} chapters`, matching the existing DOWNLOADING `N/M` style. Both the mobile card layout and the desktop `v-data-table` render the new format.

#### Modified files

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/domain/service/DownloadExecutor.kt` | COMPLETED handler computes `finalTotalChapters` and `finalCurrentChapter` from the download row and `result.filesDownloaded`. Both are written into the `download.copy(…)` passed to `updateDownloadStatus` (DB row) and into the WebSocket `completed` payload's `totalChapters`/`completedChapters`. |
| `komga-webui/src/views/Downloads.vue` | COMPLETED caption switched from `{totalChapters} chapters` to `{currentChapter || totalChapters}/{totalChapters} chapters` in both the mobile card and the desktop `v-data-table`. |

### Fix: page-thumbnail generation exhausted the JVM heap on webtoon-sized images

A user-driven series scroll triggered a wave of `OutOfMemoryError: Java heap space` from `ImageConverter.resizeImageToByteArray`, every stack ending in `Thumbnailator.ProgressiveBilinearResizer.resize` → `BufferedImageBuilder.build` → `DataBufferInt.<init>`. The frontend was requesting page thumbnails for a webtoon series (1500×30000 px pages), and `Thumbnails.of(...).size(...)` decoded each source into a full `BufferedImage` (≈180 MB at width×height×4 bytes) before downsampling. A handful of concurrent thumbnail requests therefore allocated multiple hundred-MB images on the heap at once and the JVM ran out of room despite `-Xmx3g`.

Two changes in `ImageConverter`:

- `resizeImageBuilder` now performs a **subsampled pre-decode**: when the source's longest edge is at least 2× the target thumbnail size, an `ImageReader` is opened against an `ImageInputStream` and `ImageReadParam.setSourceSubsampling(factor, factor, 0, 0)` is applied — the reader skips every Nth pixel during decompression, so a 1500×30000 webtoon at `factor = max/edgeTarget = 50` decodes to ~30×600 instead of the full surface. The pre-decoded `BufferedImage` is re-encoded once via `bufferedImageToBytes` and the resulting bytes flow into the existing `Thumbnails.of(InputStream)` path so all four callers (`BookAnalyzer`, `BookLifecycle` thumbnail + page thumbnail, `MosaicGenerator`) get the smaller pre-image without touching their call sites. `bufferedImageToBytes` returns `null` when `ImageIO.write` reports `false` or produces an empty buffer (writer not registered, e.g. WebP without TwelveMonkeys); the resize path then falls through to the original `imageBytes` so the format-incompatible case degrades cleanly instead of throwing `UnsupportedFormatException` downstream. Subsampling itself is skipped entirely when `format.imageIOFormat` is not in `supportedWriteFormats`, so a format we cannot re-encode after pre-decode is never decoded subsampled in the first place.

- A companion `decodeSemaphore = Semaphore(MAX_PARALLEL_DECODES = 2)` wraps every entry into `resizeImageToByteArray` and `resizeImageToBufferedImage`. Two simultaneous full decodes is the safety cap if the subsampled path is skipped (small source, unsupported writer); the third request blocks until one of the active decodes releases. This is symmetric to the per-process `globalSplitSemaphore` from the PageSplitter fix and keeps the absolute worst-case decode count bounded even when subsampling does nothing.

The reader endpoint that streams the original page bytes (`getBookPageByNumber`) does not go through `ImageConverter` and is untouched — original-quality page rendering is unchanged.

#### Modified files

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/image/ImageConverter.kt` | `companion` adds `MAX_PARALLEL_DECODES = 2` + `decodeSemaphore`. `resizeImageToByteArray` and `resizeImageToBufferedImage` acquire the semaphore around the existing body. New private `decodeSubsampled` (opens an `ImageReader`, sets source subsampling, decodes once) and `bufferedImageToBytes` (nullable, guards against silent `ImageIO.write` failures). `resizeImageBuilder` only runs the pre-decode path when the source is at least 2× the target edge and the source format is in `supportedWriteFormats`; otherwise it falls back to streaming `imageBytes` straight into `Thumbnails.of`. `java.util.concurrent.Semaphore` import added. |

### Fix: SQLite "no such table: temp_xxx" errors after the RO pool grew past one connection

Once the RO pool stopped being pinned at 1 (the previous fix), browser pages started failing with `SQLiteException: SQL error or missing database (no such table: temp_0QKJ4SR1JMGN4)` for any DAO that batches large IN-list queries — `BookDao.findBySeriesIds`, `BookDtoDao.findAllByBookIds`, ReadList / Sidecar / SeriesMetadata aggregations. The Komga DAO pattern wraps the IN clause through `TempTable.withTempTable(...)`:

1. `CREATE TEMPORARY TABLE temp_<tsid> (STRING varchar NOT NULL)` is executed.
2. `INSERT INTO temp_<tsid> VALUES (?), (?), ...` follows in batches.
3. The outer query joins / selects with `WHERE … IN (SELECT STRING FROM temp_<tsid>)`.
4. `close()` issues `DROP TABLE IF EXISTS temp_<tsid>`.

SQLite's `TEMPORARY` keyword scopes the table to the connection that created it — once the RO pool can hand each `dsl.execute(...)` / `dsl.batch(...)` / `dsl.select(...)` call a different Hikari connection, only step 1 sees the table. Steps 2-4 land on a connection where the temp table never existed and fail.

The table is now created without the `TEMPORARY` keyword so it lives in the regular schema and is visible to every connection in the pool. Names are TSID-generated (`temp_<TsidCreator.getTsid256()>`) so there is no collision risk between concurrent callers. The existing `close()` already drops the table when the `Closeable` exits its `.use { ... }` block, so the worst-case leak (process killed mid-operation) is a single orphan table with a stable schema, which can be cleaned by `VACUUM` or recreated by `flyway:repair` if it ever became noisy.

Other approaches considered and rejected:

- Wrap each `withTempTable(...)` in a jOOQ `transactionResult { … }` so the same connection holds the whole sequence. Correct but requires touching 36 call sites across 10 DAOs, and forces them all into the same transaction boundary which would also serialize reads behind any RW activity on the writer connection.
- Connection-pinning via HikariCP's `beginRequest()` hooks. Less invasive at call sites but the configuration scope leaks beyond the temp-table use case and silently re-pins reads in unrelated parts of Komga.

The current change is two characters at the SQL level (`CREATE TEMPORARY TABLE` → `CREATE TABLE`) and leaves the public API of `TempTable` unchanged.

#### Modified files

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/jooq/TempTable.kt` | `create()` issues `CREATE TABLE` (not `CREATE TEMPORARY TABLE`). No other behavioural change. |

### Feature: log-level persists across restarts, dual Debug/Info toggle, default WARN

Komga shipped with `logging.level.root` left at Spring Boot's default of `INFO`, so `BookLifecycle`, `TaskHandler`, hashing, and similar bookkeeping logged at INFO and rapidly filled `komga.log`. The `/settings/logs` page exposed a single `Debug` toggle that wrote DEBUG or INFO into the running `LoggerContext` only — both the YAML-configured baseline and the toggle's runtime override were lost on restart.

Three coordinated changes:

- `application.yml` pins `org.gotson.komga: WARN` so a fresh install starts quiet by default. Library-level loggers (`org.apache.activemq.audit`, `org.springframework.security.config…`) keep their existing per-package overrides.
- `LogController.setLogLevel` accepts `DEBUG / TRACE / INFO / WARN / ERROR`, normalises unknown values to `WARN`, calls a private `applyLogLevel` that sets the level on **both** the `ROOT` logger and the `org.gotson.komga` package logger, and writes the final level into `KomgaSettingsProvider.logLevel` (new `LOG_LEVEL` enum entry; default `WARN`). Flipping the YAML-pinned `org.gotson.komga` logger as well as ROOT is what makes the toggle actually switch the noisy Komga-internal loggers — otherwise the runtime ROOT change is shadowed by the package-level YAML override and the user sees no effect.
- `LogController.restoreLogLevelFromSettings` is an `@EventListener(ApplicationReadyEvent::class)` that reads the persisted level and re-applies it once the settings DAO is available. Persistence flows the same path as `taskPoolSize` (`KomgaSettingsProvider` → `serverSettingsDao.saveSetting/getSettingByKey`).
- `LogsView.vue` now renders **two** mutually-exclusive switches:
  - `Debug` (when ON, the other goes OFF, `/api/v1/logs/level?level=DEBUG`)
  - `Info` (when ON, the other goes OFF, `/api/v1/logs/level?level=INFO`)
  Both OFF resolves to `WARN`. `fetchLogLevel` maps the server's reply back onto the two switches (`DEBUG`/`TRACE` → Debug on; `INFO` → Info on; anything else → both off).

#### Modified files

| File | Change |
|------|--------|
| `komga/src/main/resources/application.yml` | `logging.level.org.gotson.komga: WARN` added. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/configuration/KomgaSettingsProvider.kt` | New `var logLevel: String` (default `WARN`) backed by `serverSettingsDao`. `LOG_LEVEL` enum entry added. |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/LogController.kt` | New constructor dep on `KomgaSettingsProvider`. `setLogLevel` accepts five levels (DEBUG/TRACE/INFO/WARN/ERROR), normalises to `WARN`, applies on ROOT + `org.gotson.komga`, persists via `settingsProvider.logLevel = …`. Restore-on-boot intentionally lives outside this controller (see `LogLevelInitializer` below) because the class-level `@PreAuthorize("hasRole('ADMIN')")` would otherwise reject any `@EventListener` method invoked without a Security context. |
| `komga/src/main/kotlin/org/gotson/komga/application/startup/LogLevelInitializer.kt` (new) | `@Component` with `@EventListener(ApplicationReadyEvent)` `restoreLogLevelFromSettings` — reads `settingsProvider.logLevel` and applies it to ROOT + `org.gotson.komga` loggers. Lives outside `LogController` so it inherits no Security annotations; without this split the listener fires under an empty `SecurityContext` and Spring Security's `AuthorizationManagerBeforeMethodInterceptor` throws `AuthenticationCredentialsNotFoundException`, which was masked by `ApplicationReadyEvent` swallowing listener exceptions (the level silently stayed at the Spring Boot default `INFO`, so the LogsView toggle always reported Info=ON after a restart even when the user had switched it off, and every `@SpringBootTest` failed initialization). |
| `komga-webui/src/views/LogsView.vue` | Single Debug switch replaced by two mutually-exclusive switches (`Debug`, `Info`). Default-both-off resolves to `WARN`. `fetchLogLevel` maps server reply back onto both states; `applyLogLevel` posts `WARN`/`INFO`/`DEBUG` depending on which toggle is on. |

### Fix: `Download Manager` chapter counter showed pre-existing CBZ files as "newly downloaded"

A user-reported regression on a 1-chapter download against *Reborn as a Space Mercenary* showed `COMPLETED  100%  109 / 109` mid-session and `COMPLETED  100%  135 / 135` after a reload — neither number was the actual fresh chapter count (1). The mid-session number was the MangaDex series total carried from `mangaInfo.totalChapters` at queue time; the post-reload number was `result.filesDownloaded`, which `GalleryDlWrapper.download` populated by listing **every** CBZ in `destDir` after the gallery-dl run finished. For a resume that started in a folder with 134 already-complete CBZs and added one new one, that count was 135 and there was no way to distinguish "new this run" from "total in folder".

`DownloadResult` gains a `newlyDownloaded: Int = filesDownloaded` field. The two success-return paths in `GalleryDlWrapper.download` compute it as `(downloadedFiles.size - existingCbzCountAtStart).coerceAtLeast(0)`, where `existingCbzCountAtStart` is sampled once at the top of `download()` before any gallery-dl process starts. On a fresh download (`existingCbzCountAtStart = 0`) it equals `filesDownloaded`; on a resume that adds N chapters it is exactly N. `DownloadExecutor.processDownload` then uses `result.newlyDownloaded` (not `result.filesDownloaded`) for both `finalTotalChapters` and `finalCurrentChapter`, so the persisted DB row and the `completed` WebSocket payload both carry the run-scoped count. Reloading the page after a 1-chapter download now renders `1/1 chapters`.

The fallback to `download.totalChapters` is kept for the degenerate case where `newlyDownloaded` is 0 (resume hit an entirely full archive and gallery-dl downloaded nothing) — without it the COMPLETED row would render blank in that scenario.

#### Modified files

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlWrapper.kt` | `download()` samples `existingCbzCountAtStart` before the gallery-dl process starts. `DownloadResult` gains `newlyDownloaded: Int = filesDownloaded` (defaulted so older constructor sites keep compiling). Both success returns set `newlyDownloaded = (downloadedFiles.size - existingCbzCountAtStart).coerceAtLeast(0)`. |
| `komga/src/main/kotlin/org/gotson/komga/domain/service/DownloadExecutor.kt` | **In addition to** the COMPLETED `-` fix above: the COMPLETED handler uses `result.newlyDownloaded` (not `result.filesDownloaded`) when computing `finalTotalChapters` and `finalCurrentChapter`. |

### Feature: per-series "Add Chapter Download" with custom filename, chapter number, and ComicInfo overrides

For series whose source uploads arrive as single-shot manga (filename and chapter number from the source don't fit the rest of the series), the existing download pipeline left no way to fix the destination filename and `ComicInfo.xml` chapter number without touching the CBZ manually after the fact. The new feature adds a "Add Chapter Download…" item to the `SeriesActionsMenu` 3-dot menu; the dialog supports two modes:

- **Single Chapter** — paste a chapter URL, optionally toggle `Use custom naming` (default ON). With custom naming on, `Filename` and `Chapter #` become required (`Volume` and `Chapter Title` stay optional). The submit posts a regular `POST /api/v1/downloads` payload extended with the overrides.
- **Series + Range** — paste the series URL and a chapter range string (`20-30` or `20,22,25`, mixable as `1-5,10,12-14`). Per-chapter overrides are not available in range mode; gallery-dl + komga-PP produce the CBZ naming and ComicInfo tags as usual, but the skip-check below still applies per chapter. The range is plumbed through to `GalleryDlWrapper.download(chapterRange = …)` and applied two ways: (1) for non-MangaDex bulk runs the range is translated to a gallery-dl `--chapter-filter` expression (`(chapter >= a and chapter <= b) or chapter == n`), so the extractor itself drops out-of-range chapters before any image fetch; (2) for MangaDex runs the wrapper's own per-chapter iteration filters `filteredChapters` with `chapterMatchesRange(number, range)` before the loop, and the resume input file (`-i` path) is filtered the same way. Both paths share one parser so the user-visible syntax is identical regardless of source.

A `Skip if file exists` toggle (default ON) compares against existing chapter numbers in the target series **via the database**, not via filename. On submit, before the download starts, `DownloadExecutor.processDownload` loads `BookMetadata.numberSort` for every book in the target `seriesId` and short-circuits to `COMPLETED` with `errorMessage = "Chapter X already exists in series"` if a match is found. Filename-based skip would have missed duplicate chapters that were uploaded under wildly different filenames (single-manga uploads, scanlator naming variations) — the database number is authoritative.

After a successful download in custom-naming mode, the COMPLETED handler calls `applyChapterOverrides`:

1. The first CBZ in `result.downloadedFiles` is renamed to `customFilename` (the `.cbz` suffix is added if missing) via `Files.move(REPLACE_EXISTING)`.
2. `patchComicInfo` opens the renamed CBZ, reads the existing `ComicInfo.xml` (or seeds an empty one if absent), upserts `<Number>` / `<Volume>` / `<Title>` with the user's values, and writes the result back through a fresh `ZipOutputStream` with the ZIP comment preserved. `upsertTag` replaces the existing tag if present and inserts it before `</ComicInfo>` otherwise; XML special characters in the user input are entity-encoded.
3. Any failure inside `applyChapterOverrides` is caught and logged at WARN — the download is still marked COMPLETED so the user can recover manually rather than seeing a transient FAILED state.

The library-scan trigger that runs at the end of every COMPLETED download remains unchanged, so the renamed-and-patched CBZ is picked up automatically.

The overrides themselves are serialised to `DownloadQueue.metadataJson` via Jackson (`ChapterDownloadOverrides`), so they survive server restart and the recovery path in `recoverStaleDownloads`. `DownloadCreateDto.toOverrides()` returns `null` when none of the override fields are set, so the standard non-custom download path stays a no-op against the new code.

#### Modified files

| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/dto/DownloadDto.kt` | `DownloadCreateDto` gains six optional fields (`seriesId`, `chapterRange`, `customFilename`, `customChapterNumber`, `customVolume`, `customChapterTitle`) + `skipIfChapterExists: Boolean = true`. New `toOverrides()` returns `ChapterDownloadOverrides?` (null when no override field is set). |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/DownloadController.kt` | `createDownload` forwards `create.toOverrides()` to `downloadExecutor.createDownload`. |
| `komga/src/main/kotlin/org/gotson/komga/domain/service/DownloadExecutor.kt` | New constructor deps on `BookRepository` and `BookMetadataRepository`. `createDownload` accepts `overrides: ChapterDownloadOverrides?` and serialises it into `metadataJson` via Jackson. `processDownload` runs a pre-download skip-check (loads `BookMetadata.numberSort` for the target series and short-circuits to COMPLETED if `customChapterNumber` matches), then forwards `overrides?.chapterRange` to `galleryDlWrapper.download(chapterRange = …)`. After a successful download, `applyChapterOverrides` renames the produced CBZ and `patchComicInfo` upserts `<Number>`/`<Volume>`/`<Title>` in `ComicInfo.xml`. New top-level `ChapterDownloadOverrides` data class at the end of the file. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlWrapper.kt` | **In addition to** the `newlyDownloaded` field from the chapter-counter fix above: new optional `chapterRange: String? = null` parameter on `download()`. When set, `parseChapterRangeToFilter` translates the range into a gallery-dl `--chapter-filter` expression that is appended to the base command (covers non-MangaDex bulk runs); the MangaDex per-chapter iteration filters `filteredChapters` via `chapterMatchesRange(Double, String)`; the resume-mode `-i` input file filters `galleryDlChapterMap.values` the same way. Both helpers parse a shared syntax (`a-b`, `n`, comma-separated mix). |
| `komga-webui/src/components/menus/SeriesActionsMenu.vue` | New `Add Chapter Download…` menu item → dispatches `dialogAddChapterDownload`. |
| `komga-webui/src/components/dialogs/AddChapterDownloadDialog.vue` | New dialog (fullscreen on `xsOnly`, `max-width: 600` on desktop). `v-btn-toggle` for `Single Chapter` / `Series + Range`. Range mode hides custom-naming fields. Custom-naming switch (default ON) gates the required `Filename` + `Chapter #` rules and the optional `Volume` + `Chapter Title`. `Skip if file exists` switch (default ON) with hint about DB-based comparison. Submit POSTs to `/api/v1/downloads` with the overrides. |
| `komga-webui/src/components/ReusableDialogs.vue` | `AddChapterDownloadDialog` wired in (component import + registration, computed `addChapterDownloadDialog` get/set bridging the Vuex store, computed `addChapterDownloadSeries` getter). |
| `komga-webui/src/store.ts` | New state (`addChapterDownloadDialog: false`, `addChapterDownloadSeries: undefined`), mutations (`setAddChapterDownloadDialog`, `setAddChapterDownloadSeries`), and actions (`dialogAddChapterDownload(series)`, `dialogAddChapterDownloadDisplay(value)`). |

### Fix: MangaDex Subscription feed dropped chapters whose follows-feed indexing lagged

`MangaDexSubscriptionSyncer.checkFeed` queried `/user/follows/manga/feed` with `publishAtSince=<last_check_time>` and set `last_check_time` to "now" after every run — a strict, non-overlapping window. MangaDex's `/user/follows/manga/feed` index is not updated synchronously with chapter `publishAt`, and the lag can be hours, not minutes. Concrete losses: `04e8da87-599f-42be-b70f-9e86a84168e8` (publishAt 2026-06-03 13:14:03 UTC, sync at 13:15 returned "no new chapters"), `89749c2e-3cde-491b-9a3c-66d32c47d116` (same date, same pattern), and `836fa090-984c-4bbb-9127-5599d34a7d9d` (publishAt 2026-06-05 12:35:17 UTC, still missing from the follows-feed 2.5h later despite being live on `/manga/{id}/feed` and the mangadex.org "Latest Updates" UI — this chapter is at `version: 3`, suggesting the server-side `publishAtSince` filter may also drop chapters that get re-versioned post-publish).

The query no longer relies on the server-side `publishAtSince` filter at all. The feed is now fetched with `order[publishAt]=desc`, no date filter, plus `includeFuturePublishAt=0` and `includeEmptyPages=0` so scheduled or zero-page entries never enter the pipeline. Each chapter's `publishAt` is parsed client-side via `OffsetDateTime.parse(...).toInstant()` and compared against a 24h-earlier cutoff derived from the stored `last_check_time`; pagination breaks at the first chapter older than the cutoff. Typical run is one page; the wide lookback exists purely to outlast the follows-feed indexing lag. Already-queued chapters are still deduped by `isChapterKnown` against `chapter_url` + blacklist tables, so the wider window never re-queues a download.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/MangaDexSubscriptionSyncer.kt` | `checkFeed` parses `last_check_time` into an `Instant` and computes a 24h-earlier client-side cutoff. The feed URL drops `publishAtSince`, sets `order[publishAt]=desc`, adds `includeFuturePublishAt=0` + `includeEmptyPages=0`. Per-chapter loop parses `publishAt` with `OffsetDateTime.parse(...).toInstant()` and breaks pagination at the first chapter older than the cutoff. New `OffsetDateTime` import. |

### Feature: `/settings/updates` shows a `gallery-dl-fork` tab on commit basis

The companion `gallery-dl-komga` fork does not bump a version string — only the upstream `gallery-dl` version it tracks does. Existing GitHub-release-based update detection therefore can't tell whether the running container is current. The Updates page gains a third tab that compares the installed gallery-dl-fork commit SHA against the latest commits on `https://github.com/08shiro80/gallery-dl-komga` and lists everything in between.

How the installed SHA is captured: the multi-stage `Dockerfile.tpl` resolves the head of `master` via the GitHub API at build time, writes the resolved SHA to `/opt/gallery-dl-fork-sha`, then installs `pip3 install … archive/${SHA}.tar.gz` (instead of `archive/refs/heads/master.tar.gz`) so the file and the actually-installed code are guaranteed to match. The runtime reads that file via `ReleaseController.readInstalledGalleryDlSha()`; if it doesn't exist (older image, local-dev builds) the tab degrades to an "Installed SHA unknown" chip and still shows the commit list.

`python3-pip` was previously stripped from the runtime image via `apt-get purge --auto-remove`. That is reverted, because the Update tab surfaces a copy-able `docker exec -u 0 komga sh -c "pip3 install … archive/<SHA>.tar.gz && echo <SHA> > /opt/gallery-dl-fork-sha"` one-liner — the user runs it from the docker host as root (Komga itself runs 1000:1000 and can't write to system `site-packages`), and `pip3` has to exist inside the container for that to work. After the manual update the SHA file is overwritten by the same command, so the next page refresh shows "Up to date" without restarting the container.

Backend: `ReleaseController.getGalleryDlForkUpdates` fetches `https://api.github.com/repos/08shiro80/gallery-dl-komga/commits?per_page=30` (cached 15min in a second Caffeine cache, separate from the existing release cache), computes `behindCount` as the index of the installed SHA in the commit list (or `-1` if the SHA file is missing), and maps each commit to `{sha, shortSha, message, author, date, url, installed}`.

Frontend: a third `v-tab` on `UpdatesView.vue` shows the installed SHA chip, a `behind by N commits` warning, the copy-command, and the commit list with the matching commit flagged as `Installed`. The "new updates" warning badges in the side navigation and home page footer (`HomeView.vue:299` + `:392`) now also light up on `isGalleryDlForkUpToDate() == 0`, so the user sees the alert without opening the Updates page.

#### Modified files
| File | Change |
|------|--------|
| `komga/docker/Dockerfile.tpl` | Resolves the gallery-dl-fork `master` HEAD via the GitHub commits API at build time, writes the SHA to `/opt/gallery-dl-fork-sha`, then installs the matching tarball (`archive/${GALLERY_DL_SHA}.tar.gz` instead of `archive/refs/heads/master.tar.gz`). `apt-get purge` no longer removes `python3-pip` — kept so the user can `docker exec -u 0 komga pip3 install …` to update without a full image rebuild. |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/ReleaseController.kt` | New `getGalleryDlForkUpdates` endpoint (`GET /api/v1/releases/gallery-dl-fork`). Adds a second 15-min Caffeine cache for commit responses (separate from the existing 1h release cache because commits move faster than releases). `readInstalledGalleryDlSha()` parses `/opt/gallery-dl-fork-sha`, accepts 7–64 char hex (covers short, full, and abbreviated SHA forms). `fetchGitHubCommits` is the commit-list equivalent of the existing `fetchGitHubReleases`. New `Files`/`Path` imports. |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/dto/GalleryDlForkUpdateDto.kt` (new) | `GalleryDlForkUpdateDto` (envelope), `GalleryDlForkCommitDto` (frontend-facing per-commit row), plus three `@JsonIgnoreProperties(ignoreUnknown = true)` GitHub commit-API parsing types (`GithubCommitDto` / `GithubCommitDetails` / `GithubCommitAuthor`). |
| `komga-webui/src/types/release.ts` | New `GalleryDlForkCommitDto` and `GalleryDlForkUpdateDto` interfaces. |
| `komga-webui/src/services/komga-releases.service.ts` | New `getGalleryDlForkUpdates()` calling `GET /api/v1/releases/gallery-dl-fork`. |
| `komga-webui/src/store.ts` | New `galleryDlForkUpdates` state (nullable), new `isGalleryDlForkUpToDate()` getter returning `1` / `0` / `-1` to match the existing `isLatestVersion`/`isForkLatestVersion` ternary, new `setGalleryDlForkUpdates` mutation. |
| `komga-webui/src/views/UpdatesView.vue` | Third `v-tab` "gallery-dl-fork" with installed-SHA chip, behind-count chip, copy-command panel, and the commit list. `data.copied`, computed `updateCommand` building the `docker exec -u 0 komga sh -c "pip3 install … archive/<SHA>.tar.gz && echo <SHA> > /opt/gallery-dl-fork-sha"` snippet from the freshest commit SHA, and `copyUpdateCommand` writing to the clipboard. `loadData()` now also calls `getGalleryDlForkUpdates()` with a fallback that commits `{installedSha: null, behindCount: -1, commits: []}` on failure so the tab still renders an "unknown" state. Scoped `.update-cmd` style. |
| `komga-webui/src/views/HomeView.vue` | The two `v-badge` warnings (`:299` side-nav "Updates" link and `:392` footer version label) now also fire on `isGalleryDlForkUpToDate() == 0`. `loadData()` calls `getGalleryDlForkUpdates()` so the badge lights up without requiring a visit to the Updates page first. |

### Fix: Add-Chapter-Download created a new manga folder instead of writing into the opened series

`processDownload` derived the destination folder purely from the MangaDex UUID embedded in the URL: chapter-URLs (`mangadex.org/chapter/<id>`) have no manga UUID, so `extractMangaDexId` returned `null`, the wrapper fell through to the non-MangaDex `libraryPath.resolve(sanitizeFileName(title))` branch and created a fresh folder named after the chapter's series-title (e.g. `"The Angel Next Door Spoils Me Rotten After the Rain/"`) — duplicating an existing Komga series (`Otonari no Tenshi-sama…/`) and leaving the user with two split folders for the same title.

**Why:** the user explicitly invokes Add-Chapter-Download from inside a series, so the target is unambiguous and shouldn't depend on URL-shape detection. `processDownload` now resolves `overrides.seriesId` (forwarded by `DownloadCreateDto.toOverrides()`) to the actual Series row, sets `destinationPath = Paths.get(overridesSeries.url.toURI())` and `komgaSeriesId = overridesSeries.id`, **before** the existing mangaDex-id / fallback branches. The two existing branches stay as-is for the URL-only paths (subscription queue, follow.txt). Works for any URL shape — MangaDex chapter URLs, MangaDex title URLs, non-MangaDex chapter URLs — because the destination is now driven by the user's UI-selected series, not by parsing the URL.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/domain/service/DownloadExecutor.kt` | **In addition to** the Add-Chapter-Download wiring above: new `overridesSeries` lookup (`overrides?.seriesId?.let { seriesRepository.findByIdOrNull(it) }`) right before the mangaDexId / fallback branches; when non-null, `destinationPath` is set from `overridesSeries.url.toURI()` and `komgaSeriesId` from `overridesSeries.id`. Logs `Add-Chapter-Download targets existing series <name> (<path>)`. |

### Fix: Subscription auto-queued dead mangas (no en chapters) endlessly

`MangaDexSubscriptionSyncer.checkForNewManga` queued a `createDownload` for every newly followed manga without first verifying the manga has any downloadable chapters in the user's language. For manga whose follows-feed entry exists but whose `/manga/{id}/feed?translatedLanguage[]=en` returns empty (only non-en chapters, only `externalUrl != null` chapters, or `pages: 0`) the wrapper logged `MangaDex chapter API returned empty for <id>, skipping bulk download` and skipped — but never added the manga to Komga's series-table, so `seriesRepository.findByMangaDexUuid(mangaId) != null` was `false` on every subsequent run, the syncer requeued the same manga, the wrapper skipped again, ad infinitum (concrete loop: `2f90e7e5-16b4-41f5-b717-137cf9783b5b`).

**Why not just blacklist the manga:** the user explicitly rejected a permanent-disable approach because later chapters may become available (en-translation added, externalUrl-chapter republished without external pointer). The right gate is "does the manga have at least one downloadable chapter right now?" — re-evaluated every sync.

New `hasDownloadableChapters(mangaId, language, token)` helper calls `/manga/{id}/feed?translatedLanguage[]=<lang>&includeFuturePublishAt=0&includeEmptyPages=0&includeExternalUrl=0&limit=1` and inspects `total`. If the response is non-200 or the parse fails the result defaults to `true` (do not skip on transient API errors — better one redundant download attempt than a silently dropped manga). `checkForNewManga` calls the helper between the `isUrlAlreadyQueued` check and `createDownload`, skips with a debug log if false.

A second cleanup in the same file: the language was looked up in two places via the same inline `pluginConfigRepository.findByPluginIdAndKey(... "default_language")?.configValue ?: "en"` snippet. Both call sites now share a private `resolveLanguage()` helper that **drops** the `?: "en"` fallback and throws `IllegalStateException("gallery-dl plugin default_language not configured")` instead — the field is always populated in practice (subscription syncer also no-ops when gallery-dl plugin is disabled), so a missing-value here means the config row has been wiped and silently substituting "en" would mask the misconfiguration.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/MangaDexSubscriptionSyncer.kt` | **In addition to** the follows-feed lag fix above: new `resolveLanguage()` and `hasDownloadableChapters(mangaId, language, token)` private helpers. `checkForNewManga` calls `hasDownloadableChapters` between `isUrlAlreadyQueued` and `createDownload`; both call sites for the plugin's `default_language` config-key go through `resolveLanguage()` (no fallback, throws on missing). |

### Feature: `/media-management/analysis` gains Verify / Repair / Rescan actions and persistent ERROR-flagging for corrupt CBZs

`/media-management/analysis` rendered an empty list because no path in Komga ever set `Media.Status.ERROR` for CBZs that became corrupt **after** their initial analyze (e.g. truncated by an interrupted write, central-directory mismatch from a PageSplitter race, SMB partial-write). Initial `BookAnalyzer.analyze` does flag corrupt-on-first-open files (catch-all `catch (Exception)` → `ERR_1005`), but that only fires for `UNKNOWN`/`OUTDATED` books — once a book is `READY`, subsequent `RemoveHashedPages` runs throwing `ZipException` propagated to `TaskHandler` as a generic ERROR log without persisting any flag, so the spam continued every scheduled scan and the user had no UI signal that the file was actually broken.

Three new pieces solve the loop and surface the result:

1. **Passive flag at read-time** — `BookPageEditor.removeHashedPages` and `deletePages` wrap the rewrite block (now under `CbzSafeWriter`, see below) with `catch (ZipException)` that updates `Media.Status.ERROR` once (idempotent) with `comment = "Corrupt CBZ: <message>"` and returns null. The early `if (media.status == ERROR) { logger.debug(...); return null }` guard then prevents the next scheduled run from even trying — the log goes from N×stack-trace per book to one debug line.

2. **Active scan on demand** — `IntegrityController` (`api/v1/media-management/integrity/`) gains `POST verify`: a single-thread `Executors.newSingleThreadExecutor()` iterates all `.cbz`/`.zip` books via `bookRepository.findAll()`, opens each with `ZipFile`, iterates every entry and reads every byte. Each book gets a **two-pass read with a 2 s sleep between passes** (`VERIFY_RETRY_DELAY_MS`); a book is only flagged when **both** passes fail. The retry suppresses false positives from transient I/O (HDD bad-sector retry, file-handle race against a concurrent write); recovered-on-retry events log at INFO. Counters `processed/total/flagged` are `AtomicInteger`. AtomicBoolean lock returns 409 if already running. `GET status` exposes counters for the live progress bar; for the flagged-count it queries `mediaRepository.countByStatus(ERROR)` instead of the in-memory counter when no scan is running, so the count survives container restart.

3. **Repair without re-download** — `POST repair` runs `zip -FF <src> --out <tmp>` on each ERROR-flagged book. For "central-directory corrupt, data intact" cases this recovers all entries; the result is verified by re-opening with `ZipFile` and counting entries against the pre-repair central-dir count. Fully recovered → `Files.move` overwrites the original, status flipped to `OUTDATED` so Komga's regular `analyzeBook` picks it up and promotes it back to `READY`. Partial → tmp deleted, comment updated to `"Partial repair: N/M entries — needs re-download"`, status stays ERROR. Timeout (10 min/file) and exit-code failures count as `failed`. `zip` package added to the runtime image (`Dockerfile.tpl`) because the multi-stage build previously didn't include it.

4. **Rescan-flagged** — `POST rescan` filters books with `status == ERROR` and emits one `Task.AnalyzeBook` per book via `taskEmitter.analyzeBook(book)`. Used after a Repair-Partial or after the user externally fixed a file on disk: forces Komga to re-evaluate one shot instead of waiting for the next library scan. Returns `{queued: N}` for the UI snackbar; the actual progress is visible via the existing task-queue indicator in the nav-bar (see "global background-job indicator" below).

The frontend wires three buttons under the existing status filter: `Verify ZIP integrity` (always present), `Repair flagged (N)` and `Rescan flagged (N)` (visible only when `verifyFlagged > 0`). Live counters poll `/status` every 3s while either verify or repair is in-progress and stop polling when both flags clear; on poll-stop the table reloads so newly-fixed books vanish from the ERROR list. The pre-mount fetch ensures progress is visible immediately if a scan started before the user opened the page.

#### Modified / new files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/domain/service/BookPageEditor.kt` | Both rewrite paths (`removeHashedPages`, `deletePages`) wrap the ZIP-write block with `catch (ZipException)` → flag `Media.Status.ERROR` with `comment = "Corrupt CBZ: <msg>"` and `return null`. Pre-flight: `if (media.status == ERROR) { logger.debug(...); return null }` short-circuits future runs on the same book, replacing the previous `MediaNotReadyException` throw that produced WARN-level log spam. New `java.util.zip.ZipException` import. |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/IntegrityController.kt` (new) | `POST verify` / `POST repair` / `POST rescan` / `GET status`. `verify`: single-thread executor iterates books and reads every byte of every entry. `repair`: spawns `zip -FF` subprocess per book, verifies entry-count, replaces atomically when fully recovered (status → OUTDATED for re-analyze). `rescan`: emits `Task.AnalyzeBook` per ERROR book via `taskEmitter`. `status` returns in-memory counters during a running scan but falls back to `mediaRepository.countByStatus(ERROR)` when idle so the badge survives container restart. AtomicBoolean locks for verify and repair (separate slots). `BackgroundJobTracker` integration so the actions surface in the global nav-bar indicator. |
| `komga/src/main/kotlin/org/gotson/komga/domain/persistence/MediaRepository.kt` | New `fun countByStatus(status: Media.Status): Long`. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/jooq/main/MediaDao.kt` | Impl `dslRO.fetchCount(m, m.STATUS.eq(status.name)).toLong()`. |
| `komga/docker/Dockerfile.tpl` | `apt-get install` list now includes `zip` (alongside `python3 python3-pip` for the gallery-dl Update-Page repair). Without it the `zip -FF` subprocess on the repair path would fail with `executable file not found in $PATH`. |
| `komga-webui/src/views/MediaAnalysis.vue` | Three buttons under the existing status filter row (`Verify`, `Repair`, `Rescan` — last two conditionally on `verifyFlagged > 0`). Live counter `{processed}/{total} checked · {flagged} flagged` for verify; `Repair: {processed}/{total} · fixed N · partial K · failed M`. Single polling loop covers both verify and repair via `refreshVerifyStatus`. `rescanFlagged` posts and pops a snackbar with the queued count; `loadBooks` re-runs after 2 s so the table loses the just-fixed rows. Mount hook fetches `/status` so progress is visible immediately after a page reload. |

### Feature: global background-job indicator (`nav-bar TaskQueueStatus` SSE includes custom long-running operations)

The existing `taskCount` indicator in the side-navigation only reflected the `tasksRepository` (Komga's regular task queue). Long-running actions launched via separate executors — Split-All (`OversizedPagesController`), Verify Integrity (`IntegrityController.verifyAll`), Repair (`IntegrityController.repairAll`) — were invisible in the nav-bar progress bar even though they could run for an hour. Users had to keep the originating page open to know anything was happening.

A new `BackgroundJobTracker` Spring `@Component` holds a `ConcurrentHashMap<String, Boolean>` of active job-names. The three custom controllers call `tracker.start("Job Name")` / `tracker.stop("Job Name")` in their try/finally. `SseController.taskCount()` (scheduled `@Scheduled(fixedRate = 10_000)`) now emits `backgroundJobs: List<String>` alongside the existing `count` / `countByType`; the frontend store mutates `state.backgroundJobs`, `HomeView.vue` ORs `taskCount > 0` with `backgroundJobs.length > 0` on the linear progress-bar `:active` binding and adds a `Background:` block in the tooltip listing the active job names. 10 s refresh matches the existing taskCount cadence; jobs that finish between ticks are picked up at the next tick.

Adding a new long-running action in the future means two lines (`tracker.start("Name")` + `tracker.stop("Name")` in `try`/`finally`) — no SSE or UI changes needed.

#### Modified / new files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/background/BackgroundJobTracker.kt` (new) | `@Component` with `ConcurrentHashMap<String, Boolean>`; `start(jobName)`, `stop(jobName)`, `snapshot(): Map<String, Boolean>`. |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/sse/dto/TaskQueueSseDto.kt` | Added `backgroundJobs: List<String> = emptyList()` (default keeps the DTO source-compatible for any consumers parsing without the new field). |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/sse/SseController.kt` | Constructor takes `BackgroundJobTracker`; `taskCount()` builds the SSE payload with `tracker.snapshot().keys.sorted()` so the order is stable across emissions. |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/OversizedPagesController.kt` | `splitAllTallPages` wraps the body in `tracker.start("Split All")` / `tracker.stop` in try/finally (in addition to the AtomicBoolean lock below). |
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/IntegrityController.kt` | `verifyAll` and `repairAll` both call `tracker.start(...)` / `tracker.stop(...)` around their executor submission (the executor itself does the in-finally stop because the request thread returns immediately). |
| `komga-webui/src/plugins/komga-sse.plugin.ts` | Store state gains `backgroundJobs: [] as string[]`; `setTaskCount` mutation reads `event.backgroundJobs || []`. |
| `komga-webui/src/views/HomeView.vue` | Linear progress bar `:active="taskCount > 0 || backgroundJobs.length > 0"`. Tooltip adds a `Background:` heading with one line per active job. New computed `backgroundJobs(): string[]`. |

### Fix: `/media-management/oversized-pages` Split-All had no endpoint-level lock — double-click fanned out a second pass over the same library

The 0.1.5 PageSplitter mutex (semaphore + per-book lock) serialised concurrent splits on the *same* book and capped total parallel splits at 2, but the `POST split-all` endpoint itself had no lock: a double-click from the UI (or two users on different tabs) triggered two synchronous loops over the same `pagesByBook` map, each acquiring the semaphore in turn and walking through every book again. Per-book serialisation prevented direct file corruption, but every book got split twice (the second pass found the now-shorter pages, re-applied the same threshold and produced a no-op `0 pagesSplit` result for most of them — but the wasted I/O blocked legitimate work for the duration of the entire second loop). On a 26 k-book library that's an extra ~30 minutes of pointless work plus a UX where the button reappeared "available" mid-run.

**Why the request thread guard:** the user explicitly wanted the button to remain unclickable across a page reload — a client-side `splitting` boolean alone resets to `false` on every fresh mount. Server-side AtomicBoolean is the only way for tab N to know that tab M kicked off a run that's still running.

`OversizedPagesController` gains a `private val splitAllInProgress = AtomicBoolean(false)`. `splitAllTallPages` does `compareAndSet(false, true)` before any work; if it fails the endpoint throws `ResponseStatusException(409, "Split-All operation already in progress")`. The actual loop is moved into `runSplitAll(request)` so the try/finally that resets the flag wraps the entire run. A new `GET split-all/status` returns `{inProgress: Boolean}` for the frontend mount hook: on page load `OversizedPages.vue` defaults `splitting = true` (button greyed), then calls `/split-all/status`; if the response is `inProgress: false` it flips to `false` (button re-enabled). If true, the existing 3 s polling loop reuses `pollSplitAllStatus` to keep the button disabled until the run finishes, then `loadPages()` refreshes the table. A 409 from a click is handled by switching to the same poll loop and surfacing a "Split-All is already running — please wait" snackbar. The initial-true default prevents the brief enabled-flash between mount and the first `/status` response.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/OversizedPagesController.kt` | New `splitAllInProgress: AtomicBoolean`. `splitAllTallPages` does `compareAndSet`, throws 409 on collision, and wraps the renamed `runSplitAll(request)` in try/finally. New `GET split-all/status` → `{inProgress: Boolean}`. `tracker.start("Split All")` / `tracker.stop` for the global indicator. |
| `komga-webui/src/views/OversizedPages.vue` | `data.splitting` defaults to `true` (button disabled until proven idle). `mounted()` calls `checkSplitAllStatusOnMount()` which queries `/status` and flips `splitting` to `false` only on confirmed `inProgress: false`. `splitAll()` catches 409, starts `pollSplitAllStatus()`. `pollSplitAllStatus()` polls every 3 s and refreshes `loadPages()` when the server reports `inProgress: false`. `beforeDestroy()` clears the interval. |

### Feature: `CbzSafeWriter` — every CBZ-mutating path now writes through a single hardened pipeline

Six paths could mutate a CBZ on disk: `PageSplitter.splitTallPages`, `BookPageEditor.removeHashedPages`, `BookPageEditor.deletePages`, `ComicInfoGenerator.writeInjected`, `ComicInfoGenerator.injectComicInfoWithRetry`, `DownloadExecutor.patchComicInfo`. Each had ad-hoc tmp-file handling — write to `tempFile`, `Files.move(REPLACE_EXISTING)`, a `finally { Files.deleteIfExists(tempFile) }` — and no post-write verification. A truncated network write to the SMB share, an exception mid-write, an interrupted process, or an SMB disconnect would leave the original replaced by an unreadable file, and the only signal was the `ZipException` thrown later when something tried to read the result. That accumulated 262 corrupt CBZs in the user's library (4 paths × hundreds of operations over months).

**Why a single utility:** the user asked for "1000 failsafes" — six independent implementations would each need their own backup, verify, rollback, disk-space-check logic, and adding a new path later would require remembering all of them. One `CbzSafeWriter.safelyReplace(target, writeLambda)` ensures every mutation is bounded by the same guarantees.

The pipeline per call — the original target file remains intact and readable until the final atomic swap. **Ordering is load-bearing:** the write-lambda is invoked while `target` is still the real file, because many callers re-read `target` inside the lambda (e.g. `BookPageEditor.removeHashedPages` reads `book.path` via `bookAnalyzer.getFileContent` to enumerate pages to drop). Backing up to `.bak` **before** running the lambda would mean the lambda sees an empty `target`, fails, rolls back from `.bak`, and (if the rollback itself fails on SMB) loses the file. The final order below avoids that class of failure entirely.

1. **Disk-space check** — `Files.getFileStore(parent).usableSpace >= 2 × expected-size`. Half the safety margin is for the tmp-file, half is to ensure the rollback can re-write the original if needed.
2. **RAM-build or disk-build via the write-lambda** — original < 100 MB → lambda writes to a `ByteArrayOutputStream` (in-RAM), original ≥ 100 MB → lambda writes to a disk-tmp. The threshold avoids OOM on huge webtoon CBZs while keeping the fast-path RAM-only for typical chapters. **Target is still intact at this point**, so the lambda can re-read it.
3. **In-RAM verify** (RAM-pipeline only) — `ZipInputStream` over the bytes; iterate every entry, read every byte to EOF. Any `ZipException` here aborts before disk-write.
4. **Disk-write to tmp** — `Files.write(tmp, bytes)` for RAM-pipeline; the lambda already wrote disk-tmp directly for the disk-pipeline.
5. **Post-write verify on disk** — `ZipFile(tmp.toFile())` + iterate every entry + read every byte. Catches "RAM bytes were correct, but disk write was truncated by SMB disconnect". Empty result aborts.
6. **Backup via `Files.move`** — original.cbz → `.bak.<uuid>`. Move (atomic on same-FS, copy+delete on SMB) avoids a wasteful per-CBZ data copy; at 150-chapter Split-All on a 50-200 MB-per-chapter library this saves ~15-30 GB of disk traffic per run. The target is only invisible to readers for the brief window between this step and the next.
7. **Atomic rename** — `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` with fallback to `REPLACE_EXISTING`-only when the underlying filesystem doesn't support atomic moves (SMB falls back here).
8. **Post-rename verify** — third `ZipFile` open on the final path. Catches "the rename completed but the destination is unreadable" (rare, but possible on flaky SMB where the rename ACKs but the result is partial).
9. **Success** → `.bak` deleted. **Any-step failure after the backup move** → tmp deleted, `.bak` moved back over target (atomic rollback), `.bak` deleted, original IOException rethrown. **Failures before the backup move** simply delete the tmp; `target` was never touched. **Rollback failure** → `.bak` is left on disk and a fatal-level log line gives its path so the user can restore manually.

The lambda signature is `(OutputStream) -> Unit` so callers can wrap the stream in either `java.util.zip.ZipOutputStream` (ComicInfoGenerator, DownloadExecutor) or Apache `commons-compress` `ZipArchiveOutputStream` (PageSplitter, BookPageEditor — which use `setMethod(DEFLATED) + setLevel(NO_COMPRESSION)`). Same writer covers both APIs.

#### Modified / new files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/util/CbzSafeWriter.kt` (new) | `object` with `safelyReplace(target: Path, write: (OutputStream) -> Unit)`. Backup via `Files.move(ATOMIC_MOVE)` (REPLACE_EXISTING fallback). RAM/disk threshold at 100 MB (`RAM_BUILD_THRESHOLD`). Disk-space check requires 2× expected-size (`MIN_FREE_RATIO`). Three verify points (in-RAM, post-write, post-rename). Atomic rename with REPLACE_EXISTING fallback. Try/catch wraps the entire critical section; rollback restores from `.bak` on any failure; only deletes `.bak` after a confirmed-clean swap. |
| `komga/src/main/kotlin/org/gotson/komga/domain/service/PageSplitter.kt` | The post-`pagesToSplit.isEmpty()` write block (previously `ZipArchiveOutputStream(tempPath.outputStream())` + `Files.move`) is replaced by `CbzSafeWriter.safelyReplace(book.path) { outStream -> ZipArchiveOutputStream(outStream).use { ... } }`. The dead `tempPath.deleteIfExists()` from the catch arm is removed (CbzSafeWriter handles tmp cleanup internally). The legacy `backupPath` (the older PageSplitter backup mechanism) is kept because the wider try/catch in the function still restores from it for non-CbzSafeWriter failures (e.g. an exception during `bookAnalyzer.analyze` after the write succeeded). |
| `komga/src/main/kotlin/org/gotson/komga/domain/service/BookPageEditor.kt` | Both `removeHashedPages` and `deletePages` replace their `ZipArchiveOutputStream(tempFile.outputStream())` + `tempFile.moveTo` blocks with `CbzSafeWriter.safelyReplace(book.path) { ... }`. The subsequent re-scan/analyze calls now run against `book.path` directly instead of `tempFile`. The `ZipException` catch (which sets ERROR status) wraps the safelyReplace call so a build-time corruption is caught and flagged. |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/ComicInfoGenerator.kt` | `writeInjected` and `injectComicInfoWithRetry` swap their tmp-file orchestration for `CbzSafeWriter.safelyReplace(cbzPath) { outStream -> ZipOutputStream(outStream).use { ... } }`. The retry loop in `injectComicInfoWithRetry` keeps its `useStored` flip on `ZipException` (STORED→DEFLATED) and the FileSystemException retry-with-backoff (file locked by a concurrent process); the actual write atomicity is delegated to the writer. |
| `komga/src/main/kotlin/org/gotson/komga/domain/service/DownloadExecutor.kt` | `patchComicInfo` (called for Add-Chapter-Download custom-naming) routes through `CbzSafeWriter.safelyReplace(cbz.toPath()) { ... }` so an override-rewrite mid-write can't leave the just-downloaded chapter corrupt. The outer `try/catch (Exception)` keeps the existing "warn + carry on" behaviour: a failed override is preferable to a failed-and-rolled-back chapter when the download itself succeeded. |

### Fix: `/media-management/duplicate-files` materialised every duplicate-hash key into Kotlin memory, then sent the full list back to SQLite

`BookDtoDao.findAllDuplicates` ran two queries: query #1 selected all duplicate `FILE_HASH` / `FILE_SIZE` group keys into a `Map<String, Int>` (one row per distinct hash with count > 1), then query #2 fed `hashes.keys` into a SQL `IN (...)` against `b.FILE_HASH`. For a 26 k-book library with ~1 k duplicate hashes the IN-clause had ~1 k literal strings, which SQLite cannot index-resolve and falls back to a full table scan; the round-trip serialise→deserialise of the keys through JDBC also added measurable latency. The page either timed out or rendered nothing depending on which side gave up first.

Refactored to one DSL `duplicateHashSubquery` (the same SELECT-with-HAVING from query #1, but as a subquery instead of a materialised collection); both the `count(*)` and the paginated DTO select use `b.FILE_HASH.in(duplicateHashSubquery)`. SQLite evaluates the subquery once per outer reference and the join uses the `FILE_HASH` index; total query time drops from minutes to seconds at 26 k books. **Why two `.in(subquery)` references instead of caching the result** — JOOQ's DSL builders for jOOQ-generated tables don't share subquery results across `fetchCount` and `selectBase`, but SQLite's optimiser handles the redundant evaluation cheaply (the inner query is a single index-scan); caching across both would have required a temp-table dance that costs more than it saves at this size.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/jooq/main/BookDtoDao.kt` | `findAllDuplicates` rewritten: removed the `hashes` Map materialisation; introduced `duplicateHashSubquery` (DSL select/from/groupBy/having); count via `fetchCount(... where FILE_HASH.in(subquery))`; DTO query reuses the same subquery in the where-clause. `count`/`PageImpl` math identical to before. |

### Fix: completed downloads didn't trigger a series rescan, new chapters lingered on disk without entering Komga

`processDownload` updated `DownloadStatus.COMPLETED` and broadcast the `DownloadCompleted` WebSocket event, but never asked Komga to scan the destination library. For chapters that landed in an existing series this was usually fine because Komga's `LibraryContentLifecycle` re-scans on its own schedule. But the user's expectation — and the natural behaviour for Add-Chapter-Download — is that the new file appears in the UI immediately after the download finishes; waiting for the next library-scheduled scan (which can be hours away depending on settings) confused the user enough to file it as a bug.

When `result.newlyDownloaded > 0` and `download.libraryId != null`, the COMPLETED handler now also calls `taskEmitter.scanLibrary(download.libraryId, scanDeep = false)` and logs `Triggered library scan after download (N new files)`. A `scanDeep = false` scan is cheap (filesystem walk + new-file detection, no re-hash of existing books) so this is a safe always-on trigger. The `newlyDownloaded` guard prevents firing a scan after a no-op resume that found nothing new.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/domain/service/DownloadExecutor.kt` | Inside the `result.success` branch in `processDownload`, after the `eventPublisher.publishEvent(DomainEvent.DownloadCompleted(...))` call: `if (result.newlyDownloaded > 0 && download.libraryId != null) { taskEmitter.scanLibrary(download.libraryId, scanDeep = false); logger.info { ... } }`. |
| `komga/docker/Dockerfile.tpl` | Split the monolithic `RUN` into two layers: Layer 1 (apt packages + kepubify, no ARG reference, stable across komga builds) and Layer 2 (`pip3 install gallery-dl-komga` gated by `ARG GALLERY_DL_REV`). Drops `apt-get purge curl` (curl needed in Layer 2 for the github commits API call). End-user pull per komga release drops from ~200 MB to ~10-25 MB because `GALLERY_DL_REV=${{ github.run_id }}-${{ github.run_attempt }}` no longer invalidates the ~150-180 MB apt layer. |

### Feature: Library "Newest" sort uses book-creation time as tiebreaker

The series-list `lastModifiedDate`/`lastModified` sort (UI label "Date updated") was a single `ORDER BY MAX(book_metadata.release_date)`. `RELEASE_DATE` carries day-granularity only and is frequently missing or backdated to the original chapter release. Effect in the user's library: when several series received a new chapter on the same day — or when a freshly downloaded chapter had an old `release_date` (backfill of an older chapter, missing metadata) — the sort order between those series collapsed to whatever SQLite's row order happened to be, so "just downloaded a new chapter" series did not surface to the top.

`SeriesDtoDao` now appends `MAX(book.created_date)` as a second `ORDER BY` term whenever the sort property is `lastModifiedDate` or `lastModified`. `BOOK.CREATED_DATE` is the DB insert timestamp with millisecond precision, written once on first import and never touched by later content mutations (page-split, ComicInfo re-injection, `CbzSafeWriter` rewrites). Tiebreaker direction follows the primary sort direction (DESC primary → DESC tiebreaker).

Other sort fields (`createdDate`, `name`, `booksCount`, `readDate`, `metadata.titleSort`, `random`, `relevance`, `collection.number`, `booksMetadata.releaseDate`) are untouched.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/jooq/main/SeriesDtoDao.kt` | Added private `newestBookCreatedDate` field (`MAX(BOOK.CREATED_DATE)` correlated subquery on `b.SERIES_ID = s.ID`). Rewrote the `orderBy` builder from `mapNotNull` to `flatMap`: for the `lastModifiedDate`/`lastModified` properties it now emits a two-element `[primary, newestBookCreatedDate]` sort-field list with matching direction; all other properties keep a one-element list. The `sorts` map mapping for these two properties is unchanged (`newestBookReleaseDate` remains the primary). |

---

## [0.1.4.5] - 2026-05-31 Hotfix

### Fix: gallery-dl resume cleanup recursively deleted entire series folders (data loss)

`GalleryDlWrapper` ran two destructive cleanup blocks during the non-MangaDex resume path that, under real-world layouts, wiped entire libraries:

- **Lines 530-538 (resume entry):** `destDir.walkTopDown().filter { dir contains image files }.forEach { it.deleteRecursively() }`. `walkTopDown()` includes `destDir` itself, so any series root containing a `cover.jpg` matched the filter and was recursively deleted along with every CBZ, `series.json` and `.gallery-dl-archive.txt` it held.
- **Lines 925-936 (post-download):** `destDir.listFiles().filter { isDirectory }.forEach { it.deleteRecursively() }`. With extractors that nest into `{category}/{manga}/` (e.g. `manhuaplus` via the Madara base), this wiped the entire subtree of finished chapters in one shot.

Concrete incident (2026-05-31, manhuaplus *Magic Emperor*): 864 finished chapters plus `cover.jpg` and `series.json` were destroyed in a single resume run. Komga's DB still held the book records, so subsequent metadata refreshes produced a flood of `NoSuchFileException` against every CBZ path.

The cleanup code was also pointless: gallery-dl's own downloader treats `.part` files as byte-range resume markers (`downloader/http.py:159` — `Range: bytes=<size>-`), so removing them only forces a from-scratch redownload. The `zip` postprocessor with `keep-files: false` already prunes packed page files after each successful chapter. Both delete blocks are removed entirely; nothing replaces them.

### Fix: gallery-dl extractors without an explicit `directory` override nested CBZs under `{category}/{manga}/`

`GalleryDlProcess.createTempConfigFile` only overrode `extractor.<site>.directory` for sites declared in `getDefaultWebsiteConfigs` (mangadex, mangahere, comick, ...). Any extractor not in that map fell through to its own default `directory_fmt` — for everything Madara-based (`manhuaplus`, `asurascans` mirrors, etc.) that is `("{category}", "{manga}", "c{chapter}…")`, which writes finished CBZs three levels deep under the series root. The wrapper then relied on the post-run move-to-root step (`GalleryDlWrapper.kt:655-667`) to flatten them, but that hand-off depended on `.gallery-dl-archive.txt` and the leftover subdirs surviving cleanup — which they didn't (see above).

An extractor-global `directory: ["c{chapter:>03}"]` fallback is now set on `extractor` itself. Site-specific overrides keep their existing templates (gallery-dl resolves per-extractor config before the global fallback); unconfigured extractors now write directly into the series root, so the move step usually has nothing to do and `.gallery-dl-archive.txt` stays at a stable path.

#### Modified files
| File | Change |
|------|--------|
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlWrapper.kt` | Both destructive cleanup blocks removed (530-538 and 925-936). |
| `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlProcess.kt` | Added `extractor.directory` global fallback in `createTempConfigFile`. |

---

## [0.1.4.4] - 2026-05-30

### Fix: MangaDex Subscription feed — new chapters silently skipped for any manga that was ever queued

`MangaDexSubscriptionSyncer.checkFeed` was guarding every chapter with `downloadExecutor.isUrlAlreadyQueued(mangaUrl)`, which treats `COMPLETED` queue entries as "still queued". Any manga that ever produced a queue row (e.g. an earlier 0-files completion from an empty MangaDex API, or a one-off queue via the search dialog) was permanently invisible to the feed — `runFeedCheck` reported "no new chapters" while real updates piled up, fixable only by manually clearing the COMPLETED row. The guard is removed in `checkFeed`: `isChapterKnown` already blocks duplicates via the chapter-URL DB, and `createDownload` is the right action when the API reports a new chapter for a followed series, regardless of any historical queue row for the manga URL.

### Fix: MangaDex Listing API stale `pages: 0` auto-blacklisted real chapters

For some chapters MangaDex's `/chapter` and `/manga/{id}/feed` listings return `pages: 0` while `externalUrl` is still `null` — the listing cache is stale, but `/at-home/server/{chapterId}` actually serves the real pages. Komga's downloader treated every `pages == 0` chapter as an external-publisher redirect and auto-blacklisted it forever, so those chapters could never be re-downloaded even after MangaDex's cache caught up. gallery-dl's own check is `if external_url and not count: abort` — only when both are set is it a real redirect. `MangaDexApiClient` now extracts `externalUrl` into `ChapterDownloadInfo` and `GalleryDlWrapper` uses the same precise condition:

- `externalRedirects = filteredChapters.count { it.pages == 0 && it.externalUrl != null }` (statistic only)
- `totalChapters = filteredChapters.count { (it.pages > 0 || it.externalUrl == null) && fails < 3 }` — stale-cache chapters are counted as downloadable
- Auto-blacklist is gated by `chapter.pages == 0 && chapter.externalUrl != null`

Stale-cache chapters fall through to the normal download path. gallery-dl follows the chapter URL, sees `external_url=null`, hits AtHome, and writes the real pages. Real J-Novel-style redirects (`externalUrl != null && pages == 0`) are still auto-blacklisted; ChapterChecker, the Subscription sync, ChapterUrlImporter, the `downloadable-check` heuristic and the ComicInfo generator all keep their existing semantics — they don't gate on `pages == 0`. After repeated AtHome failures the existing `chapterFailures` counter (3 strikes) takes over for chapters that really have no pages anywhere.

**Example:** `https://mangadex.org/title/d2d22b38-4b3f-4ffb-9387-d18f870d5a91` — chapters 13 (EN) and 17 (EN) show `pages: 0, externalUrl: null` in the listing, but `/at-home/server/2e42e9fd-…` returns 24 image filenames and `/at-home/server/9000d89b-…` returns 36. Before this change both were auto-blacklisted on first sync; after the change they download normally.

### Added: Force Resync Feed action (schema-driven fix card)

A new `FixRegistry` (`infrastructure/maintenance/`) defines maintenance actions as JSON-serialisable schemas (`id`, `title`, `description`, `icon`, `endpoint`, `method`, typed `params[]`) with a per-fix `isEnabled` predicate so each entry hides when its underlying plugin/feature is disabled. `GET /api/v1/maintenance/fixes` returns the visible list, and the existing `SettingsFixes.vue` (`/settings/fixes`) renders one card per fix dynamically — typed inputs (`number`/`string`/`boolean`) and a Run button are generated automatically; the hand-written *Re-inject ComicInfo.xml* card on the same page stays in place because it has its own polling/status flow. Adding a new fix later means one `Registration` block in `FixRegistry` plus the backend endpoint; no Vue changes needed.

First registered fix: **MangaDex Subscription — Force Resync** (`POST /api/v1/downloads/mangadex-subscription/force-resync?lookbackDays=N`, default 7). Rewinds `last_check_time` by N days and runs `runFeedCheck()` immediately, so chapters that were silently dropped by the old `isUrlAlreadyQueued` guard above (or by a stuck `last_check_time` after a long restart gap) get picked up without waiting for the next scheduled tick.

### Added: MangaDex account follow toggle + filter in the advanced MangaDex search

The advanced MangaDex search under */downloads* already had a follow.txt toggle per card; it now also has an explicit *MangaDex* button (visible when the MangaDex Subscription plugin is enabled) that follows/unfollows the title on the user's MangaDex account via `POST` / `DELETE /api/v1/downloads/mangadex/follows/{mangaId}`. The existing follow.txt button was relabeled to `follow.txt` to make the two distinct.

A second filter toggle **Hide titles already on MangaDex follow list** complements the existing *Hide titles already in any follow.txt*. It uses `GET /api/v1/downloads/mangadex/follows` to seed the local set once on mount (paginated server-side, capped at MangaDex's `limit=100`/page) and is part of the saved filter defaults (`m` field).

### Fix: AutoMetadataMatcher.match — type mismatch caused by positional argument

`match(series, onlyEnabled)` forwarded `onlyEnabled: Boolean` into `scan(series, searchTitle: String = series.name, onlyEnabled: Boolean = true)`'s positional `searchTitle` slot, causing a `Boolean` / `String` type mismatch in `compileKotlin`. Now uses the named argument `scan(series, onlyEnabled = onlyEnabled)`.

### Added: Plugin class-loader isolation — only the SPI is reachable from a plugin JAR

`PluginLoader` previously gave every plugin's `URLClassLoader` Komga's own classloader as parent, so a plugin could `import org.gotson.komga.domain.service.SeriesLifecycle` (or any other internal) and reach into the host. The parent is now a `SpiOnlyClassLoader` that whitelists `org.gotson.komga.infrastructure.plugin.api.*`, `java.*`, `javax.*`, `kotlin.*`, `kotlinx.*`, `com.fasterxml.jackson.*` and throws `ClassNotFoundException("Plugin denied access to '$name' — class-loader isolation. …")` for everything else. The four built-in plugins (`anilist-plugin`, `kitsu-plugin`, `metron-plugin`, `plugin-template`) already only touch the allowed packages, so nothing breaks; an external plugin that needs OkHttp, SQLite-JDBC etc. keeps working as long as it bundles those into its JAR (the plugin's own `URLClassLoader` serves them after the parent rejects). `PLUGINS.md`, the project `README.md` and `plugins/plugin-template/README.md` are updated with a "Security model" section that calls out the explicit limits: this is **class-loader isolation, not a sandbox** — `java.lang.reflect`, `java.io.File` and `java.net.http` are intentionally allowed, the SPI itself is the trust boundary, and a malicious external JAR still has Komga-process-level filesystem and network rights. Real isolation (SecurityManager / JPMS / out-of-process plugins) is explicitly out of scope.

### Fix: History page (`/history`) — browser lagged for seconds while titles popped in

`HistoryView.loadData()` fired one `getOneSeries` + one `getBook` per unique id in the visible page (up to 20 + 20 = 40 fire-and-forget HTTP calls) and `.push()`'d each result onto an array. Each push re-rendered the whole data table, and the per-cell `getSeries(id)` / `getBook(id)` lookups were `Array.find(x => x.id === id)` (O(n)) called four times per row × 20 rows × 40 push-cycles ≈ 64 000 linear scans. Worse, `loading = false` ran straight after `getAll()`, before any series/book fetch resolved — so the spinner cleared while titles popped in one by one. Now: caches are `Record<string, …>` (O(1) lookup), the per-page fetches run through one `Promise.all(Promise.allSettled(…))` and write the cache with a single object spread (2 reactive updates total instead of 40), and `loading = false` only flips after every fetch settles. Template / method signatures unchanged.

### Modified Files
| File | Path |
|------|------|
| `MangaDexSubscriptionSyncer.kt` | `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/MangaDexSubscriptionSyncer.kt` |
| `MangaDexApiClient.kt` | `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/MangaDexApiClient.kt` |
| `GalleryDlWrapper.kt` | `komga/src/main/kotlin/org/gotson/komga/infrastructure/download/GalleryDlWrapper.kt` |
| `DownloadController.kt` | `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/DownloadController.kt` |
| `AutoMetadataMatcher.kt` | `komga/src/main/kotlin/org/gotson/komga/infrastructure/automatch/AutoMetadataMatcher.kt` |
| `PluginLoader.kt` | `komga/src/main/kotlin/org/gotson/komga/infrastructure/plugin/PluginLoader.kt` |
| `PLUGINS.md` | `PLUGINS.md` |
| `README.md` | `README.md` |
| `plugin-template/README.md` | `plugins/plugin-template/README.md` |
| `SettingsFixes.vue` | `komga-webui/src/views/SettingsFixes.vue` |
| `DownloadDashboard.vue` | `komga-webui/src/views/DownloadDashboard.vue` |
| `HistoryView.vue` | `komga-webui/src/views/HistoryView.vue` |

### New Files
| File | Path |
|------|------|
| `FixRegistry.kt` | `komga/src/main/kotlin/org/gotson/komga/infrastructure/maintenance/FixRegistry.kt` |
| `MaintenanceController.kt` | `komga/src/main/kotlin/org/gotson/komga/interfaces/api/rest/MaintenanceController.kt` |

### Fix: Duplicate pages (unknown) — unresponsive when stepping through quickly

Acting on a single card (ignore / manual delete / auto delete) reloaded the entire page via `loadData()`, which re-fetched every remaining thumbnail and, combined with an index-based `v-for` key, forced Vue to re-render and re-download all visible cover images. Stepping through duplicates quickly therefore stalled after the first action. The card now removes only the acted-upon hash from the local list (a full reload happens only when the page empties out) and the `v-for` key is the stable `element.hash`, so untouched thumbnails are no longer re-fetched.

### Fix: MangaDex search — client-side filters produced sparse pages

With *Hide titles already in follow.txt* or *Only downloadable* enabled, filtering ran **after** a 24-result page was fetched while pagination still used MangaDex's unfiltered `total`. A search that yielded 8 visible results was split across e.g. two near-empty pages of 4 instead of one full page. Search now fetches additional batches per page until `searchPageSize` filtered results are collected (or the result set is exhausted), tracking the raw MangaDex offset per page for cursor-based pagination (`searchPageRawStart` / `searchKnownPages`). When no result-reducing filter is active the original deterministic offset pagination is kept unchanged. Bounded by `MAX_BATCHES` per page and the MangaDex `offset+limit ≤ 10000` cap.

### Fix: gallery-dl `-j` output truncated for mangas with many chapters

`getChapterInfo` and `fetchGalleryDlChapterMapping` captured the `gallery-dl -j` output with `appendBounded`, the helper used for progress/error streams — which, past a 512 KB cap, **drops the front half of the buffer**. For a title with hundreds of chapters the dumped JSON exceeded that cap, so the captured string began mid-document and Jackson failed with `MismatchedInputException`. The result was lost metadata (title/genres) and no chapter mapping — and, since the chapter count came from this parse, no progress total in the WebUI for big non-MangaDex series (smaller listings like mangahere stayed under the cap). JSON output now uses a dedicated `appendJson` helper that never front-drops (bounded only by a 32 MB OOM guard); progress/error streams keep the old tail-bounded behaviour.

### Fix: non-MangaDex download — slow resume, scattered gaps, leftover folders

Resuming a large non-MangaDex series re-visited every already-done chapter (`gallery-dl --download-archive` only skips re-downloading *images*, and only after opening each chapter page — ~1–2 s each), downloading nothing but taking many minutes, while the progress counter restarted at 0. Broken/partial chapters could also sit anywhere in the run and a range-based resume never went back for them. Komga can't use the DB here (an interrupted series isn't scanned yet), so resume now works purely from the **CBZ files on disk**:

- A chapter is *done* only if its CBZ carries a chapter `<Number>` in the **source-written ComicInfo.xml** (the gallery-dl `komga` postprocessor adds it once the chapter fully finishes). Keying resume off this metadata — not the CBZ filename — means a new site needs no Komga change (no per-site naming entry); broken/partial CBZs are detected and deleted, and leftover chapter image folders (stray page images or a half-downloaded `.part`) are removed too, forcing a clean re-download.
- Komga feeds gallery-dl **only the still-missing chapter URLs** via `-i`, fixing gaps **anywhere** in the run without re-visiting completed chapters. The highest complete chapter is re-downloaded as well, so a chapter that was finishing at the interruption point is never left half-done. The progress counter is seeded from the done-CBZ count.
- If the chapter list can't be enumerated, it falls back to the whole-manga bulk command.

### Fix: bogus `mangadex.org` URL in ComicInfo of non-MangaDex chapters; downloads stuck at 100 %

After download, Komga **re-injected** ComicInfo over the correct file the gallery-dl `komga` postprocessor had already written. Its chapter lookup was keyed by `chapter_id` (`chapter-648`) while the files are named `c648`, so every CBZ fell through to a MangaDex lookup that hardcoded `<Web>https://mangadex.org/chapter/…</Web>` even for non-MangaDex sources (manhuaplus → `…/chapter/c`) and fired hundreds of MangaDex API calls (404s) that stalled the download at 100 % so it never reached `COMPLETED`/scan. Komga's redundant injection is removed — it now trusts the postprocessor's ComicInfo. (The companion `gallery-dl-komga` fork now writes the real chapter URL into `<Web>` and the chapter release date for all sites, not just MangaDex.)

### Improved: Duplicate pages — instant page switching

Unknown-duplicate thumbnails were regenerated from the archive on disk on **every** request (unlike book posters, which are stored in the DB), so paging through them on an HDD took several seconds per page even after a browser-only prefetch. Three changes make switching near-instant:

- **Server-side cache** (`PageHashLifecycle.getPage`): the generated (optionally resized) page bytes are cached in a Caffeine cache keyed by `hash|resize` (max 500 entries, 30 min after access). The page image for a hash is immutable, so this is always safe. A prefetch now warms this cache; the real display request hits it without touching disk.
- **`Cache-Control: private, max-age=7d`** on the `unknown/{hash}/thumbnail` endpoint, so the browser also keeps prefetched images and revisiting a page costs no request at all.
- **Prefetch keeps image references** (`prefetchImages`, a frozen array): `new Image()` loads are no longer garbage-collected mid-flight, so the prefetch reliably completes while you view the current page.

### Improved: MangaDex advanced search — persisted preferences

- **Sort by + Direction are now part of "Save as default"** — `searchOrder` / `searchOrderDir` are included in the saved filter payload (`o` / `od`) and restored on load, and changing either now marks the defaults dirty.
- **Target library is remembered** — the selected target library is stored in `localStorage` (`komga.fork.mangadexsearch.targetlibrary`) and restored on next visit (falls back to the first library if the stored one no longer exists).

### Improved: Smaller jar — drop source maps

`productionSourceMap: false` in the webui build stops bundling ~15 MB of `.js.map` source maps into `BOOT-INF/classes/public/js/` of the production jar. Source maps only help when debugging minified JS in the browser; they are not needed for a self-hosted server.

| Modified File | Change |
|---------------|--------|
| `komga-webui/.../views/DuplicatePagesUnknown.vue` | Remove acted hash locally instead of full reload; stable `element.hash` `v-for` key; prefetch next page's thumbnails (refs retained to avoid GC) |
| `komga-webui/.../components/PageHashUnknownCard.vue` | Emit acted hash on `created` so the parent can drop just that card |
| `domain/service/PageHashLifecycle.kt` | Caffeine cache for generated page thumbnails (keyed by `hash\|resize`) so prefetch + display don't re-read from disk |
| `interfaces/api/rest/PageHashController.kt` | `Cache-Control: private, max-age=7d` on the unknown-thumbnail endpoint |
| `infrastructure/download/GalleryDlWrapper.kt` | `appendJson` (no front-drop) for `-j` output; non-MangaDex resume from on-disk CBZs (done = chapter `<Number>` in the source-written ComicInfo, **not** the filename) — deletes broken CBZs + leftover `.part`/image folders, re-downloads only the missing chapters (plus the highest complete one) via `-i`, progress seeded from done count; **removed** the post-download ComicInfo re-injection that overwrote the postprocessor's file with a bogus mangadex URL |
| `infrastructure/download/ComicInfoGenerator.kt` | `readChapterNumber` — reads `<Number>` from a CBZ's ComicInfo so resume keys off metadata, not the filename |
| `komga-webui/.../views/DownloadDashboard.vue` | Fetch-to-fill cursor pagination when result-reducing filters are active; sort + direction saved in defaults; target library persisted in localStorage |
| `komga-webui/vue.config.js` | `productionSourceMap: false` — stop bundling ~15 MB of source maps |

### Fix: Auto Metadata Match ran even when disabled

`AutoMetadataMatcher.isEnabled()` read a `PLUGIN_CONFIG` key (`auto-metadata.enabled`) that the Plugin Manager toggle never writes — the toggle sets `PLUGIN.enabled`. Disabling the plugin in the UI therefore had no effect and new-series auto-match kept running. It now reads the real plugin state via `pluginRepository.findByIdOrNull(...)?.enabled`, consistent with the scrobblers and the subscription syncer.

### Cleanup: dead code + orphan DB tables removed

A full audit of the fork code (every private helper, model class, DTO and migration table cross-checked for real callers) removed unused artefacts:

- Dead functions `getMangaDexChapterCount` (wrapper + impl) and `evictPluginConfigCache` — zero callers.
- Dead model classes `UpdateCheck`, `UserBlacklist`, `BlacklistType` — never instantiated (unrelated to the active chapter-blacklist `BlacklistedChapter`).
- Orphan tables `UPDATE_CHECK`, `USER_BLACKLIST`, `PLUGIN_PERMISSION` — created by the original plugin_system migration, never read/written by any DAO.
- Stray `stale.yml.bak` backup file.

| Modified File | Change |
|---------------|--------|
| `infrastructure/automatch/AutoMetadataMatcher.kt` | `isEnabled()` reads `PLUGIN.enabled` (via `pluginRepository`) instead of a stale `PLUGIN_CONFIG` key |
| `infrastructure/download/GalleryDlWrapper.kt` | Removed dead `getMangaDexChapterCount` wrapper + `evictPluginConfigCache` |
| `infrastructure/download/MangaDexApiClient.kt` | Removed dead `getMangaDexChapterCount` implementation |
| `domain/model/DownloadQueue.kt` | Removed never-instantiated `UpdateCheck`, `UserBlacklist`, `BlacklistType` |
| `db/migration/fork/sqlite/V20260529000000__drop_orphan_plugin_tables.sql` | New — drops orphan `UPDATE_CHECK`, `USER_BLACKLIST`, `PLUGIN_PERMISSION` |

### Fix: Follow-list check silently dropped non-MangaDex URLs

The periodic follow.txt scan (`ChapterChecker`) bailed out on any non-MangaDex URL with "Not a MangaDex URL" and never queued it, so manhuaplus/mangahere/… entries were silently ignored — contradicting the README ("other sites use gallery-dl"). Non-MangaDex URLs are now queued for download; the download resume (which keys off the on-disk CBZ `<Number>`) re-downloads only the missing chapters, so a fully-downloaded series loads nothing.

A reliable up-front "already have it?" check is **not** possible for these sites: they have no stable identifier — the URL domain changes (`mangahere.cc` → `.com`) and titles change for fresh releases — so neither URL nor title reliably maps a follow.txt entry back to a local series. The on-disk CBZ `<Number>` (used by the resume) is the single source of truth, so the checker queues unconditionally and lets the resume decide what (if anything) to download.

Separately, `fetchGalleryDlChapterMapping` (used by the download **resume**) is now keyed by the always-present, always-unique `chapterUrl` with `chapter_id` optional (`ChapterDownloadInfo.chapterId` nullable) — `chapter_id` only exists in MangaDex/Madara listings, so the resume now resolves the chapter list for every gallery-dl source instead of only Madara-based ones.

### Fix: "new chapters" endpoint returned 0 for non-MangaDex series

`GET /series/{id}/new-chapters` extracted a MangaDex ID and returned `availableCount=0` for anything else. It now branches on `mangaId` and uses the same gallery-dl mapping for non-MangaDex series.

### Fix: Pause button returned HTTP 400

`DownloadController.performAction` had no `"pause"` case, so the WebUI's pause button (which posts `{action:"pause"}`) got a 400. Added `pauseDownload` to `DownloadExecutor` — it kills the running gallery-dl subprocess and sets status `PAUSED`; `resume` re-queues and (via the on-disk resume logic) downloads only the missing chapters.

| Modified File | Change |
|---------------|--------|
| `domain/service/ChapterChecker.kt` | `checkNonMangaDexUrl` — queues non-MangaDex follow.txt URLs unconditionally (no stable identifier for a reliable pre-check; the on-disk resume handles dedup) |
| `interfaces/api/rest/ChapterUrlController.kt` | `getNewChapters` branches on `mangaId`; non-MangaDex series use the gallery-dl mapping |
| `domain/service/DownloadExecutor.kt` | New `pauseDownload` (kill subprocess + status `PAUSED`) |
| `interfaces/api/rest/DownloadController.kt` | `performAction` handles `"pause"` |
| `infrastructure/download/GalleryDlWrapper.kt` | `fetchGalleryDlChapterMapping` keyed by `chapterUrl` (no longer requires `chapter_id`); `ChapterDownloadInfo.chapterId` nullable — works for every gallery-dl source |

### Fix: Auto Metadata Match searched by folder name instead of title

`AutoMetadataMatcher.scan` searched providers with `series.name` — the folder name. With `folder_naming: uuid` the folder is a UUID string, so the provider search ran against the UUID and never matched. It now searches with the series' metadata title (`SeriesMetadata.title`, populated from the ComicInfo `<Series>` even for UUID folders) and falls back to `series.name` only when the title is blank.

| Modified File | Change |
|---------------|--------|
| `infrastructure/automatch/AutoMetadataMatcher.kt` | `scan` takes a `searchTitle` param (default `series.name`); provider search + title scoring use it |
| `infrastructure/automatch/AutoMetadataApplier.kt` | passes `searchTitle = meta.title ?: series.name` to `scan` |

---

## [0.1.4.3] - 2026-05-27

### New: gallery-dl-komga fork + komga postprocessor

**gallery-dl fork:** All Dockerfiles and documentation now reference [gallery-dl-komga](https://github.com/08shiro80/gallery-dl-komga) (a Komga-specific fork of gallery-dl) instead of upstream PyPI `gallery-dl`. The fork adds:
- New `komga` postprocessor: injects ComicInfo.xml into CBZ archives and writes series.json during the gallery-dl download itself — no extra API calls required

**komga postprocessor integration:**
- `GalleryDlProcess` adds the `komga` postprocessor (after `zip`); series.json from gallery-dl itself is disabled since `GalleryDlWrapper` writes a richer version
- `GalleryDlWrapper` skips its own ComicInfo.xml injection when the postprocessor already added one (via `hasComicInfoXml()`)

### New: Settings → Fixes page

GUI-triggered one-time maintenance actions live under **Settings → Fixes**. Cards are versioned and removed once no longer needed.

- **Re-inject ComicInfo.xml** — regenerates `ComicInfo.xml` + `series.json` for every CBZ in the selected library from MangaDex metadata. Enable Force to overwrite existing ComicInfo. Useful when MangaDex metadata has changed, when CBZs are missing ComicInfo, or to migrate libraries that ran an older fork build with different ComicInfo layout.
  - Runs fully in the background: after **Run** the job continues server-side even if you navigate away. Returning to the Fixes page re-attaches to the running job (resumes the progress display) and shows the last finished result when idle.

### New: External plugin loading (install plugin JARs)

Until now the "Install Plugin" dialog in the Plugin Manager was a no-op — plugins could only be the ones compiled into Komga and registered at startup (`PluginInitializer`). The fork now supports **real dynamic plugin JARs** that load at runtime, alongside the built-in plugins. Both coexist: built-ins stay as before, external JARs are an additive extension point.

- **Plugin SPI** (`org.gotson.komga.infrastructure.plugin.api`): a small, stable contract third parties compile against — `KomgaPlugin` (base), `MetadataProviderPlugin` (search + metadata) and `NotifierPlugin` (event hooks), with plain DTOs (`PluginSearchResult`, `PluginMetadataDetails`, `PluginAuthor`, `PluginNotification`) and a `PluginContext` (config map + logging). A plugin declares its implementation class(es) in `META-INF/services/org.gotson.komga.infrastructure.plugin.api.KomgaPlugin` so it is discoverable via `ServiceLoader`.
- **Isolated class loading** (`PluginLoader`): each JAR is loaded in its own `URLClassLoader` whose parent is Komga's loader (so the plugin sees the SPI + Kotlin stdlib but stays in its own namespace). Loading failures are reported as `PluginLoadException`, never crash startup.
- **Registry** (`PluginRegistry`): on `ApplicationReadyEvent` it scans `${komga.config-dir}/plugins/*.jar`, loads each, registers it in the existing `plugin` DB table (reusing the stored `enabled` flag so toggles survive a restart), and calls `initialize(context)`. `install()` writes an uploaded JAR to the plugins dir and loads it live; `uninstall()` calls `shutdown()`, closes the class loader, and deletes the JAR.
- **Metadata bridge**: a loaded `MetadataProviderPlugin` is adapted to the internal `OnlineMetadataProvider`, so external metadata plugins appear in the "Search Online Databases" dialog and work with the existing `{id}/search`, `{id}/metadata/{externalId}` and `apply-metadata` endpoints with no extra wiring.
- **Notifier hook**: the registry listens for `DomainEvent.DownloadCompleted` / `DownloadFailed` and dispatches a `PluginNotification` (`DOWNLOAD_COMPLETED` / `DOWNLOAD_FAILED`) to every enabled `NotifierPlugin`. Failures in a notifier are logged and never affect the download flow.
- **Plugin template** (`plugin-template/`) + guide (`PLUGINS.md`): a standalone Gradle project with example metadata + notifier plugins. It mirrors the SPI locally (excluded from the built JAR) so authors only write their code and run `./gradlew build` — no Komga artifact needed on the classpath.
- **REST**: `POST /api/v1/plugins/install` (multipart) accepts a `file` (JAR upload) **or** a `url` (http/https download). `DELETE /api/v1/plugins/{id}` now also unloads + deletes the JAR for external plugins. Admin-only (the whole controller requires `ROLE_ADMIN`).
- **UI**: the Plugin Manager's Install dialog is now functional — pick a `.jar` or paste a URL, and the plugin loads immediately and shows up in the list.

**Security note:** installing a plugin runs arbitrary code inside the Komga JVM. There is no sandbox (the JVM `SecurityManager` is removed in modern JDKs), so the endpoint is restricted to admins. Only install plugins you trust.

### New: Auto-Match, Scrobbler, Metron (cherry-picked from jackohagan94-afk's v2.0 branch)

Cherry-picked feature bundle originally authored by [jackohagan94-afk](https://github.com/jackohagan94-afk). Their PR was withdrawn upstream — integrated here with their attribution preserved on the new plugins.

**New plugins (all disabled by default; Auto Metadata Match is enabled but hidden from the Search Online Databases dialog):**
- **Metron Metadata Provider** — fetches comic series metadata from metron.cloud (free account required).
- **Manga Scrobbler** (AniList / MyAnimeList / Kitsu / MangaDex) — pushes read progress when a book is marked completed. OAuth2 auto-refresh for MAL/Kitsu.
- **Comic Scrobbler** (Metron) — pushes Western comic issue progress.
- **Auto Metadata Match** — Komf-style: walks a configured provider priority (`anilist,mangadex,kitsu` by default), scores candidates by normalized-title Jaccard similarity, writes `web_url` + `tracker_links` to series.json. Registered as `PluginType.PROCESSOR` so it does NOT appear in the per-series "Search Online Databases" dialog (where it would not do anything useful). Bulk-run via `POST /api/v1/automatch/libraries/{id}`.

**Infrastructure:**
- New DB table `sync_state` (migration `V20260511000000`) tracks per-tracker submission state to avoid duplicate scrobbles
- `TrackerLinkEnricher` writes multi-source tracker URLs into SeriesMetadata
- `AutoMatchSeriesMetadata` task type + `TaskEmitter.autoMatchSeriesMetadata()` for queued matching
- `RefreshSeriesMetadata` now best-effort runs auto-match inline (gated on plugin enabled + no existing link) before invoking the lifecycle refresh — no extra queued refresh

**Mylar / status integration:**
- `MylarSeriesProvider` now reads `web_url` (single URL) and `tracker_links` (list of `{label,url}`) from series.json and produces one WebLink per tracker. Falls back to the legacy MangaDex-UUID `comicid` link.
- `MylarMetadata` DTO gains `tracker_links: List<TrackerLinkEntry>`
- `Status` enum understands AniList/Kitsu uppercase strings (`RELEASING`, `ONGOING`, `COMPLETED`, `NOT_YET_RELEASED`, …) without custom mapping
- `PluginController` writes a provider-aware `web_url` when a metadata plugin applies a result with an `externalId` + optional `provider` hint

### New: MangaDex search on Downloads page

Inspired by [beaux](https://github.com/beaux)'s `komga-enhanced` branch — re-implemented to reuse the existing `MangaDexMetadataPlugin.search()` (respects rate limiter + plugin content-rating config) instead of adding a separate `MangaDexProxyController`. UI sits at the top of `DownloadDashboard`:

- Search MangaDex by title, pick a target library once via a dropdown
- Per-result actions: **Download** (queues a new download) and **Follow** (appends `https://mangadex.org/title/<id>` to that library's `follow.txt` — dedupes if the URL is already there)
- **Advanced filters** (collapsible panel): multi-select for *Include tags*, *Blacklist tags* (MangaDex `excludedTags[]`), Status, Content Rating, Publication Demographic, plus an *Only titles with downloadable chapters* toggle. Server-side `hasAvailableChapters=true` is too loose (counts external-link / 0-page chapters as "available"), so when this toggle is on the UI does an additional batch call to `POST /api/v1/plugins/mangadex-metadata/downloadable-check` which inspects each candidate's `/manga/{id}/feed` for at least one chapter with `externalUrl == null` AND `pages > 0` in the preferred language. **Trade-off:** 1 extra MangaDex API call per uncached result (24h server-side cache). With the toggle off there is zero extra cost. With no title and any filter set the button switches to "Browse" and queries MangaDex sorted by `followedCount desc`.
- **Persistent filter defaults**: a "Save as default" button stores the current filter combination in your **Komga account** (user client-setting `komga.fork.mangadexsearch.defaults`), so the defaults follow you across browsers and devices. A previously saved per-browser `localStorage` value is migrated automatically on first load. On next visit the panel pre-fills with those values — set a permanent tag blacklist once and forget it. "Clear all" resets the current panel.
- **Pagination** — 24 results per page with a `v-pagination` control underneath. `total` is reported by MangaDex (often tens of thousands when filters are loose). MangaDex caps `offset+limit ≤ 10000`, so the UI clamps to 417 pages max at the default 24/page.
- **Tag catalog cached for 7 days in browser localStorage** (`komga-fork.mangadex-tags-cache`) — first visit hits `GET /api/v1/plugins/mangadex-metadata/tags`, subsequent visits use the local copy. Server-side in-memory cache (`getTags()`) survives until the JVM restarts. MangaDex changes its tag list only a handful of times per year.
- **Follow button is a toggle** — already-followed titles show `Following` (success-coloured); clicking again locates the line in whichever library's `follow.txt` contains it and removes it. New follows still go to the currently-selected target library.
- Cover images use a native `<img>` with `referrerpolicy="no-referrer"` to bypass MangaDex's anti-hotlinking (which otherwise replaces the cover with a "read on mangadex" placeholder when accessed from a non-localhost origin).
- Theme-neutral (uses Vuetify `primary`, not hardcoded blue)
- Status chips colored via the same enum mapping the rest of the fork uses (ongoing/releasing → primary, completed/ended/finished → success, hiatus → warning, cancelled → error)

New backend endpoints on the MangaDex plugin:
- `GET /api/v1/plugins/mangadex-metadata/tags` — returns cached MangaDex tag catalog (id, name, group) for the multi-select picker.
- `POST /api/v1/plugins/mangadex-metadata/search-advanced` — body `{ query?, includedTagIds[], excludedTagIds[], status[], contentRating[], publicationDemographic[], hasAvailableChapters?, offset?, limit? }`. Returns `{ data: MetadataSearchResult[], total, offset, limit }` (paginated). Default `limit=24`, MangaDex caps `offset+limit ≤ 10000`. Empty query → `order[followedCount]=desc`.
- `POST /api/v1/plugins/mangadex-metadata/downloadable-check` — body `{ language, ids[] }` → `{ uuid: boolean }`. Per-id 24h cache in `MangaDexMetadataPlugin.downloadableCache`. Returns true iff at least one chapter in `language` has `externalUrl == null` and `pages > 0`.

**API rate-limit note:** All search calls (basic and advanced) go through the existing `MangaDexMetadataPlugin` against `api.mangadex.org` and count against MangaDex's global rate limits (~5 req/sec). Heavy search/browse will throttle (HTTP 429) for a few seconds before recovering — same behaviour as the rest of the fork's MangaDex traffic.

### Fix: Re-inject ComicInfo crashed on CBZ files with STORED entries + EXT descriptor

`ComicInfoGenerator.injectComicInfo` used `ZipInputStream`, which throws `ZipException: only DEFLATED entries can have EXT descriptor` on CBZ files repackaged by third-party tools. A single such file in a library would abort `repairMissingComicInfo` mid-loop and surface as a `504` from the Settings → Fixes UI. Rewrote `injectComicInfo`, `injectComicInfoWithRetry`, and `hasComicInfoXml` to use `ZipFile` instead, which tolerates that combination.

### Other adjustments

- **Shared MangaDex credentials across plugins**: `gallery-dl Downloader` is now the single source of truth for MangaDex authentication. Its schema gains `mangadex_client_id` and `mangadex_client_secret` alongside the existing `mangadex_username` / `mangadex_password`. **MangaDex Subscription Sync** and **Manga Scrobbler** auto-fall back to those values when their own equivalent fields are blank. Both `client_id/client_secret/username/password` on Subscription Sync and all four `mangadex_*` fields on the scrobbler are now optional. Resolution order: plugin's own field → gallery-dl → (scrobbler only) Subscription Sync.
- **gallery-dl Downloader — chapter naming template**: new optional `chapter_naming` config field accepts a gallery-dl `directory` template string and applies it to all configured sites. Empty value keeps the per-site defaults. NOTE: `ChapterMatcher` still expects a `c<num>` token to extract chapter numbers from CBZ filenames — keep that in your template.
- **DB hygiene — daily cleanup of log/event tables**: two new scheduled jobs run daily and delete entries older than 30 days:
  - `PluginLogCleanupController` prunes `PLUGIN_LOG` (was unbounded; observed 119k+ rows in one install).
  - `HistoricalEventCleanupController` prunes `HISTORICAL_EVENT` + `HISTORICAL_EVENT_PROPERTIES` together (no FK cascade in schema, so the DAO deletes properties first then events).
- **Removed `updateExistingCbzChapterUrls` from gallery-dl download path**: 71-LOC function that re-walked every existing CBZ in a series on every download and re-injected missing ComicInfo.xml. The name lied (it never touched chapter URLs anymore), the work was redundant with the Re-inject ComicInfo card under Settings → Fixes, and it slowed down resumed downloads. Removed the function and its call site.

| New / Modified File | Change |
|---------------------|--------|
| `infrastructure/download/GalleryDlProcess.kt` | `komga` postprocessor; `chapter_naming` template |
| `infrastructure/download/GalleryDlWrapper.kt` | Skip ComicInfo if already present; `repairMissingComicInfo` `forceReinject`; `updateExistingCbzChapterUrls` removed |
| `infrastructure/download/MangaDexApiClient.kt` + `ComicInfoGenerator.kt` | All MangaDex tags in `<Genre>` |
| `interfaces/api/rest/DownloadController.kt` | `repair-comicinfo` `?force=true` + async status |
| `komga-webui/.../SettingsFixes.vue` + `router.ts` + `HomeView.vue` | NEW Fixes page — Re-inject ComicInfo, runs in background (resume on return) |
| `interfaces/scheduler/PluginLogCleanupController.kt` (7d) + `HistoricalEventCleanupController.kt` (30d) | NEW daily retention; `deleteOlderThan` on the log/event repos+DAOs |
| `infrastructure/automatch/*` | NEW auto-match (TitleNormalizer/Matcher/Applier/EventListener/SeriesJsonWriter). Runs for new series + explicit bulk only; `tracker_links` require a strong title match |
| `interfaces/api/rest/AutoMatchController.kt` + `application/tasks/*` | NEW bulk-match endpoint + `AutoMatchSeriesMetadata` task |
| `infrastructure/scrobbler/*` + `comicscrobbler/*` | NEW Manga/Comic scrobblers (`SCROBBLER` type); shared-MangaDex-credential fallback |
| `infrastructure/metadata/tracker/TrackerLinkEnricher.kt`, `rate/MetronRateLimiter.kt`, `sync_state` (model/repo/DAO + migration `V20260511000000`) | NEW |
| `infrastructure/metadata/mylar/*` | Multi-tracker WebLinks; `tracker_links` field; AniList/Kitsu status aliases |
| `komga-webui/.../DownloadDashboard.vue` | NEW MangaDex search card: Download/Follow, advanced filters, pagination, tag cache, **Sort by**, description dialog; "Save as default" stored in account |
| `infrastructure/plugin/api/KomgaPlugin.kt` | NEW external-plugin SPI (`KomgaPlugin`/`MetadataProviderPlugin`/`NotifierPlugin`/`PluginContext`/`configSchema` + DTOs) |
| `infrastructure/plugin/PluginLoader.kt` | NEW per-JAR `URLClassLoader` + `ServiceLoader` |
| `infrastructure/plugin/PluginRegistry.kt` | NEW scan/install/uninstall of `${config-dir}/plugins`; bundles & refreshes default plugins on startup; `OnlineMetadataProvider` bridge; notifier dispatch on download events |
| `interfaces/api/rest/PluginController.kt` + `dto/PluginDto.kt` | `POST /plugins/install` (file/URL); dynamic provider resolution; provider-aware `web_url`; `external` flag (built-ins can't be uninstalled); apply-metadata/apply-cover |
| `komga-webui/.../PluginManager.vue` | Install upload; Auto Metadata Match GUI config (ordered providers + library multiselect); uninstall hidden for built-ins |
| `application/startup/PluginInitializer.kt` + `domain/model/Plugin.kt` | Built-in defs (gallery-dl/MangaDex/scrobblers/auto-metadata/subscription); syncs metadata on startup; `SCROBBLER` type; Subscription Sync is `PROCESSOR` |
| `plugins/kitsu-plugin`, `plugins/anilist-plugin`, `plugins/metron-plugin` | NEW — Kitsu/AniList/Metron as default external plugins (built-ins removed); MangaDex stays built-in |
| `plugins/plugin-template` + `PLUGINS.md` | NEW authoring template + guide |
| `settings.gradle` + `komga/build.gradle.kts` | Auto-discover `plugins/*` as `:plugins:<name>` (template skipped); bundle all `:plugins:*` jars as default plugins |
| `infrastructure/jooq/main/PageHashDao.kt` + `domain/model/PageHashUnknown.kt` + `dto/PageHashUnknownDto.kt` | Unknown duplicate pages expose `seriesTitle` (read-only, no migration) |
| `komga-webui/.../DuplicatePagesUnknown.vue` + `PageHashUnknownCard.vue` | Sort by Manga/Series; show series title; fixed-width cards |
| `komga-webui/.../EditSeriesDialog.vue` | Search Online covers use native `<img referrerpolicy=no-referrer>` |
| `komga/docker/Dockerfile.local` + `Dockerfile.tpl` | Use `gallery-dl-komga` fork |
| `README.md` + UI strings | Install/plugins/VACUUM docs; all UI text in English |

---

## [0.1.4.2] - 2026-05-02

### New Features
- **Per-library default book sort** — Each library can now define a default sort field and direction for books within a series. Configured in Library Settings → Options → "Default book sort" (field + direction). The server-side default applies whenever a series is opened with no active URL sort parameter and no device-local sort preference stored. If the user explicitly changes the sort for a library, that preference is remembered in localStorage and takes priority over the library default until the user resets it. Clicking "Reset" clears the stored preference and reverts to the library default. The sort indicator icon is only highlighted when the active sort differs from the library default — opening a series with the library default applied does not trigger the orange indicator.

| Modified/New Files | Purpose |
|-------------------|---------|
| `db/migration/fork/sqlite/V20260502000000__library_default_book_sort.sql` | Adds `DEFAULT_BOOKS_SORT_FIELD` / `DEFAULT_BOOKS_SORT_ORDER` columns to `LIBRARY` table |
| `domain/model/Library.kt` | `BookSortField` and `BookSortOrder` enums + two new fields |
| `interfaces/api/rest/dto/BookSortFieldDto.kt` | New — DTO enum + `toDomain()`/`toDto()` |
| `interfaces/api/rest/dto/BookSortOrderDto.kt` | New — DTO enum + `toDomain()`/`toDto()` |
| `interfaces/api/rest/dto/LibraryDto.kt` | New fields in API response |
| `interfaces/api/rest/dto/LibraryCreationDto.kt` | New fields with defaults (`NUMBER`/`ASC`) |
| `interfaces/api/rest/dto/LibraryUpdateDto.kt` | New nullable fields for PATCH |
| `interfaces/api/rest/LibraryController.kt` | Maps new fields in `addLibrary` + `updateLibraryById` |
| `infrastructure/jooq/main/LibraryDao.kt` | Persists and reads new DB columns |
| `komga-webui/src/types/enum-libraries.ts` | `BookSortFieldDto` + `BookSortOrderDto` enums |
| `komga-webui/src/types/komga-libraries.ts` | New fields in `LibraryDto`, `LibraryCreationDto`, `LibraryUpdateDto` |
| `komga-webui/src/locales/en.json` | i18n for `book_sort_field.*`, `book_sort_order.*`, dialog label |
| `komga-webui/src/components/dialogs/LibraryEditDialog.vue` | Two dropdowns in Options tab; form reset/submit wired |
| `komga-webui/src/views/BrowseSeries.vue` | Library default applied in `loadSeries`; `resetSortAndFilters` clears stored pref; `sortOrFilterActive` compares against library default |

> **Note:** Run `./gradlew jooq-codegen-primary` after applying the migration to regenerate the jOOQ DSL before compiling.

---

## [0.1.4.1] - 2026-04-26

### Upstream Fixes (from Komga 1.24.4)

| File | Change |
|------|--------|
| `infrastructure/mediacontainer/epub/Nav.kt` | EPUB TOC: XML parser for correct TOC handling |
| `infrastructure/kobo/KoboProxy.kt` | Kobo: request body proxied as `ByteArray` |
| `interfaces/api/kosync/KoreaderSyncController.kt` | KOReader: also accepts `application/json` |
| `interfaces/api/opds/v2/Opds2Controller.kt` | OPDS2: `series/latest` navigation link fix |
| `interfaces/api/OpdsGenerator.kt` | OPDS2: auth logo URL correct with base URL |
| `interfaces/api/rest/dto/UserDto.kt` | API: `ageRestriction` is hidden instead of sent as `null` |

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
