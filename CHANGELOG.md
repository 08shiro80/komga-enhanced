# Changelog

All notable changes to **komga-enhanced** fork plugins authored by **Jack O'Hagan** (trackers, auto-metadata match, Metron metadata) are documented here.

## [Unreleased]

### Planning summary (this iteration)

**Manga tracker stack (`manga-scrobbler`, sync puller)**  
Goal: align ID resolution between push and pull, survive restarts without redundant API traffic, and keep configuration DRY.

**Comic tracker (`comic-scrobbler`)**  
Goal: share Metron HTTP behavior with the Metron metadata plugin (timeouts, base URL), reducing hung worker threads on slow API responses.

**Auto Metadata Match (`auto-metadata`)**  
Goal: allow library-scoped opt-out, separate confidence for “winning metadata” vs “extra tracker URLs”, and clearer bulk-queue reporting.

**Metron Metadata Provider (`metron-metadata`)**  
Goal: reduce log noise during routine searches and use the same HTTP defaults as the comic scrobbler.

---

### Added

- **`TrackerIdResolver`** (`org.gotson.komga.infrastructure.scrobbler`): centralizes manga tracker ID resolution from `SeriesMetadata.links` plus optional `mappings` JSON. Used by **`MangaScrobblerPlugin`** and **`MangaSyncPullerPlugin`** so remote pull and local push never disagree on parsing rules.
- **`MetronHttp`** (`org.gotson.komga.infrastructure.metadata.metron`): builds a shared **`RestClient`** for `https://metron.cloud` with a **90s read timeout** (via `JdkClientHttpRequestFactory`). Used by **`MetronMetadataPlugin`** and **`ComicScrobblerPlugin`**.
- **Auto Metadata Match — `exclude_library_ids`**: comma-separated Komga **library IDs** where auto-match must not run (new-series enqueue, synchronous apply, refresh hook, and bulk queue filtering). Bulk response includes **`skippedExcludedLibrary`** count.
- **Auto Metadata Match — `min_score_tracker_links`**: optional floor (0.0…`min_score`) for extra entries in `series.json` **`tracker_links`**. If unset, defaults to **`min_score - 0.08`**, clamped to never exceed `min_score`. Primary provider selection still uses **`min_score`** only.
- **`AutomatchScanResult`** / **`AutoMetadataMatcher.scan()`**: single pass over provider priority produces both the **primary** match and the **tracker link** candidate list under the two thresholds above.
- **Plugin version constants** in `PluginVersions`: **`AUTO_METADATA`**, bumps for **`MANGA_SCROBBLER`**, **`COMIC_SCROBBLER`**, **`METRON_METADATA`**. `PluginInitializer` wires **`AUTO_METADATA`** for the auto-metadata plugin row.

### Changed

- **`MangaScrobblerPlugin`**: on startup when the plugin is **enabled**, **hydrates** the in-memory `lastSynced` map from **`sync_state`** (all trackers) so restarts do not re-fire scrobbles for chapters already recorded.
- **`MangaSyncPullerPlugin`**: uses **`TrackerIdResolver`**; removes duplicated regex/mapping logic.
- **`AutoMetadataApplier`**: respects **library exclusion** before reading metadata; uses **`scan()`** for primary + supplementary tracker URLs.
- **`AutoMetadataEventListener`**: skips enqueue when the new series’ library is excluded.
- **`AutoMatchController`**: bulk library queue filters excluded libraries; **`POST .../queue`** returns **400** if the series’ library is excluded.
- **`MetronMetadataPlugin`**: routine “searching / fetching” logs moved from **INFO** to **DEBUG**; uses **`MetronHttp.restClient()`**.
- **`ComicScrobblerPlugin`**: uses **`MetronHttp.restClient()`** instead of a raw `RestClient.create(...)`.

### Fixed (prior + related)

- **Kitsu JSON:API** requests in **`MangaScrobblerPlugin`**: `Content-Type` **`application/vnd.api+json`** must be set via **`MediaType.parseMediaType(...)`** (Spring rejects a single-token `MediaType(...)` constructor for that string).
- **`MangaSyncPullerPlugin`** (earlier iteration): **`SyncState.seriesId`** is the **Komga** series id; external IDs are resolved from metadata, not by parsing the TSID as an AniList id.

---

### Upgrade / ops notes

1. **Rebuild the Docker image** from repo root:  
   `docker build --no-cache --pull -t komga-enhanced:comic-scrobbler .`  
   Then recreate the container:  
   `docker compose up -d --force-recreate`
2. **Existing `plugin` rows** in the DB are not auto-migrated to new `version` strings by this code path alone; the changelog versions reflect **defaults for new installs** and **`PluginVersions`** constants used in `PluginInitializer`.
3. **Verify locally** (when `JAVA_HOME` / Gradle are available):  
   `./gradlew :komga:test --tests "org.gotson.komga.infrastructure.metadata.mylar.MylarSeriesProviderTest"`  
   and a full `:komga:compileKotlin` / `:komga:test` before release.

### Suggested commit message (GitHub)

```
feat(plugins): tracker + automatch + Metron reliability (Jack O'Hagan)

- Add TrackerIdResolver; hydrate manga scrobbler lastSynced from sync_state
- MetronHttp shared client (90s read timeout) for Metron metadata + comic scrobbler
- Auto-metadata: library exclude list, secondary score for tracker_links, scan()
- Bump plugin versions (AUTO_METADATA, manga/comic scrobbler, Metron metadata)
- CHANGELOG for fork plugins
```
