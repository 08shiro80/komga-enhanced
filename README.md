# Komga Enhanced

[![Docker Pulls](https://img.shields.io/docker/pulls/08shiro80/komga)](https://hub.docker.com/r/08shiro80/komga)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21+-blue)](https://adoptium.net/)
[![Based on Komga](https://img.shields.io/badge/Based%20on-Komga-blueviolet)](https://github.com/gotson/komga)

**Komga Enhanced** - A powerful manga media server with integrated manga downloading, automatic chapter tracking, and Tachiyomi/Mihon backup import.

> **Built on [Komga](https://github.com/gotson/komga)** - Extends the excellent Komga media server with manga downloading and automation features.

---

## Contents

- [Quick Start](#quick-start)
- [Why This Fork?](#why-this-fork)
- [Screenshots](#screenshots)
- [Key Features](#key-features)
- [Installation](#installation)
- [Configuration](#configuration)
- [API](#api)
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
version: "3.9"
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
| Manually downloading manga | **Automatic downloads** via gallery-dl — supports MangaDex and other manga/image sites |
| Losing track of downloaded chapters | **Chapter URL tracking** prevents duplicates |
| Re-downloading after crashes | **DB + filesystem tracking** - never re-download completed chapters |
| Title changes cause re-downloads | **UUID folder names** - MangaDex UUID as folder name, immune to title changes |
| Folder renames break series | **Series survives folder rename** - detects same series via MangaDex UUID, preserves progress and metadata |
| Unwanted chapters keep re-downloading | **Chapter blacklist** - permanently block chapters from being downloaded |
| Syncing MangaDex subscriptions | **MangaDex Subscription Sync** auto-downloads from your followed manga feed |
| No guest browsing for family | **Guest/Kiosk mode** - read-only browsing without login, per-library access control |
| Migrating from Tachiyomi/Mihon | **Backup import** extracts your MangaDex follows |
| Long vertical webtoon pages | **Page splitting** like TachiyomiSY |
| Missing metadata | **MangaDex, AniList & Kitsu plugins** for rich metadata |
| No server logs in UI | **Web-based log viewer** - real-time auto-refresh, search, color-coded levels |
| Bland default theme | **7 color themes** - AMOLED, Nord, Dracula, Solarized, Green, Red + Default |

---

## Screenshots

### Download Page

![Download Page](https://github.com/user-attachments/assets/4cf6904a-a182-468c-a1ff-8b4118a378aa)

> **New Download** triggers a one-time download only — it does not add the URL to your follow list.

### Plugin Configuration

![Plugin Configuration](https://github.com/user-attachments/assets/43463b26-c436-4dc2-8619-351c37e43843)
![Plugin Configuration 2](https://github.com/user-attachments/assets/16de37ef-e2e9-465a-9298-c695ad58243b)
![Save after edit](https://github.com/user-attachments/assets/57cb7b83-9aa2-4469-a720-bcc5eba92ab2)

### Plugin Manager

![Plugins](https://github.com/user-attachments/assets/b1a40557-dfc4-4e6e-88e3-d587fc7dafbe)

### Manual Backups

![Manual Backups](https://github.com/user-attachments/assets/b2f308be-806c-4f01-a6f5-5162b83a568f)

### Live Logs with Debug Toggle

![Live Logs](https://github.com/user-attachments/assets/24f65885-353f-45c6-85a8-a5afc7a2693e)

### Color Themes

![Color Themes](https://github.com/user-attachments/assets/f7e79d95-e66a-4a3c-98b7-0fff97a526c0)

---

## Key Features

### Download System

Download manga from MangaDex and other manga/image sites via [gallery-dl-komga](https://github.com/08shiro80/gallery-dl-komga) (a fork of [gallery-dl](https://github.com/mikf/gallery-dl) with Komga-specific enhancements):

- **Queue-based downloads** with priority support
- **Real-time progress** via Server-Sent Events (SSE)
- **ComicInfo.xml injection** - metadata embedded in every CBZ
- **UUID folder names** - uses MangaDex UUID as folder name, immune to title changes and mislabeled titles
- **Crash recovery** - skips already-downloaded chapters via DB + filesystem checks, auto-resumes interrupted downloads on restart
- **Repair ComicInfo** - retroactively inject missing ComicInfo.xml and ZIP comments into existing MangaDex CBZ files
- **Rate limiting** - respects site-specific API limits
- **Multi-language support** - 36 languages, shared across all plugins (one setting)
- **Automatic publisher detection** - derives publisher from source site (MangaDex, Mangahere, etc.)
- **Custom gallery-dl path** - point to a local gallery-dl-komga checkout for latest extractors

Any manga/image URL supported by gallery-dl works — not just MangaDex. Simply paste the URL in the WebUI to start a download.

### Follow List Automation

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
https://hdoujin.me/12345
```

### MangaDex Subscription Feed Sync

Automatically sync new chapters from your MangaDex subscription feed — completely independent from the follow.txt system:

1. Create a [MangaDex API Client](https://mangadex.org/settings) (Personal Client)
2. Enable the `mangadex-subscription` plugin in **Settings → Plugins**
3. Enter your `client_id`, `client_secret`, `username`, and `password`
4. The syncer authenticates via OAuth2 and polls your follow feed

**How it works:**
- Authenticates with MangaDex via OAuth2 (password grant through Keycloak)
- Checks `GET /user/follows/manga` for newly followed manga → queues full download
- Checks `GET /user/follows/manga/feed?publishAtSince=...` for new chapters of existing manga
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

### Tachiyomi/Mihon Migration

Import your manga library from Tachiyomi or Mihon:

- Supports `.tachibk` (Mihon/forks) and `.proto.gz` (Tachiyomi) formats
- Extracts MangaDex URLs from your backup
- Adds URLs to your library's `follow.txt`
- Duplicate detection prevents re-adding existing URLs

### Tall Page Splitting

Split long vertical webtoon pages into readable segments:

- Configurable maximum height threshold
- Batch processing for entire libraries
- Preserves original files (creates new split versions)
- Similar to TachiyomiSY's "split tall images" feature

### Enhanced Metadata

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

**Kitsu Metadata Plugin:**
- Fetches title, synopsis, authors, genres, age rating
- Alternative titles in multiple languages
- Cover art downloading
- No API key required

### Chapter Blacklist

Permanently prevent unwanted chapters from being re-downloaded:

- **Blacklist & Delete** via book 3-dot menu — blacklists the chapter URL and deletes the book file
- **Manage Blacklist** via series 3-dot menu — view, remove, and **manually add** blacklisted chapter URLs
- **Manual URL entry** — paste MangaDex chapter URLs directly into the blacklist dialog for edge cases the automatic system can't handle
- Persists even after book deletion (stored in separate database table)
- Respected by both the downloader and chapter checker

#### Automatic Same-Group Duplicate Detection

When a scanlation group uploads the same chapter multiple times on MangaDex (e.g. same chapter number, same group, different UUIDs), the system automatically detects this, keeps the newest upload, and blacklists the older duplicates. No manual intervention needed — the blacklisted count is included in the known count so the ChapterChecker stays in sync.

### Chapter URL Tracking

Never download the same chapter twice:

- Database tracking of all downloaded chapter URLs
- Filesystem duplicate detection via existing CBZ files
- Tracks chapter metadata (volume, language, scanlation group)
- Multi-group support — same chapter from different scanlation groups downloaded separately

> **Important:** `Import chapter URLs` is **enabled by default** and required for the downloader, follow list, and subscription sync to detect already-downloaded chapters. **Disable it** in **Library → Edit → Metadata** for libraries that don't use the download system — otherwise library scans will be significantly slower.

### Guest/Kiosk Mode

Read-only browsing without login — perfect for family or shared setups:

- Toggle in admin **Settings → UI**
- "Als Gast durchsuchen" button on login page
- Per-library guest access — admins select which libraries are visible
- Security: GET-only access to series/books/libraries, admin routes blocked

### Web Log Viewer

Admin-only log viewer in **Settings → Logs**:

- **Live streaming** via SSE — real-time log tailing without polling
- **Pause/Resume** — buffer incoming logs while paused, flush on resume
- **Debug toggle** — switch between INFO and DEBUG log level at runtime (no restart)
- Color-coded log levels (ERROR=red, WARN=orange, DEBUG=grey)
- Client-side search/filter
- Full log file download

### Color Themes

7 predefined theme presets in **Account → UI Settings**:

- Default, AMOLED, Nord, Dracula, Solarized, Green, Red
- Each preset defines both light and dark mode colors
- Persistent selection via browser local storage

### Configurable Folder Naming

Choose how new manga folders are named:

- `uuid` (default) — uses MangaDex UUID like `0c6fe779-...`
- `title` — uses manga title like `Roman Club`
- Set in **Plugin Manager → gallery-dl Downloader** settings
- Only affects new manga — existing folders are never renamed

### Auto-Scan After Download

New chapters are automatically scanned after download completes:

- Uses targeted `scanSeriesFolder()` — only processes the affected series folder
- New books are added, analyzed, and chapter URLs imported automatically
- No full library scan needed

---

## API

Full API documentation with request/response examples: **[API Reference](docs/api-reference.md)**

---

## Switching Between Official Komga and This Fork

The fork stores its database migrations in a separate history table (`flyway_fork_history`), completely independent from the official Komga migration history (`flyway_schema_history`):

- **Official Komga → Fork:** Works. Fork migrations run automatically on first startup.
- **Fork → Official Komga:** Works. Official Komga only sees its own migration history and starts normally. The fork's extra tables and columns remain in the database but are ignored.

---

## Installation

### Requirements

- Java 21+
- [gallery-dl-komga](https://github.com/08shiro80/gallery-dl-komga) (`pip install https://github.com/08shiro80/gallery-dl-komga/archive/refs/heads/master.tar.gz`)

### Docker

See [Quick Start](#quick-start) for Docker and Docker Compose commands.

### Updating gallery-dl-komga in Docker

gallery-dl-komga is installed via pip inside the Docker image. To update:

```bash
docker exec -u 0 komga pip3 install --break-system-packages -U https://github.com/08shiro80/gallery-dl-komga/archive/refs/heads/master.tar.gz
```

### JAR

```bash
java -jar komga.jar
```

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

### Follow List Check Interval

Configure via application properties:

```yaml
komga:
  download:
    follow-check-interval: 24h
```

---

## Comparison with Original Komga

| Feature | Original | This Fork |
|---------|----------|-----------|
| Media Server | Yes | Yes |
| Manga Downloads | No | Yes |
| Automatic Chapter Tracking | No | Yes |
| MangaDex Subscription Sync | No | Yes |
| Follow List Automation | No | Yes |
| Chapter Blacklist | No | Yes |
| Series survives folder rename | No | Yes |
| Auto-scan after download | No | Yes |
| Configurable folder naming | No | Yes (UUID/title) |
| Guest/Kiosk Mode | No | Yes |
| Web Log Viewer | No | Yes |
| Color Themes | No | 7 presets |
| Tachiyomi Import | No | Yes |
| Page Splitting | No | Yes |
| AniList & Kitsu Metadata | No | Yes |
| Real-time Progress | No | Yes (SSE) |

---

## Documentation

- [Download System Guide](docs/downloads.md)
- [Follow List Setup](docs/follow-lists.md)
- [Tachiyomi Migration](docs/tachiyomi-import.md)
- [Page Splitting](docs/page-splitting.md)
- [Metadata Plugins](docs/metadata-plugins.md)
- [Plugin Development](docs/plugin-development.md)
- [API Reference](docs/api-reference.md)

---

## Tech Stack

- **Backend:** Kotlin, Spring Boot, jOOQ
- **Frontend:** Vue.js 2, Vuetify, TypeScript
- **Database:** H2 (embedded) / SQLite
- **Downloads:** gallery-dl integration
- **Metadata:** MangaDex API, AniList GraphQL, auto-detected from source site

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
- [gallery-dl-komga](https://github.com/08shiro80/gallery-dl-komga) - Komga-enhanced fork with genre/tag splitting and extended metadata
- [MangaDex](https://mangadex.org) - Primary manga source and API
- [AniList](https://anilist.co) - Metadata source
- [Kitsu](https://kitsu.app) - Metadata source

---

## License

[MIT License](LICENSE)
