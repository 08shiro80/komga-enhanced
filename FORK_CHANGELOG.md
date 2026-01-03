# Fork Changelog

All notable changes specific to this fork are documented here.

For upstream Komga changes, see [CHANGELOG.md](CHANGELOG.md).

---

## [Unreleased]

### Added

#### MangaDex Download System
- **Download Queue** - Queue-based download management with priority support
- **gallery-dl Integration** - Download manga directly from MangaDex using gallery-dl
- **Real-time Progress** - SSE-based download progress updates in the UI
- **ComicInfo.xml Injection** - Automatic metadata injection into downloaded CBZ files
- **Crash Recovery** - Incremental chapter tracking, downloads resume from last completed chapter
- **Rate Limiting** - Respect MangaDex API limits with configurable throttling
- **Multi-language Support** - Download chapters in preferred language

#### Follow List Automation
- **follow.txt Support** - Per-library follow lists for automatic chapter checking
- **Scheduled Downloads** - Cron-based automatic new chapter detection
- **Configurable Intervals** - Set check frequency per library (default: 24 hours)
- **Duplicate Prevention** - Skip already-downloaded chapters automatically

#### Tachiyomi/Mihon Integration
- **Backup Import** - Import MangaDex URLs from Tachiyomi/Mihon backup files
- **Format Support** - `.tachibk` (Mihon/forks) and `.proto.gz` (Tachiyomi legacy)
- **Bulk Import** - Extract all MangaDex URLs in one operation
- **Duplicate Detection** - Skip URLs already in follow.txt

#### Page Splitting
- **Oversized Page Detection** - Scan for pages with configurable height threshold
- **Tall Image Splitting** - Split vertical webtoon pages into readable segments
- **Batch Processing** - Split all oversized pages in library at once
- **TachiyomiSY-like Feature** - Similar to TachiyomiSY's "split tall images"

#### Metadata Plugins
- **MangaDex Metadata Plugin** - Fetch metadata from MangaDex API
  - Multi-language title support (10+ languages)
  - Author/artist information
  - Genre and tag mapping
  - Cover art downloading
- **AniList Metadata Plugin** - Fetch metadata from AniList GraphQL API
  - Configurable title type (English/Romaji/Native)
  - Detailed descriptions and staff info
  - Genre mapping

#### Chapter URL Tracking
- **Download History** - Track all downloaded chapter URLs in database
- **Import from gallery-dl** - Automatic import of `.chapter-urls.json` files
- **Duplicate Prevention** - Never re-download the same chapter
- **Metadata Tracking** - Store volume, language, scanlation group info

#### API Endpoints
- `GET/POST/DELETE /api/v1/downloads` - Download queue management
- `DELETE /api/v1/downloads/clear/*` - Clear completed/failed/cancelled
- `GET /api/v1/downloads/progress` - SSE progress stream
- `GET/PUT /api/v1/downloads/follow-config` - Follow list configuration
- `GET /api/v1/media-management/oversized-pages` - List oversized pages
- `POST /api/v1/media-management/oversized-pages/split/*` - Split pages
- `POST /api/v1/tachiyomi/import` - Import Tachiyomi backup
- `GET /api/v1/health` - System health check

#### Infrastructure
- **WebSocket Progress Handler** - Real-time download updates via WebSocket
- **MangaDex Rate Limiter** - Respect API rate limits
- **Download Scheduler** - Background scheduled tasks for follow list checking
- **Chapter URL DAO** - Database persistence for chapter tracking

### Changed

- Updated Kotlin version to 2.2.21 (from 2.2.0)
- Updated ktlint plugin to 13.1.0 (from 13.0.0)
- Updated ben-manes versions plugin to 0.53.0 (from 0.52.0)
- Updated JReleaser to 1.21.0 (from 1.19.0)

### Technical Details

#### New Domain Models
- `DownloadQueue` - Download queue entity with status tracking
- `DownloadItem` - Individual chapter download tracking
- `ChapterUrl` - Downloaded chapter URL entity
- `FollowConfig` - Per-library follow configuration
- `UpdateCheck` - Series update checking configuration
- `DownloadProgress` - Progress tracking DTO

#### New Services
- `DownloadExecutor` - Download queue processing
- `DownloadScheduler` - Scheduled follow list checking
- `ChapterUrlImporter` - Import URLs from gallery-dl JSON
- `TachiyomiImporter` - Import from Tachiyomi backups
- `PageSplitter` - Split oversized pages

#### New Infrastructure
- `GalleryDlWrapper` - gallery-dl process management
- `MangaDexRateLimiter` - API rate limiting
- `ImageSplitter` - Core image splitting logic
- `MangaDexMetadataProvider` - MangaDex API integration
- `AniListMetadataProvider` - AniList GraphQL integration

---

## Differences from Upstream

| Feature | Upstream Komga | This Fork |
|---------|----------------|-----------|
| Media Server | Yes | Yes |
| MangaDex Downloads | No | Yes |
| gallery-dl Integration | No | Yes |
| Follow List Automation | No | Yes |
| Tachiyomi Import | No | Yes |
| Page Splitting | No | Yes |
| AniList Metadata | No | Yes |
| MangaDex Metadata | No | Yes |
| Real-time Progress (SSE) | No | Yes |
| Chapter URL Tracking | No | Yes |

---

## Requirements

### Additional Dependencies

- **gallery-dl** - Required for downloads (`pip install gallery-dl`)
- Included in Docker image

### System Requirements

- Java 21+ (same as upstream)
- 2GB+ RAM recommended for download operations
- Sufficient disk space for downloaded manga
