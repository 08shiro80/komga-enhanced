# Komga with MangaDex Downloader

**Komga Enhanced** - A powerful manga media server with integrated MangaDex downloading, automatic chapter tracking, and Tachiyomi/Mihon backup import.

> **Built on [Komga](https://github.com/gotson/komga)** - Extends the excellent Komga media server with manga downloading and automation features.

---

## Why This Fork?

This fork transforms Komga from a pure media server into a **complete manga management solution**:

| Problem | Solution |
|---------|----------|
| Manually downloading manga from MangaDex | **Automatic downloads** via gallery-dl integration |
| Losing track of downloaded chapters | **Chapter URL tracking** prevents duplicates |
| Re-downloading after crashes | **DB + filesystem tracking** - never re-download completed chapters |
| Migrating from Tachiyomi/Mihon | **Backup import** extracts your MangaDex follows |
| Long vertical webtoon pages | **Page splitting** like TachiyomiSY |
| Missing metadata | **MangaDex & AniList plugins** for rich metadata |

---

## Key Features

### MangaDex Download System

Download manga directly from MangaDex with full automation:

- **Queue-based downloads** with priority support
- **Real-time progress** via Server-Sent Events (SSE)
- **ComicInfo.xml injection** - metadata embedded in every CBZ
- **Crash recovery** - skips already-downloaded chapters via DB + filesystem checks
- **Rate limiting** - respects MangaDex API limits
- **Multi-language support** - download chapters in your preferred language

```
POST /api/v1/downloads
{
  "url": "https://mangadex.org/title/...",
  "libraryId": "your-library-id"
}
```

### Follow List Automation

Automatically check for new chapters from your favorite manga:

1. Create a `follow.txt` file in your library root
2. Add MangaDex URLs (one per line)
3. Configure check interval (default: 24 hours)
4. Fast parallel checking via MangaDex aggregate API (200 manga in under a minute)
5. New chapters download automatically

```
# Example follow.txt
https://mangadex.org/title/a1c7c817-4e59-43b7-9365-09c5f56e5eb1
https://mangadex.org/title/32d76d19-8a05-4db0-9fc2-e0b0648fe9d0
```

### Tachiyomi/Mihon Migration

Import your MangaDex library from Tachiyomi or Mihon:

- Supports `.tachibk` (Mihon/forks) and `.proto.gz` (Tachiyomi) formats
- Extracts all MangaDex URLs from your backup
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
| MangaDex Downloads | No | Yes |
| Automatic Chapter Tracking | No | Yes |
| Tachiyomi Import | No | Yes |
| Page Splitting | No | Yes |
| AniList Metadata | No | Yes |
| Follow List Automation | No | Yes |
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
- **Metadata:** MangaDex API, AniList GraphQL

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
- [MangaDex](https://mangadex.org) - Manga source and API
- [AniList](https://anilist.co) - Metadata source

---

## License

[MIT License](LICENSE)
