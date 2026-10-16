# Komga Enhanced

[![Docker Pulls](https://img.shields.io/docker/pulls/08shiro80/komga)](https://hub.docker.com/r/08shiro80/komga)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21+-blue)](https://adoptium.net/)
[![Based on Komga](https://img.shields.io/badge/Based%20on-Komga-blueviolet)](https://github.com/gotson/komga)

**Komga Enhanced** — A manga media server with integrated downloading, automatic chapter tracking, multi-source metadata, scrobbling, and hardened CBZ data-safety.

> Built on [Komga](https://github.com/gotson/komga) — extends the excellent upstream media server with manga downloading, automation, multi-tracker sync, and reliability features.

> [!IMPORTANT]
> **New Vue 3 UI available at `/next` — feedback wanted!**
>
> A completely rebuilt web interface (Vue 3) is now bundled **alongside** the classic UI. Open it at
> **`https://<your-komga-address>/next`**. The classic Vue 2 UI stays the default at `/` and both run in
> parallel — the classic UI (and the fork's Vue 2 code) can only be removed once upstream Komga finishes
> its own Vue 3 migration.
>
> This early phase is exactly when your input matters most: if nobody knows `/next` exists, there can be no
> feedback. Please try it and [open an issue](../../issues) for anything missing, broken, or worth improving —
> all fork features (Downloads, Discover, Followed, Blacklisted chapters, Media Integrity, Oversized Pages,
> Fixes, Logs, Plugins, Backups, Updates, Tachiyomi import, plus the series/book/library menus and edit
> dialogs) are ported.

---

## Contents

- [Quick Start](#quick-start)
- [Why This Fork?](#why-this-fork)
- [Screenshots](#screenshots)
- [Features](#features)
  - [Downloads & Automation](#downloads--automation)
  - [Metadata & Trackers](#metadata--trackers)
  - [Reading & Browsing](#reading--browsing)
  - [Reliability & Data Safety](#reliability--data-safety)
  - [Admin & Maintenance](#admin--maintenance)
- [Installation](#installation)
- [Configuration](#configuration)
- [Switching Between Komga Versions](#switching-between-official-komga-and-this-fork)
- [Comparison](#comparison-with-original-komga)
- [Documentation](#documentation)
- [Tech Stack](#tech-stack)
- [Contributing](#contributing)
- [Credits](#credits)

---

## Quick Start

| Image | Description |
|-------|-------------|
| `08shiro80/komga:latest` | Stable release |
| `08shiro80/komga-private:latest` | Testing branch — may contain unstable or experimental changes |

**Docker:**

```bash
docker run -d \
  --name komga \
  --network bridge \
  -p 25600:25600 \
  -v /path/to/config:/config \
  -v /path/to/manga:/manga \
  08shiro80/komga:latest
```

**Docker Compose:**

```yaml
services:
  komga:
    image: 08shiro80/komga:latest
    container_name: komga
    network_mode: bridge
    ports:
      - "25600:25600"
    volumes:
      - ./config:/config
      - /path/to/manga:/manga
    restart: unless-stopped
```

```bash
docker compose up -d
```

Open `http://localhost:25600`, create an admin account, and add a library. See [Installation](#installation) for JAR, build-from-source, and gallery-dl options.

---

## Why This Fork?

This fork transforms Komga from a pure media server into a **complete manga management solution**:

| Problem | Solution |
|---------|----------|
| Manually downloading manga | **Automatic downloads** via gallery-dl — supports MangaDex and many other manga/image sites |
| Losing track of downloaded chapters | **Chapter URL tracking** — DB + filesystem checks prevent duplicates |
| Re-downloading after crashes | **Crash recovery** — auto-resumes interrupted downloads on startup |
| Title changes cause re-downloads | **UUID folder names** — MangaDex UUID as folder name, immune to title changes |
| Folder renames break series | **Series survives folder rename** — detects same series via MangaDex UUID, preserves progress and metadata |
| Unwanted chapters keep re-downloading | **Chapter blacklist** — permanent + auto-detection of same-group duplicates and dead-link redirects |
| Syncing MangaDex subscriptions | **MangaDex Subscription Sync** — auto-downloads from your followed manga feed |
| Source upload uses wrong chapter number | **Add-Chapter-Download** — paste a chapter URL with custom filename + number override |
| Cloudflare blocks the source site | **FlareSolverr integration** — transparent challenge-bypass for non-MangaDex sources |
| Missing metadata | **MangaDex, AniList, Kitsu & Metron plugins** for rich metadata |
| Manual metadata-match per series | **Auto Metadata Match** — title-similarity scoring across providers on scan |
| Tracker progress out of sync | **Manga Scrobbler** — AniList / MyAnimeList / Kitsu / MangaDex on book completion |
| Western comics need their own tracker | **Comic Scrobbler** — Metron |
| Migrating from Tachiyomi/Mihon | **Backup import** extracts your MangaDex follows |
| No guest browsing for family | **Guest/Kiosk mode** — read-only browsing without login, per-library access control |
| Long vertical webtoon pages | **Page splitting** like TachiyomiSY |
| Corrupt CBZ after an interrupted write | **CbzSafeWriter** — every CBZ-mutating path writes through a single hardened pipeline with multi-stage verify + atomic rollback |
| Silent CBZ corruption goes unnoticed | **Media Integrity Verify / Repair / Rescan** — byte-wise scan, `zip -FF`-based repair, persistent ERROR-flag in the UI |
| Long-running maintenance hidden | **Background-job indicator** in the nav-bar — Verify / Repair / Split-All / Re-inject all visible from anywhere |
| No server logs in UI | **Web-based log viewer** — real-time SSE, persistent log-level, dual Debug/Info toggle |
| One-off DB/CBZ maintenance | **Settings → Fixes** page — GUI-triggered re-inject of ComicInfo.xml across a library |
| Bland default theme | **7 color themes** — AMOLED, Nord, Dracula, Solarized, Green, Red + Default |

---

## Screenshots

### Download Page

![Download Page](https://github.com/user-attachments/assets/80ed9a1f-f82c-4a5b-8b22-57b61740d765)

### Integrated Mangadex Search

![Integrated Mangadex Search](https://github.com/user-attachments/assets/e2355a2f-a3f0-4b11-970b-96601ec2b52d)

> **New Download** triggers a one-time download only — it does not add the URL to your follow list.

### Follow List Configuration

![Follow List Configuration](https://github.com/user-attachments/assets/43463b26-c436-4dc2-8619-351c37e43843)
![Follow List Configuration 2](https://github.com/user-attachments/assets/16de37ef-e2e9-465a-9298-c695ad58243b)
![Save after edit](https://github.com/user-attachments/assets/57cb7b83-9aa2-4469-a720-bcc5eba92ab2)

### Plugin Manager

![Plugins](https://github.com/user-attachments/assets/baebe276-5a05-4726-8cbe-7d7399e68fb4)

### Manual Backups

![Manual Backups](https://github.com/user-attachments/assets/b2f308be-806c-4f01-a6f5-5162b83a568f)

### Live Logs with Debug Toggle

![Live Logs](https://github.com/user-attachments/assets/24f65885-353f-45c6-85a8-a5afc7a2693e)

### Color Themes

![Color Themes](https://github.com/user-attachments/assets/f7e79d95-e66a-4a3c-98b7-0fff97a526c0)

### Fixes Page

![Fixes Page](https://github.com/user-attachments/assets/10f81194-57bd-42c1-a900-825ee988b1d9)

---

## Features

Grouped by area. Every section here is implemented and shipped; nothing aspirational.

### Downloads & Automation

#### Download System

Download manga from MangaDex and other manga/image sites via [gallery-dl-komga](https://github.com/08shiro80/gallery-dl-komga) (a fork of [gallery-dl](https://github.com/mikf/gallery-dl) with Komga-specific enhancements):

- **Queue-based downloads** with priority support
- **Real-time progress** via Server-Sent Events (SSE)
- **ComicInfo.xml injection** — metadata embedded in every CBZ
- **UUID folder names** — uses MangaDex UUID as folder name, immune to title changes and mislabeled titles
- **Crash recovery** — skips already-downloaded chapters via DB + filesystem checks, auto-resumes interrupted downloads on restart
- **Auto-retry** — failed downloads are retried with backoff; after 3 consecutive failures the chapter URL is auto-blacklisted so the queue keeps moving
- **Repair ComicInfo** — retroactively inject missing ComicInfo.xml and ZIP comments into existing MangaDex CBZ files (skips non-MangaDex sources to avoid destroying source-specific metadata)
- **Rate limiting** — respects site-specific API limits
- **Multi-language support** — 36 languages, shared across all plugins (one setting)
- **Automatic publisher detection** — derives publisher from source site (MangaDex, Mangahere, etc.)
- **Custom gallery-dl path** — point to a local gallery-dl-komga checkout for latest extractors

Any manga/image URL supported by gallery-dl works — not just MangaDex. Simply paste the URL in the WebUI to start a download.

#### MangaDex Subscription Feed Sync

Automatically sync new chapters from your MangaDex subscription feed — completely independent from the follow.txt system:

1. Create a [MangaDex API Client](https://mangadex.org/settings) (Personal Client)
2. Enable the `mangadex-subscription` plugin in **Settings → Plugins**
3. Enter your `client_id`, `client_secret`, `username`, and `password`
4. The syncer authenticates via OAuth2 and polls your follow feed

**How it works:**
- Authenticates with MangaDex via OAuth2 (password grant through Keycloak)
- Checks `GET /user/follows/manga` for newly followed manga → queues full download
- Checks `GET /user/follows/manga/feed?publishAtSince=...` for new chapters of existing manga, with a 30-min look-back window so chapters whose feed-indexing lagged the sync boundary still land
- Pre-checks each new manga for downloadable English chapters before queuing — dead-manga (only external-redirect or empty pages) are skipped without being permanently blacklisted (re-evaluated on every sync in case en-chapters appear later)
- Deduplicates against DB: checks mangaDexUuid → series → CHAPTER_URL IDs and blacklist before queuing
- Filters by the language configured in the gallery-dl Downloader plugin (`Default Language`)
- Resilient to temporary MangaDex API failures (retries on next scheduled check)

**Configuration** (via Plugin Manager UI):

| Setting | Default | Description |
|---------|---------|-------------|
| `client_id` | — | MangaDex API Client ID |
| `client_secret` | — | MangaDex API Client Secret |
| `username` | — | MangaDex username |
| `password` | — | MangaDex password |
| `sync_interval_minutes` | 30 | How often to check for new chapters |

**No app restart needed** — the syncer automatically restarts when you save config or toggle the plugin.

#### Follow List Automation

Automatically check for new chapters from your favorite manga:

1. Create a `follow.txt` file in your library root
2. Add URLs (one per line) — MangaDex URLs get fast aggregate checking, other sites use gallery-dl
3. Configure check interval (default: 24 hours)
4. Fast parallel checking via MangaDex aggregate API (~200 manga in 2 minutes)
5. New chapters download automatically

```
# Example follow.txt
https://mangadex.org/title/a1c7c817-4e59-43b7-9365-09c5f56e5eb1
https://mangadex.org/title/32d76d19-8a05-4db0-9fc2-e0b0648fe9d0
https://mangahere.cc/manga/one_piece/
https://manhuaplus.com/manga/magic-emperor/
https://hdoujin.me/12345
```

#### Add-Chapter-Download (per series)

For series whose source uploads arrive as one-off chapters that don't fit the rest of the series naming, the **`Add Chapter Download…`** entry in the 3-dot menu of a series opens a dialog with two modes:

- **Single Chapter** — paste a chapter URL, optionally override `Filename`, `Chapter #`, `Volume`, `Chapter Title`. With overrides on, the downloaded CBZ is renamed and `<Number>` / `<Volume>` / `<Title>` are upserted into ComicInfo so it sorts correctly in the series.
- **Series + Range** — paste a manga URL with a chapter-range filter (e.g. `chapter>=50,chapter<=80`), runs gallery-dl with the filter expression. Standard naming applies.

`Skip if file exists` compares **DB chapter numbers** (`BookMetadata.numberSort`), not filenames — filenames vary too much per source. The destination is taken from the series the menu was triggered on, so an Add-Chapter-Download into an existing UUID folder never creates a parallel manga folder.

#### Cloudflare Bypass (FlareSolverr)

Cloudflare-protected manga sites (mgeko.cc, mangaclash.com, deatte5.com, tritinia.org, manhwatop.com, …) cannot be fetched by gallery-dl's `requests` session — the server returns the Cloudflare challenge HTML.

The companion gallery-dl-komga fork supports an external [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) endpoint that solves the challenge in headless Chrome and returns the cookies + User-Agent that solved it. Per-host cookies + UA are cached on disk for 20 minutes — subsequent runs go direct without re-invoking FlareSolverr. The challenge round-trip only fires on a Cloudflare HTML response or a cold cache; JSON-API endpoints (`api.mangadex.org`) stay on the direct path.

Set `flaresolverr_url` (e.g. `http://192.168.1.10:8191/v1`) in **Plugin Manager → gallery-dl Downloader**. Leave blank to disable.

#### MangaDex Search on Downloads Page

A prominent search card sits at the top of **Downloads**. Pick a target library once from the dropdown, then for each result you get two buttons:

- **Download** — queues a one-time download into the selected library
- **Follow / Following** — a toggle. If the title isn't in any library's `follow.txt`, the button is "Follow" and appends the MangaDex URL to the selected library. If it's already in any library's `follow.txt`, the button is "Following" (success-coloured) and a second click removes the line from whichever library contains it.

**Advanced filters** (collapsible panel) — multi-select for:
- *Include tags / genres* (MangaDex `includedTags[]`)
- *Blacklist tags / genres* (MangaDex `excludedTags[]`) — useful for permanently hiding things you never want to see
- Status (ongoing / completed / hiatus / cancelled)
- Content rating (default = all four — empty selection keeps it that way)
- Publication demographic (shounen / shoujo / seinen / josei / none)
- *Only titles with downloadable chapters* (toggle) — MangaDex' built-in `hasAvailableChapters=true` is too generous (it counts external-link chapters and 0-page placeholders as "available", e.g. Solo Leveling still shows up even though every English chapter just links to webnovel.com). With this toggle on the UI does one extra `/manga/{id}/feed` call per result and only keeps titles where at least one chapter has `externalUrl == null` AND `pages > 0` in your preferred language (read from gallery-dl Downloader `default_language`). Each result is cached server-side for 24h.

  > **Cost:** 1 extra MangaDex API call per result when the toggle is on (cached). 20 results = ~20 extra calls = a few seconds of throttling on first run, then free until cache expires. Toggle off = zero extra cost.

With no title and at least one filter set, the button switches to **Browse** and queries MangaDex sorted by `followedCount desc` — popular-first browse when you can't decide what to read next.

**Pagination** — 24 results per page, classic page selector below the grid. MangaDex caps `offset + limit ≤ 10000`, so the page count tops out at 417 pages with the default page size.

**Tag catalog cached 7 days in your browser** — the tag dropdown is filled from `GET /api/v1/plugins/mangadex-metadata/tags` on first use, then served from `localStorage` for a week (MangaDex updates its tags only a few times a year). No tag fetch on every page load.

**Persistent defaults** — a **Save as default** button writes the current filter combination to your **Komga account** (user client-setting `komga.fork.mangadexsearch.defaults`), so defaults follow you across browsers and devices (a previously saved per-browser `localStorage` value is migrated automatically). On the next visit the panel pre-fills with those values, so you can set a permanent tag blacklist once and forget about it. **Clear all** resets the current selection without touching the saved default.

**Already-followed titles are marked** — on page load the search card pre-fetches every library's `follow.txt`, extracts the MangaDex UUIDs, and uses that set to flip every result's Follow button to "Following" state. No special filter; just an honest two-state toggle.

> **API rate-limit note:** every search and browse call goes through `MangaDexMetadataPlugin` to `api.mangadex.org` and counts against MangaDex's global rate limit (~5 req/sec). Heavy use of the panel — especially Browse — will throttle (HTTP 429) for a few seconds, same as the rest of the fork's MangaDex traffic.

#### Tachiyomi/Mihon Migration

Import your manga library from Tachiyomi or Mihon:

- Supports `.tachibk` (Mihon/forks) and `.proto.gz` (Tachiyomi) formats
- Extracts MangaDex URLs from your backup
- Adds URLs to your library's `follow.txt`
- Duplicate detection prevents re-adding existing URLs

#### Chapter Blacklist

Permanently prevent unwanted chapters from being re-downloaded:

- **Blacklist & Delete** via book 3-dot menu — blacklists the chapter URL and deletes the book file
- **Manage Blacklist** via series 3-dot menu — view, remove, and **manually add** blacklisted chapter URLs
- **Manual URL entry** — paste MangaDex chapter URLs directly into the blacklist dialog for edge cases the automatic system can't handle
- Persists even after book deletion (stored in separate database table)
- Respected by both the downloader and chapter checker

**Three automatic blacklist paths** keep the queue self-cleaning:

- **Same-group duplicates** — when a scanlation group uploads the same chapter multiple times on MangaDex (same chapter number, same group, different UUIDs), the system keeps the newest upload and blacklists the older duplicates
- **External-redirect chapters** — MangaDex chapters with `externalUrl != null && pages == 0` (J-Novel-style redirects to a publisher site) are blacklisted on detection — they can't be downloaded anyway
- **Three-strikes** — chapters that fail download three times in a row are auto-blacklisted so the queue keeps moving instead of hammering the same broken URL

All three log at WARN so the operator can review and remove individual entries via the series blacklist dialog if needed.

#### Chapter URL Tracking

Never download the same chapter twice:

- Database tracking of all downloaded chapter URLs
- Filesystem duplicate detection via existing CBZ files
- Tracks chapter metadata (volume, language, scanlation group)
- Multi-group support — same chapter from different scanlation groups downloaded separately

> **Important:** `Import chapter URLs` is **enabled by default** and required for the downloader, follow list, and subscription sync to detect already-downloaded chapters. **Disable it** in **Library → Edit → Metadata** for libraries that don't use the download system — otherwise library scans will be significantly slower.

### Metadata & Trackers

#### Enhanced Metadata

Rich metadata from multiple sources:

**MangaDex Metadata Plugin:**
- Fetches title, description, authors, artists
- Multi-language title support (10+ languages)
- Genre and tag mapping
- Cover art downloading

**AniList Metadata Plugin:**
- GraphQL-based metadata fetching
- Configurable title preference (English/Romaji/Native)
- Detailed series information

**Kitsu Metadata Plugin** *(ships as a default external plugin — see [Custom Plugins](#custom-plugins-external-jars)):*
- Fetches title, synopsis, authors, genres, age rating
- Alternative titles in multiple languages
- Cover art downloading
- No API key required

**Metron Metadata Plugin** *(disabled by default — comic metadata):*
- Fetches comic series metadata from [metron.cloud](https://metron.cloud) (free account required)
- Title, publisher, description, genres, cover art
- For Western comics where MangaDex/AniList don't apply

#### Auto Metadata Match

Komf-style automatic provider matching for new series on scan:

- Walks a configured provider priority (`anilist,mangadex,kitsu` by default)
- Scores candidates by normalized-title Jaccard similarity (default threshold `0.85`)
- Writes a `web_url` plus multi-source `tracker_links` into `series.json`, so `MylarSeriesProvider` produces one WebLink per matched site
- Bulk-match existing series: `POST /api/v1/automatch/libraries/{id}`
- Configured via `Plugin Manager → Auto Metadata Match` with a **GUI** (drag-free ordered provider list + library multi-select) instead of raw CSV; **does not** appear in the per-series *Search Online Databases* dialog (it runs as a background processor, not a manual search provider)
- Works with **external metadata plugins** too — any installed `MetadataProviderPlugin` becomes selectable in the provider priority

#### Tracker Sync (Scrobblers)

Push read progress to external trackers when a book is marked completed. Scrobbling is **per user**: every user links their own tracker accounts under **My account → Trackers** (classic UI `/account/trackers`, new UI `/next/account/trackers`), and a completed book is only synced to the accounts of the user who read it. There is no server-wide account and no fallback. The administrator enables the scrobbler plugins and sets the server policy (`discover_ids`, `mappings`, `library_ids`, `exclude_library_ids`) in the Plugin Manager. The Trackers page shows for every tracker whether the administrator has enabled the matching scrobbler.

**Linking an account** (My account → Trackers):
- **AniList** — on [anilist.co/settings/developer](https://anilist.co/settings/developer) choose *Create New Application* and enter the **Redirect URL shown on the Trackers page** (it is built from the address you use to open Komga, including a context path such as `/komga`). Save the client ID and client secret in Komga, then click **Connect** and approve the login on AniList. The AniList token is valid for one year; the Trackers page shows the expiry date and warns two weeks before. When AniList rejects the token, the account is marked "connect again".
- **MyAnimeList** — on [myanimelist.net/apiconfig](https://myanimelist.net/apiconfig) create an API client and enter the Redirect URL shown on the Trackers page as *App Redirect URL*. Save the client ID (and the client secret if your app type has one) in Komga, then click **Connect**. Komga uses OAuth2 with PKCE and renews the token automatically; if the renewal is rejected, the account is marked "connect again".
- **Kitsu** — enter your Kitsu login (email or username and password). Komga signs in to Kitsu itself and renews the session automatically. A rejected login is shown on the Trackers page instead of failing every chapter. Previously stored Kitsu tokens are removed by the update; link Kitsu again with your login.
- **MangaDex** — your MangaDex account plus a personal API client (mangadex.org/settings → API Clients) and the maximum content rating to sync.
- **Metron** — an API token from your metron.cloud profile (*Profile → API Tokens*). Username and password are only a fallback and are used when no token is set.

Every exit of the provider login (success, cancelled, expired login request, expired Komga session, rejected or rate-limited token request, a tracker without provider login) returns to the Trackers page with a message. The Redirect URL must match exactly what is registered at AniList / MyAnimeList. If Komga runs behind a reverse proxy, the proxy must forward the original host and scheme (`X-Forwarded-Host` / `X-Forwarded-Proto`, and `X-Forwarded-Prefix` if it adds a path), otherwise the shown URL points to the internal address.

For every tracker the page shows the remote account name, the last success and the last error, and offers **Test connection** (the result is stored as the account status), **Sync now** (sends every read series that is ahead of the tracker) and **Unlink** (removes the credentials and the sync state of that tracker). Pausing an account keeps the credentials and stops syncing; after resuming, series read during the last seven days are synced automatically, older ones with **Sync now**. Switching **Active** only changes that flag; unsaved input in the other fields is kept. After new credentials are saved (AniList, MyAnimeList, Kitsu, MangaDex), Komga identifies the remote account before the next send or pull; if the connection test, the provider login or this check finds a different remote account than before, the sync state of that tracker is reset. For Metron the sync state is reset when the username changes.

**Manga Scrobbler** — AniList / MyAnimeList / Kitsu / MangaDex:
- Sends the **highest read chapter** of the series (decimal numbers rounded down; numbers ≤ 0 are skipped with a log line). Several books marked read at once result in one update per series and tracker.
- Reads the remote entry before every write and **never lowers** the remote progress. A new entry is created as *current*; when the read chapter reaches the total chapter count the entry is set to *completed* (never back from *completed* to *current*). For AniList and Kitsu Komga also sends the start date (first read) and, when the entry is set to *completed*, the completion date (last read), each only if the remote entry has none yet; for Kitsu this has not been verified against the live API. MyAnimeList only gets progress and status (no start or finish dates, its PATCH API does not document them).
- Manga libraries are chapter based: the book number is always sent as read chapters. Komga one-shot series (books in the library's one-shots folder) and series with the genre or tag "Oneshot" (also "One-shot" or "One shot", as MangaDex provides it) count as chapter 1 and complete the entry. Any other series with a single book is sent by its chapter number like every series.
- MangaDex: marks the downloaded MangaDex chapters up to the read chapter as read (the chapters and languages from the download history, otherwise the MangaDex feed in the downloader's default language), only chapters that are not read yet, and only if the manga's content rating is within the account's maximum.
- Every hour the progress of AniList, MyAnimeList and Kitsu is pulled back for every active account: books up to a higher remote progress are marked read for that user (and forwarded to the user's other trackers), a lower remote progress is sent again (at most once per day for the same local progress). MangaDex is not pulled.
- An update that fails because the tracker is down or rate limited is planned again, regardless of when the series was read: after the time the tracker announced (`Retry-After`), otherwise after 10 minutes, doubling up to 6 hours. Planned retries are kept in memory only and are lost on a restart; every 30 minutes the series read during the last seven days are compared with what each tracker last received, and **Sync now** sends older ones.
- Requests per tracker are rate limited and share one budget for scrobbling, pulling, ID discovery, link enrichment, token requests and connection tests. Background work waits for a free slot; **Test connection** and the provider login wait at most 10 seconds and then report that the tracker is rate limited (try again later). A `429` answer pauses that tracker for the announced time. ID discovery and sending run on a separate thread per tracker, so a slow tracker does not delay the others; the hourly pull runs on its own thread and a run is skipped while the previous one is still running.
- A series skipped for MangaDex (content rating above the maximum, no known MangaDex chapters up to the read chapter) is not checked again by the 30-minute comparison for 24 hours unless the read chapter changes. Repeated failure messages are logged once per user, series, tracker and kind of error per day (details on DEBUG).

**Finding the tracker entry of a series** (per tracker, in this order):
1. `mappings` (keys are Komga series IDs or series titles; `anilist_id`, `mal_id`, `kitsu_id`, `mangadex_id`, `metron_series_id`)
2. IDs Komga already stored for the series (kept even if a metadata refresh replaces the links or the links are locked)
3. the series links (anilist.co, myanimelist.net, kitsu.app or kitsu.io with ID or slug, mangadex.org, metron.cloud/series)
4. if `discover_ids` is on (default): the MangaDex entry of the series (its AniList / MyAnimeList / Kitsu links; the MangaDex ID comes only from the link, the series folder UUID or the downloaded chapters, never from a MangaDex title search) and then a **verified title search** over all local titles (title and alternative titles): a result is only used when one of its titles is exactly equal to a local title after normalisation, it is a manga (no novels), and only one entry matches. The MyAnimeList search uses `auto-metadata.mal_client_id`, otherwise the reading user's own MAL client ID. The MangaDex title search only runs for users with a linked MangaDex account.

Found IDs are stored and added to the series links unless the links are locked. Removing or replacing such a link in the series editor rejects the removed value and the ID that was in use for that tracker (wherever it came from), so the automatic discovery (MangaDex entry, title search) does not pick them again. A link on the series always counts: if it points to a rejected ID (for example a Kitsu slug that resolves to the ID of the replaced link), that ID is accepted again. If no ID is found, one log line per series and tracker and day explains why. A failed lookup (network, rate limit) is retried later, a missing match is retried after 24 hours.

**Comic Scrobbler** — Metron:
- Each user links their own Metron account; every issue is sent once per user, with the date the book was read.
- The Metron issue comes from a `metron.cloud/issue/…` book link, otherwise from the issue number within the Metron series of the series (mapping `metron_series_id`, `metron.cloud/series/…` link or a verified series search). The old mapping key `metron_issue_id` is no longer used.
- Metron's rate limit is respected: 20 requests per minute and a daily limit per account (5000 requests, or the limit Metron reports in its `X-RateLimit-Sustained-*` headers). When the daily limit is reached, the issue is sent again after the reported reset (otherwise after midnight UTC); a `429` answer pauses all Metron requests for the announced time. The daily counter is kept in memory: after a restart it starts again at the full limit until the next Metron answer reports the remaining requests. Failed issues are planned again like manga updates.
- An issue number that the Metron series does not have is not looked up again for 24 hours.

**Encrypted credentials:** all tracker secrets (tokens, passwords, client secrets) are stored encrypted (AES-256-GCM) in the database. The key is created on first use in the config directory as `tracker-secrets.key` (Docker: `/config/tracker-secrets.key`, file mode `0600`) and is never stored in the database. Values stored by older versions are encrypted once at startup. **Back up the key file together with `database.sqlite`** — a database backup without the matching key cannot decrypt the tracker credentials. If the key file is missing at startup while encrypted credentials exist, Komga logs a warning, the affected accounts show "credentials cannot be read" and must be linked again; saving new credentials is refused while the key cannot be created. The encryption protects the database file and its backups; it does not protect against someone with full access to the server's config directory.

Both scrobblers persist the last sent progress per (user, series, tracker) in `sync_state` (Metron per issue), so nothing is sent twice after a restart. API: `GET /api/v1/trackers`, `PUT|DELETE /api/v1/trackers/{tracker}`, `POST /api/v1/trackers/{tracker}/test`, `POST /api/v1/trackers/{tracker}/sync`, `POST /api/v1/trackers/{tracker}/authorize` and the provider callback `GET /api/v1/trackers/{tracker}/callback` (secrets are never returned).

#### Shared MangaDex Credentials

`gallery-dl Downloader` is the single source of truth for the server's MangaDex authentication. Set `mangadex_username` / `mangadex_password` / `mangadex_client_id` / `mangadex_client_secret` once on that plugin and **MangaDex Subscription Sync** reads from it automatically (its own equivalent fields stay empty and only act as an override). The Subscription Sync's `default_language` is read the same way (in `MangaDexSubscriptionSyncer.checkFeed`).

The **Manga Scrobbler** does not use these shared credentials: it only marks chapters as read on the MangaDex account each user links under **My account → Trackers**.

#### Custom Plugins (External JARs)

Beyond the built-in plugins, the fork can **load external plugin JARs at runtime** — drop them into `<config-dir>/plugins/` or upload via **Plugin Manager → Install Plugin** (file or URL). Built-in plugins stay compiled in and **cannot be uninstalled**; only external plugins are removable.

> **Docker:** the plugins folder lives at `/config/plugins`, which is **already inside the mounted `/config` volume** — no extra volume mount is needed. Drop JARs there (or upload via the UI) and they persist across container restarts. The bundled default plugins (e.g. Kitsu) are auto-installed there on first start.

- **Two plugin types today:** `MetadataProviderPlugin` (search + metadata, shows up in *Search Online Databases* and Auto Metadata Match) and `NotifierPlugin` (receives `DOWNLOAD_COMPLETED` / `DOWNLOAD_FAILED` events — webhook, Discord, …)
- **Class-loader isolation (SPI-only)** — each JAR runs under a `SpiOnlyClassLoader` whose parent whitelists only the SPI (`org.gotson.komga.infrastructure.plugin.api.*`), JDK (`java.*`/`javax.*`), Kotlin runtime (`kotlin.*`/`kotlinx.*`) and Jackson (`com.fasterxml.jackson.*`). A plugin attempting `import org.gotson.komga.domain.…` or Spring gets `ClassNotFoundException("Plugin denied access to '…' — class-loader isolation. …")`. Bundle anything else you need (OkHttp, JDBC driver, …) into your plugin JAR. Load failures never crash the server.
- **Default external plugin:** the **Kitsu** provider is shipped this way (bundled in the app, auto-installed on first start) as the reference implementation
- **Write your own:** copy [`plugins/plugin-template`](plugins/plugin-template), edit one Kotlin file, run `./gradlew build`, install the JAR. Full guide: **[PLUGINS.md](PLUGINS.md)**

> **Security model — class-loader isolation, NOT a sandbox.** The whitelist above blocks compile-time/runtime imports of Komga internals so a plugin can't reach into the host through the SPI's back door. It does **not** restrict filesystem (`java.io.File`), network (`java.net.http`) or reflection (`java.lang.reflect`) — those are intentionally allowed because plugins call external APIs. An installed external JAR runs arbitrary code with the same OS rights as the Komga process. The install endpoint is admin-only — only install plugins you trust. See [PLUGINS.md → Security model](PLUGINS.md#security-model) for the full statement of limits.

### Reading & Browsing

#### Tall Page Splitting

Split long vertical webtoon pages into readable segments:

- Configurable maximum height threshold
- Batch processing for entire libraries with **active UI filter respected** — Split-All only walks the books matching the visible filter, not the whole library
- Endpoint-level lock prevents double-click fan-out
- Per-book serialisation + global parallelism cap (max 2 simultaneous splits) — webtoon pages can be 180 MB BufferedImages each, the cap keeps the JVM from OOMing
- Preserves original files via `CbzSafeWriter` (see [Reliability](#reliability--data-safety))
- Similar to TachiyomiSY's "split tall images" feature

#### Guest/Kiosk Mode

Read-only browsing without login — perfect for family or shared setups:

- Toggle in admin **Settings → UI**
- "Als Gast durchsuchen" button on login page
- Per-library guest access — admins select which libraries are visible
- Security: GET-only access to series/books/libraries, admin routes blocked

#### Color Themes

7 predefined theme presets in **Account → UI Settings**:

- Default, AMOLED, Nord, Dracula, Solarized, Green, Red
- Each preset defines both light and dark mode colors
- Persistent selection via browser local storage

#### Configurable Folder Naming

Choose how new manga folders are named:

- `uuid` (default) — uses MangaDex UUID like `0c6fe779-...`
- `title` — uses manga title like `Roman Club`
- Set in **Plugin Manager → gallery-dl Downloader** settings
- Only affects new manga — existing folders are never renamed

#### Configurable Chapter Naming

Override the gallery-dl `directory` template per-install via the **Plugin Manager → gallery-dl Downloader → Chapter Naming Template** field. Blank keeps each site's default. Common fields: `{chapter}`, `{chapter_minor}`, `{volume}`, `{title}`, `{group}`, `{lang}`.

`ChapterMatcher` accepts three prefix shapes in the resulting filename for post-download CBZ matching and resume detection: **`c<num>`** (gallery-dl default — e.g. `c001`), **`ch. <num>`** (e.g. `ch. 1`), or **`Chapter <num>`** / **`Chapter_<num>`** (e.g. `Chapter 1`, `Chapter_001`). Letter suffixes (`5.5a`, `5.5b` — MangaDex convention for split releases of the same decimal chapter) are preserved across both extraction and matching. Pick any template that produces one of these three prefixes; the rest of the filename is yours.

### Reliability & Data Safety

The fork puts the same level of care into not-corrupting-your-library as it does into the user-visible features. Many of these are silent in normal operation — you only notice them when something would have gone wrong.

#### CbzSafeWriter — every CBZ-mutating path

Six paths can mutate a CBZ on disk (`PageSplitter`, `BookPageEditor.removeHashedPages` / `deletePages`, `ComicInfoGenerator.writeInjected` / `injectComicInfoWithRetry`, `DownloadExecutor.patchComicInfo`). All six route through one utility that guarantees:

- **Pre-flight disk-space check** — refuses if free space < 2× expected output size
- **Hybrid RAM/disk build** — < 100 MB original = in-RAM build, ≥ 100 MB = direct disk-tmp (avoids OOM on webtoon CBZs while keeping the fast path RAM-only for typical chapters)
- **Three independent verify passes** — in-RAM ZipInputStream, post-write ZipFile open + byte-read, post-rename ZipFile open. An SMB-truncated write or partial rename is caught before the original is touched.
- **Backup-via-move (not copy)** — original.cbz → `.bak.<uuid>` only after the new bytes are verified on disk; the backup move + atomic rename are the only window where the file isn't readable. At 150-chapter Split-All on a 50-200 MB-per-chapter library this saves ~15-30 GB of disk traffic per run versus a copy-based approach.
- **Atomic rollback on any failure** — `.bak` is moved back over `target`; rollback failure leaves `.bak` on disk and a fatal-level log line gives its path so the user can restore manually.

The lambda signature is `(OutputStream) -> Unit` so callers can wrap the stream in either `java.util.zip.ZipOutputStream` or Apache commons-compress `ZipArchiveOutputStream` — same writer, both ZIP APIs.

#### Media Integrity — Verify / Repair / Rescan

`Settings → Maintenance → Verify ZIP integrity` runs a byte-wise check over every `.cbz` / `.zip` in the library:

- **Two-pass read with a 2s sleep between passes** — only flags after **both** passes fail. Transient I/O errors (HDD bad-sector retry, file-handle race against a concurrent write) don't produce false positives.
- **Persistent ERROR-flag** — corrupt books get `Media.Status.ERROR` with a `Corrupt CBZ: <message>` comment, surface in the analysis filter, and survive container restart (count comes from `mediaRepository.countByStatus(ERROR)` when no scan is running)
- **`Repair flagged`** runs `zip -FF <src> --out <tmp>` per ERROR-book. Fully recovered → `Files.move` overwrites + status flips to `OUTDATED` for re-analyze. Partial → comment updated, status stays ERROR.
- **`Rescan flagged`** byte-wise re-verifies each ERROR-book and only emits `taskEmitter.analyzeBook` for those that pass. Use after a Repair-Partial or after fixing files externally.

#### Background-Job Indicator

The task-count indicator in the nav-bar now reflects custom long-running actions too — `Split-All`, `Verify Integrity`, `Repair`, `Re-inject ComicInfo`, library imports. The tooltip lists active job names. You can navigate away from the originating page without losing visibility.

#### SQLite Hardening

Default SQLite was tuned for embedded single-user use. The fork picks values that match a multi-tab Komga UI against a library DB measured in hundreds of MB:

- **`busy-timeout: 30s`** (both DBs) — the JDBC driver waits transparently instead of bubbling `SQLITE_BUSY` to the task layer
- **`synchronous: NORMAL`** (both DBs) — fsync only at WAL-checkpoint boundaries, 3-5× faster on bulk writes; no corruption risk under WAL, ~1 s commits may be lost on power-cut
- **`cache_size: 2000`** (main DB only — `tasks-db` is small) — 8 MB per connection (default is 2 MB) holds the working set of the dominant indexes
- **Dynamic RO pool** — mirrors `/settings/server → Task threads` (upstream historically capped to 1 from pre-WAL days). Resizes live without restart.
- **`SQLITE_BUSY` retry-loop in `TaskHandler`** — 5 attempts with linear backoff (500 ms → 2.5 s) catches the edge case where a write enters busy state right at the 30 s ceiling

#### Database Hygiene

Two scheduled jobs run daily and prune log/event tables to keep the SQLite file lean:

- `PLUGIN_LOG` — entries older than 7 days are deleted (was unbounded; observed 119k+ rows on installs without retention)
- `HISTORICAL_EVENT` + `HISTORICAL_EVENT_PROPERTIES` — pruned together (the schema has no `ON DELETE CASCADE`, so the DAO deletes properties first then events)

> **Note on file size:** SQLite does **not** shrink `database.sqlite` when rows are deleted — freed pages are reused for future writes but the file stays the same size on disk (it just stops growing). To actually reclaim space, run `VACUUM;` against the database (e.g. `sqlite3 database.sqlite "VACUUM;"`) — this works while Komga is running but holds a brief exclusive lock. Retention prevents unbounded growth; VACUUM is the one-time reclaim.

#### Auto-Scan After Download

New chapters are automatically scanned after a download completes (`scanDeep=false` — filesystem walk only, cheap):

- Triggered only when `newlyDownloaded > 0` (resume runs that found nothing already on disk are skipped)
- New books are added, analyzed, and chapter URLs imported automatically
- No full library scan needed; no waiting for the next scheduled scan

### Admin & Maintenance

#### Settings → Fixes

GUI-triggered one-time maintenance actions under **Settings → Fixes**. Cards are versioned and removed once obsolete. The card list is built dynamically from `FixRegistry` — adding a new fix is one block of Kotlin + one backend endpoint, no frontend change.

- **Re-inject ComicInfo.xml** — regenerates `ComicInfo.xml` + `series.json` for every CBZ in the selected library from MangaDex metadata. Enable *Force* to overwrite existing ComicInfo (useful when MangaDex metadata changed). **CBZs from non-MangaDex sources are skipped even under Force** — the re-inject reads the existing `<Web>` tag and refuses to overwrite mangabuddy / cubari / Mihon-export ComicInfo with MangaDex metadata. Image entries are written with `STORED` to skip re-deflating already-compressed data (~3-5× faster sweep).

#### Web Log Viewer

Admin-only log viewer in **Settings → Logs**:

- **Live streaming** via SSE — real-time log tailing without polling
- **Pause/Resume** — buffer incoming logs while paused, flush on resume
- **Persistent log level** — switch between DEBUG / INFO / WARN at runtime via two mutually-exclusive toggles (Debug, Info; both off = WARN). The choice persists in the DB and is re-applied on restart.
- Color-coded log levels (ERROR=red, WARN=orange, DEBUG=grey)
- Client-side search/filter
- Full log file download

The fork pins `org.gotson.komga: WARN` as the baseline log level — state-altering events (auto-blacklist, stale-recovery, repair-skip, etc.) are emitted at WARN so they survive the default install. Routine flow stays at INFO/DEBUG and only surfaces when you flip the toggle.

---

## Installation

### Requirements

- Java 21+
- [gallery-dl-komga](https://github.com/08shiro80/gallery-dl-komga) (`pip install https://github.com/08shiro80/gallery-dl-komga/archive/refs/heads/master.tar.gz`)
- (Optional) [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) endpoint for Cloudflare-protected sources

### Docker

See [Quick Start](#quick-start) for Docker and Docker Compose commands. The Docker image already contains gallery-dl-komga, kepubify, and `zip` (for the Repair path); no extra setup needed.

### Updating gallery-dl-komga in Docker

gallery-dl-komga is installed via pip inside the Docker image. The fork keeps the upstream version string (`1.32.1`), so a plain `-U` will **not** pull in new commits — pip sees an unchanged version and reuses its cached wheel, and GitHub caches the branch tarball for a few minutes. Force a clean reinstall:

```bash
docker exec -u 0 komga pip3 install --break-system-packages --no-cache-dir --force-reinstall \
  https://github.com/08shiro80/gallery-dl-komga/archive/refs/heads/master.tar.gz
```

To pin an exact commit — immutable, and it bypasses the branch-tarball cache entirely — use its SHA instead of `refs/heads/master`:

```bash
docker exec -u 0 komga pip3 install --break-system-packages --no-cache-dir --force-reinstall \
  https://github.com/08shiro80/gallery-dl-komga/archive/<commit-sha>.tar.gz
```

gallery-dl runs as a subprocess per download, so no Komga restart is needed — the next download uses the updated version.

### JAR

```bash
java -jar komga.jar
```

> Install **[gallery-dl-komga](https://github.com/08shiro80/gallery-dl-komga)** (the fork, see [Requirements](#requirements)) — the download/ComicInfo features rely on the fork's `komga` postprocessor, not upstream PyPI `gallery-dl`. Point the gallery-dl Downloader plugin's `gallery_dl_path` at a local checkout if needed.

### Important: Metadata Completeness

Full metadata in ComicInfo.xml and series.json (title, authors, genres, cover art, publish dates, scanlation group, etc.) is only guaranteed when downloading from **MangaDex** or when using the **MangaDex/AniList metadata plugins**. Other sites supported by gallery-dl will download chapters correctly, but metadata may be incomplete or missing.

### Build from Source

```bash
# Build frontend
cd komga-webui && npm install && npm run build && cd ..

# Build backend with frontend
./gradlew prepareThymeLeaf :komga:bootJar

# Run
java -jar komga/build/libs/komga-*.jar
```

---

## Configuration

### gallery-dl Setup

Create `~/.config/gallery-dl/config.json`:

```json
{
  "extractor": {
    "mangadex": {
      "lang": ["en"],
      "chapter-filter": "lang == 'en'"
    }
  }
}
```

To use a local gallery-dl-komga checkout (e.g. for latest extractors), set `gallery_dl_path` in the plugin config to the directory containing the `gallery_dl` package. This sets `PYTHONPATH` so `python -m gallery_dl` loads from your local source.

### FlareSolverr

If you have a FlareSolverr instance for Cloudflare-protected sites, set its URL in **Plugin Manager → gallery-dl Downloader → `flaresolverr_url`** (e.g. `http://192.168.1.10:8191/v1`). Blank disables the feature.

### Follow List Check Interval

The check schedule is configured **per library** (not via a global application property) through the
follow-schedule API or the **Downloads → Followed** page in the UI:

```bash
curl -X PUT http://localhost:25600/api/v1/downloads/follows/{libraryId}/schedule \
  -H 'Content-Type: application/json' \
  -d '{"enabled": true, "scheduleMode": "interval", "intervalHours": 24}'
```

---

## Switching Between Official Komga and This Fork

The fork stores its database migrations in a separate history table (`flyway_fork_history`), completely independent from the official Komga migration history (`flyway_schema_history`):

- **Official Komga → Fork:** Works. Fork migrations run automatically on first startup.
- **Fork → Official Komga:** Works. Official Komga only sees its own migration history and starts normally. The fork's extra tables and columns remain in the database but are ignored.

---

## Comparison with Original Komga

| Feature | Original | This Fork |
|---------|----------|-----------|
| Media Server | Yes | Yes |
| Manga Downloads | No | Yes |
| Automatic Chapter Tracking | No | Yes |
| MangaDex Subscription Sync | No | Yes |
| Follow List Automation | No | Yes |
| Add-Chapter-Download per series | No | Yes |
| Cloudflare Bypass (FlareSolverr) | No | Yes |
| Chapter Blacklist + Auto-blacklist | No | Yes |
| Series survives folder rename | No | Yes |
| Auto-scan after download | No | Yes |
| Configurable folder naming | No | Yes (UUID/title) |
| Configurable chapter naming | No | Yes (3 prefixes) |
| Guest/Kiosk Mode | No | Yes |
| Web Log Viewer (persistent level) | No | Yes |
| Color Themes | No | 7 presets |
| Tachiyomi Import | No | Yes |
| Page Splitting | No | Yes |
| AniList, Kitsu & Metron Metadata | No | Yes |
| Auto Metadata Match (Komf-style) | No | Yes |
| Tracker Sync (AniList / MAL / Kitsu / MangaDex / Metron) | No | Yes |
| Settings → Fixes (one-off maintenance) | No | Yes |
| Media Integrity Verify / Repair / Rescan | No | Yes |
| Hardened CBZ writer (atomic + verified) | No | Yes |
| Background-job indicator in nav-bar | No | Yes |
| Real-time Progress | No | Yes (SSE) |

---

## Documentation

- [Download System Guide](docs/downloads.md)
- [Follow List Setup](docs/follow-lists.md)
- [Tachiyomi Migration](docs/tachiyomi-import.md)
- [Page Splitting](docs/page-splitting.md)
- [Metadata Plugins](docs/metadata-plugins.md)
- [Plugin Development](PLUGINS.md)
- [API Reference](docs/api-reference.md)
- [Fork Changelog](FORK_CHANGELOG.md) — per-version stability notes, ordered newest-first

---

## Tech Stack

- **Backend:** Kotlin, Spring Boot, jOOQ
- **Frontend:** Vue.js 2, Vuetify, TypeScript
- **Database:** SQLite (WAL journal mode, RO/RW pool split)
- **Downloads:** gallery-dl-komga (Komga-enhanced gallery-dl fork), optional FlareSolverr for Cloudflare bypass
- **Metadata:** MangaDex API, AniList GraphQL, Kitsu REST, Metron REST, auto-detected from source site
- **Scrobbling:** AniList GraphQL, MyAnimeList REST, Kitsu JSON:API, MangaDex auth, Metron REST

---

## Contributing

1. Fork the repository
2. Create a feature branch
3. Follow [Conventional Commits](https://www.conventionalcommits.org/)
4. Submit a pull request

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

---

## Credits

- [Komga](https://github.com/gotson/komga) by gotson - The excellent base media server
- [gallery-dl](https://github.com/mikf/gallery-dl) by mikf - Download engine (base)
- [gallery-dl-komga](https://github.com/08shiro80/gallery-dl-komga) - Komga-enhanced gallery-dl fork with extended manga metadata and the `komga` postprocessor for in-process ComicInfo.xml injection
- [jackohagan94-afk](https://github.com/jackohagan94-afk) — original author of the Auto-Metadata-Match, Manga Scrobbler, Comic Scrobbler and Metron Metadata Provider plugins (cherry-picked from their v2.0 branch after their upstream PR was withdrawn)
- [beaux](https://github.com/beaux) — inspiration for the MangaDex search card on the Downloads page (re-implemented here against the existing `MangaDexMetadataPlugin` to keep the rate-limiter and content-rating config in play)
- [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) - Cloudflare challenge solver used by the gallery-dl-fork integration
- [MangaDex](https://mangadex.org) - Primary manga source and API
- [AniList](https://anilist.co) - Metadata and tracker source
- [Kitsu](https://kitsu.app) - Metadata and tracker source
- [MyAnimeList](https://myanimelist.net) - Tracker source
- [Metron](https://metron.cloud) - Comic metadata and tracker source

---

## License

[MIT License](LICENSE)
