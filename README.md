# Komga Enhanced

**Komga Enhanced** - A powerful manga media server with integrated manga downloading from 250+ sites, automatic chapter tracking, and Tachiyomi/Mihon backup import.

> **Built on [Komga](https://github.com/gotson/komga)** - Extends the excellent Komga media server with manga downloading and automation features.

---

## Why This Fork?

This fork transforms Komga from a pure media server into a **complete manga management solution**:

| Problem | Solution |
|---------|----------|
| Manually downloading manga | **Automatic downloads** via gallery-dl — supports MangaDex, mangahere, hdoujin, senmanga, weebdex, and [250+ more sites](https://github.com/mikf/gallery-dl/blob/master/docs/supportedsites.md) |
| Losing track of downloaded chapters | **Chapter URL tracking** prevents duplicates |
| Re-downloading after crashes | **DB + filesystem tracking** - never re-download completed chapters |
| Title changes cause re-downloads | **UUID folder names** - MangaDex UUID as folder name, immune to title changes |
| Unwanted chapters keep re-downloading | **Chapter blacklist** - permanently block chapters from being downloaded |
| Syncing MangaDex subscriptions | **MangaDex Subscription Sync** auto-downloads from your feed |
| Migrating from Tachiyomi/Mihon | **Backup import** extracts your MangaDex follows |
| Long vertical webtoon pages | **Page splitting** like TachiyomiSY |
| Missing metadata | **MangaDex & AniList plugins** for rich metadata |

---

## Key Features

### Download System (250+ Sites)

Download manga from MangaDex, mangahere, hdoujin, senmanga, weebdex, and any site supported by [gallery-dl](https://github.com/mikf/gallery-dl/blob/master/docs/supportedsites.md):

- **Queue-based downloads** with priority support
- **Real-time progress** via Server-Sent Events (SSE)
- **ComicInfo.xml injection** - metadata embedded in every CBZ
- **UUID folder names** - uses MangaDex UUID as folder name, immune to title changes and mislabeled titles
- **Crash recovery** - skips already-downloaded chapters via DB + filesystem checks
- **Rate limiting** - respects site-specific API limits
- **Multi-language support** - download chapters in your preferred language
- **Automatic publisher detection** - derives publisher from source site (MangaDex, Mangahere, etc.)
- **Custom gallery-dl path** - point to a local gallery-dl checkout for latest extractors

```
POST /api/v1/downloads
{
  "url": "https://mangadex.org/title/...",
  "libraryId": "your-library-id"
}
```

Any URL supported by gallery-dl works — not just MangaDex.

### Follow List Automation

Automatically check for new chapters from your favorite manga:

1. Create a `follow.txt` file in your library root
2. Add URLs (one per line) — MangaDex URLs get fast aggregate checking, other sites use gallery-dl
3. Configure check interval (default: 24 hours)
4. Fast parallel checking via MangaDex aggregate API (200 manga in under a minute)
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
4. The syncer authenticates via OAuth2, creates/reuses a CustomList, and polls your subscription feed

**How it works:**
- Authenticates with MangaDex via OAuth2 (password grant through Keycloak)
- Creates a dedicated CustomList (`Komga Sync`) for tracking
- Periodically checks `GET /subscription/feed` for new chapters
- Filters by your configured language (default: `en`)
- Queues new manga for download automatically
- Resilient to temporary MangaDex API failures (retries on next scheduled check)

**Configuration** (via Plugin Manager UI):

| Setting | Default | Description |
|---------|---------|-------------|
| `client_id` | — | MangaDex API Client ID |
| `client_secret` | — | MangaDex API Client Secret |
| `username` | — | MangaDex username |
| `password` | — | MangaDex password |
| `sync_interval_minutes` | 30 | How often to check for new chapters |
| `language` | en | Chapter language filter |

**No app restart needed** — the syncer automatically restarts when you save config or toggle the plugin.

### Tachiyomi/Mihon Migration

Import your manga library from Tachiyomi or Mihon:

- Supports `.tachibk` (Mihon/forks) and `.proto.gz` (Tachiyomi) formats
- Extracts MangaDex URLs from your backup
- Adds URLs to your library's `follow.txt`
- Duplicate detection prevents re-adding existing URLs

```
POST /api/v1/tachiyomi/import
Content-Type: multipart/form-data
```

### Tall Page Splitting

Split long vertical webtoon pages into readable segments:

- Configurable maximum height threshold
- Batch processing for entire libraries
- Preserves original files (creates new split versions)
- Similar to TachiyomiSY's "split tall images" feature

```
GET  /api/v1/media-management/oversized-pages
POST /api/v1/media-management/oversized-pages/split/{bookId}
POST /api/v1/media-management/oversized-pages/split-all
```

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

### Chapter Blacklist

Permanently prevent unwanted chapters from being re-downloaded:

- **Blacklist & Delete** via book 3-dot menu — blacklists the chapter URL and deletes the book file
- **Manage Blacklist** via series 3-dot menu — view, remove, and **manually add** blacklisted chapter URLs
- **Manual URL entry** — paste MangaDex chapter URLs directly into the blacklist dialog for edge cases the automatic system can't handle
- Persists even after book deletion (stored in separate database table)
- Respected by both the downloader and chapter checker

#### Edge Case: Duplicate Uploads on MangaDex

In rare cases, a scanlation group uploads the same chapter multiple times on MangaDex. For example, manga [`cbce49c7-6311-4955-8b10-1005775d5cee`](https://mangadex.org/title/cbce49c7-6311-4955-8b10-1005775d5cee) has 27 API entries for only 13 unique chapter numbers — all from the same group (Galaxy Degen Scans). These are duplicate uploads, not multi-group entries.

This causes permanent re-queuing: the MangaDex `/feed` API reports `total=27`, but since each chapter number only produces one CBZ file, the known count stays at 13. The ChapterChecker sees `api=27 > known=13` and keeps queuing the manga for download. Re-downloads and library rescans can inflate the DB count (e.g. to 22), but it can never reach 27.

**Solution:** Open the series 3-dot menu → **Manage Blacklist** → paste the duplicate chapter URLs to blacklist them. The blacklisted count is included in the known count, so blacklisting the extra entries resolves the mismatch.

To find the duplicate chapter URLs, open the manga page on [mangadex.org](https://mangadex.org) and look for chapters with the same number uploaded multiple times by the same group. Right-click the duplicate chapters → copy link, then paste into the Manage Blacklist dialog.

### Chapter URL Tracking

Never download the same chapter twice:

- Database tracking of all downloaded chapter URLs
- Filesystem duplicate detection via existing CBZ files
- Tracks chapter metadata (volume, language, scanlation group)
- Multi-group support — same chapter from different scanlation groups downloaded separately

---

## API Endpoints

### Downloads

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/downloads` | List download queue |
| POST | `/api/v1/downloads` | Add download to queue |
| DELETE | `/api/v1/downloads/{id}` | Cancel/remove download |
| DELETE | `/api/v1/downloads/clear/completed` | Clear completed |
| DELETE | `/api/v1/downloads/clear/failed` | Clear failed |
| DELETE | `/api/v1/downloads/clear/cancelled` | Clear cancelled |
| DELETE | `/api/v1/downloads/clear/pending` | Clear pending |
| GET | `/api/v1/downloads/progress` | SSE progress stream |
| POST | `/api/v1/downloads/check-new` | Check for new chapters and queue |
| POST | `/api/v1/downloads/check-only` | Check for new chapters only |
| POST | `/api/v1/downloads/{libraryId}/migrate-to-uuid` | Migrate title folders to UUID names |

### Chapter Blacklist

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/books/{bookId}/blacklist` | Blacklist a book's chapter |
| DELETE | `/api/v1/books/{bookId}/blacklist` | Remove from blacklist |
| GET | `/api/v1/books/{bookId}/blacklist` | Check if blacklisted |
| GET | `/api/v1/series/{seriesId}/blacklist` | List blacklisted chapters |
| POST | `/api/v1/series/{seriesId}/blacklist` | Manually add URL to blacklist |
| DELETE | `/api/v1/series/{seriesId}/blacklist/{id}` | Remove blacklist entry |

### Follow Configuration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/downloads/follow-config` | Get follow settings |
| PUT | `/api/v1/downloads/follow-config` | Update follow settings |

### Media Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/media-management/oversized-pages` | List oversized pages |
| POST | `/api/v1/media-management/oversized-pages/split/{bookId}` | Split book pages |
| POST | `/api/v1/media-management/oversized-pages/split-all` | Split all oversized |

### System

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/health` | Health check |
| POST | `/api/v1/tachiyomi/import` | Import Tachiyomi backup |

---

## Switching Between Official Komga and This Fork

### Official Komga → Fork: Works

The fork's database migrations run automatically on first startup. All existing data is preserved.

### Fork → Official Komga: Works with One Step

The fork adds extra tables and columns to the database, but these don't interfere with official Komga — it simply ignores them. The only blocker is Flyway: it sees the fork's migration entries in the database history and refuses to start.

To switch back, remove the fork migration entries from the database before starting official Komga:

```sql
DELETE FROM flyway_schema_history WHERE version IN (
  '20250930120000', '20251201000000', '20251201000001',
  '20251201000002', '20251201000003', '20251204000000',
  '20251211000000', '20260301000000', '20260315000000',
  '20260315000001'
);
```

After this, official Komga starts normally. The fork's extra tables and columns remain in the database but are never queried and cause no issues. Fork-specific data (downloads, blacklist, plugin config, MangaDex UUID mappings) stays in the database but is unused.

---

## Switching Between Official Komga and This Fork

Since version 0.1.0, the fork stores its database migrations in a separate history table (`flyway_fork_history`), completely independent from the official Komga migration history (`flyway_schema_history`). This means:

- **Official Komga → Fork:** Works. Fork migrations run automatically on first startup.
- **Fork → Official Komga:** Works. Official Komga only sees its own migration history and starts normally. The fork's extra tables and columns remain in the database but are ignored.
- **Upgrading from fork 0.0.9 or earlier:** The fork automatically moves its entries from `flyway_schema_history` to `flyway_fork_history` on first startup. No manual steps needed.

**If you are on fork version 0.0.9 or earlier** and want to switch back to official Komga without upgrading to 0.1.0 first, you need to remove the fork migration entries manually:

```sql
DELETE FROM flyway_schema_history WHERE version > '20250730173126';
```

After this, official Komga starts normally.

---

## Installation

### Requirements

- Java 21+
- [gallery-dl](https://github.com/mikf/gallery-dl) (`pip install gallery-dl`)

### Docker (Recommended)

```bash
docker pull 08shiro80/komga:latest

docker run -d \
  --name komga \
  -p 25600:25600 \
  -v /path/to/config:/config \
  -v /path/to/manga:/manga \
  08shiro80/komga:latest
```

### Docker Compose

```yaml
version: "3.9"
services:
  komga:
    image: 08shiro80/komga:latest
    container_name: komga
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

### Updating gallery-dl in Docker

To update gallery-dl to the latest version inside your running container:

```bash
docker exec komga pip install -U gallery-dl
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

To use a local gallery-dl checkout (e.g. for latest extractors), set `gallery_dl_path` in the plugin config to the directory containing the `gallery_dl` package. This sets `PYTHONPATH` so `python -m gallery_dl` loads from your local source.

### Follow List Check Interval

Configure via API or application properties:

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
| Manga Downloads (250+ sites) | No | Yes |
| Automatic Chapter Tracking | No | Yes |
| Tachiyomi Import | No | Yes |
| Page Splitting | No | Yes |
| AniList Metadata | No | Yes |
| Follow List Automation | No | Yes |
| MangaDex Subscription Sync | No | Yes |
| Chapter Blacklist | No | Yes |
| Real-time Progress | No | Yes (SSE) |

---

## Documentation

- [Download System Guide](docs/downloads.md)
- [Follow List Setup](docs/follow-lists.md)
- [Tachiyomi Migration](docs/tachiyomi-import.md)
- [Page Splitting](docs/page-splitting.md)
- [Metadata Plugins](docs/metadata-plugins.md)
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
- [gallery-dl](https://github.com/mikf/gallery-dl) by mikf - Download engine
- [MangaDex](https://mangadex.org) - Primary manga source and API
- [AniList](https://anilist.co) - Metadata source

---

## License

[MIT License](LICENSE)
