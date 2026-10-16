# API Reference

Complete API documentation for Komga Enhanced fork features.

## Authentication

All endpoints require authentication via:

- Session cookie (web UI)
- Basic Auth header
- API key header: `X-API-Key: your-key`

## Downloads API

### List Downloads

```http
GET /api/v1/downloads
```

Query parameters:

| Parameter | Type | Description |
|-----------|------|-------------|
| `status` | string | Filter by status (PENDING, DOWNLOADING, COMPLETED, FAILED, PAUSED, CANCELLED) |
| `libraryId` | string | Filter by library |
| `page` | int | Page number (0-indexed) |
| `size` | int | Page size (default: 20) |

Response:

```json
{
  "content": [
    {
      "id": "abc123",
      "url": "https://mangadex.org/title/...",
      "title": "Manga Title",
      "status": "DOWNLOADING",
      "progress": 45,
      "currentChapter": "Chapter 5",
      "totalChapters": 10,
      "libraryId": "lib123",
      "createdAt": "2024-01-15T10:00:00Z",
      "updatedAt": "2024-01-15T10:05:00Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1
}
```

### Create Download

```http
POST /api/v1/downloads
Content-Type: application/json

{
  "url": "https://mangadex.org/title/...",
  "libraryId": "lib123",
  "priority": 0
}
```

Response: Created download object

### Get Download

```http
GET /api/v1/downloads/{id}
```

### Cancel Download

```http
DELETE /api/v1/downloads/{id}
```

### Perform Action

```http
POST /api/v1/downloads/{id}/action
Content-Type: application/json

{
  "action": "RETRY"
}
```

Actions: `RETRY`, `PAUSE`, `RESUME`, `CANCEL`

### Clear Downloads

```http
DELETE /api/v1/downloads/clear/completed
DELETE /api/v1/downloads/clear/failed
DELETE /api/v1/downloads/clear/cancelled
```

Response:

```json
{
  "cleared": 5
}
```

### Download Progress (SSE)

```http
GET /api/v1/downloads/progress
Accept: text/event-stream
```

Events:

```
event: progress
data: {"id":"abc123","percent":45,"currentChapter":"Ch. 5","speed":"1.2 MB/s"}

event: complete
data: {"id":"abc123","title":"Manga Title"}

event: error
data: {"id":"abc123","error":"Rate limited"}
```

### Repair ComicInfo

Retroactively inject missing ComicInfo.xml and ZIP comments into existing MangaDex CBZ files.

```http
POST /api/v1/downloads/repair-comicinfo/{libraryId}
```

Response:

```json
{
  "mangaProcessed": 5,
  "repaired": 12,
  "skipped": 88,
  "errors": []
}
```

## Follow Schedule API (per Library)

Each library has its own follow-list check schedule.

### Get Schedule

```http
GET /api/v1/downloads/follows/{libraryId}/schedule
```

Response:

```json
{
  "libraryId": "lib123",
  "enabled": true,
  "scheduleMode": "interval",
  "intervalHours": 24,
  "checkTime": "16:00",
  "lastCheckTime": "2024-01-15T10:00:00"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `enabled` | boolean | Whether scheduled checks run for this library |
| `scheduleMode` | string | `"interval"` or `"fixed_time"` |
| `intervalHours` | int | Hours between checks (used in `interval` mode) |
| `checkTime` | string? | Time in HH:mm format (used in `fixed_time` mode) |
| `lastCheckTime` | string? | Timestamp of last check |

### Update Schedule

```http
PUT /api/v1/downloads/follows/{libraryId}/schedule
Content-Type: application/json

{
  "enabled": true,
  "scheduleMode": "interval",
  "intervalHours": 24,
  "checkTime": null
}
```

### Trigger Immediate Check

```http
POST /api/v1/downloads/follows/{libraryId}/check-now
```

Response: `{ "queued": <int> }`

## Follow List API (per Library)

The follow list is stored in the database, per library. (`follow.txt` in the library root is only a one-time legacy import source — see *Import follow.txt* below.)

### List Follows

```http
GET /api/v1/downloads/follows/{libraryId}
GET /api/v1/downloads/follows/by-series/{seriesId}
```

### Add a Follow

```http
POST /api/v1/downloads/follows/{libraryId}
Content-Type: application/json

{ "url": "https://mangadex.org/title/...", "title": "Optional", "seriesId": null }
```

### Add Follows in Bulk

```http
POST /api/v1/downloads/follows/{libraryId}/batch
Content-Type: application/json

{ "urls": ["https://mangadex.org/title/...", "https://mangadex.org/title/..."] }
```

Response: `{ "added": <int>, "skipped": <int> }`

### Update / Delete a Follow

```http
PATCH  /api/v1/downloads/follows/{libraryId}/{id}
DELETE /api/v1/downloads/follows/{libraryId}/{id}
DELETE /api/v1/downloads/follows/{libraryId}/batch
```

PATCH body: `{ "title": "...", "enabled": true }` · batch-delete body: `{ "ids": ["...", "..."] }`

### Import follow.txt (legacy, one-time)

Imports URLs from the library root's `follow.txt` into the database follow list.

```http
POST /api/v1/downloads/follows/{libraryId}/import-follow-txt
```

### Sync to MangaDex

Push this library's MangaDex follow URLs to your MangaDex account follow list.

```http
POST /api/v1/downloads/follows/{libraryId}/sync-to-mangadex
```

### MangaDex Account — Follow List

Read / add / remove a manga on the user's MangaDex account follow list (used by the *Add to MangaDex follow list* button and the *Hide titles already on MangaDex follow list* filter in the advanced MangaDex search). All three require the **MangaDex Subscription** plugin to be enabled and configured; they use that plugin's stored credentials.

```http
GET /api/v1/downloads/mangadex/follows
```

Response (lowercase UUIDs, paginated server-side through MangaDex's `/user/follows/manga` and aggregated):

```json
{
  "uuids": ["18d92807-8627-4a23-9ef6-12a0a7fbd054", "..."]
}
```

```http
POST   /api/v1/downloads/mangadex/follows/{mangaId}
DELETE /api/v1/downloads/mangadex/follows/{mangaId}
```

Response (200 on success, 400 with the same body if the plugin is disabled / unauthenticated):

```json
{ "success": true, "message": "Followed on MangaDex" }
```

### MangaDex Subscription — Force Resync

Rewind `last_check_time` and run a feed check immediately. Picks up chapters that were silently dropped earlier (e.g. by a stuck `last_check_time` after a long restart gap, or — before 0.1.4.4 — by the `isUrlAlreadyQueued`/`COMPLETED` guard in `checkFeed`).

```http
POST /api/v1/downloads/mangadex-subscription/force-resync?lookbackDays=N
```

`lookbackDays` defaults to `7`. Response shape matches the follow endpoints (`{success, message}`).

## Maintenance API

### List Available Fix Cards

Schema-driven list of one-click maintenance actions that the WebUI renders dynamically on `/settings/fixes`. New fixes are added by registering a `Fix` in `FixRegistry.kt`; no Vue change required. Each entry's `isEnabled` predicate is evaluated server-side so disabled plugins / inactive features simply don't appear in the response.

```http
GET /api/v1/maintenance/fixes
```

Response:

```json
[
  {
    "id": "mangadex-force-resync",
    "title": "MangaDex Subscription — Force Resync",
    "description": "Rewinds last_check_time and re-runs the followed-manga feed check now...",
    "icon": "mdi-wrench-outline",
    "endpoint": "/api/v1/downloads/mangadex-subscription/force-resync",
    "method": "POST",
    "params": [
      {
        "key": "lookbackDays",
        "label": "Lookback window (days)",
        "type": "number",
        "default": 7,
        "min": 1,
        "max": 30,
        "hint": "How far back to scan the followed-manga feed"
      }
    ]
  }
]
```

Param `type` is one of `number`, `string`, `boolean`. The UI sends the collected values as query parameters to the declared `endpoint`/`method`.

## Oversized Pages API

### List Oversized Pages

```http
GET /api/v1/media-management/oversized-pages
```

Query parameters:

| Parameter | Type | Description |
|-----------|------|-------------|
| `minHeight` | int | Minimum height in pixels (default: 10000) |
| `libraryId` | string | Filter by library |
| `seriesId` | string | Filter by series |
| `page` | int | Page number |
| `size` | int | Page size |

Response:

```json
{
  "content": [
    {
      "bookId": "book123",
      "bookTitle": "Chapter 1",
      "seriesId": "series123",
      "seriesTitle": "Webtoon Title",
      "pageNumber": 1,
      "width": 800,
      "height": 15000,
      "aspectRatio": 0.053,
      "filePath": "/manga/Webtoon/Chapter 1.cbz"
    }
  ],
  "totalElements": 42
}
```

### Split Book Pages

```http
POST /api/v1/media-management/oversized-pages/split/{bookId}
Content-Type: application/json

{
  "maxHeight": 2000,
  "preserveOriginal": true
}
```

Response:

```json
{
  "bookId": "book123",
  "originalPageCount": 5,
  "newPageCount": 25,
  "splitPages": [1, 3, 5]
}
```

### Split All Oversized

```http
POST /api/v1/media-management/oversized-pages/split-all
Content-Type: application/json

{
  "maxHeight": 2000,
  "minHeight": 10000,
  "libraryId": "lib123"
}
```

Response:

```json
{
  "processed": 10,
  "skipped": 2,
  "errors": 0,
  "details": [
    {"bookId": "book1", "newPageCount": 25},
    {"bookId": "book2", "newPageCount": 18}
  ]
}
```

## Tachiyomi Import API

### Import Backup

```http
POST /api/v1/import/tachiyomi
Content-Type: multipart/form-data

file: <backup file>
libraryId: lib123
```

Response:

```json
{
  "imported": 42,
  "skipped": 5,
  "errors": 0,
  "urls": [
    "https://mangadex.org/title/...",
    "https://mangadex.org/title/..."
  ]
}
```

## Chapter URL API

### Check URLs

```http
POST /api/v1/check-urls
Content-Type: application/json

{
  "urls": [
    "https://mangadex.org/chapter/...",
    "https://mangadex.org/chapter/..."
  ]
}
```

Response:

```json
{
  "results": [
    {"url": "https://...", "exists": true, "downloadedAt": "2024-01-10T10:00:00Z"},
    {"url": "https://...", "exists": false}
  ]
}
```

### Clear Chapter URLs

```http
DELETE /api/v1/series/{seriesId}/chapter-urls
```

## Health API

### Health Check

```http
GET /api/v1/health
```

Response:

```json
{
  "status": "UP",
  "components": {
    "database": "UP",
    "diskSpace": "UP",
    "galleryDl": "UP"
  },
  "galleryDlVersion": "1.26.0"
}
```

## Error Responses

All endpoints may return:

```json
{
  "timestamp": "2024-01-15T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid URL format",
  "path": "/api/v1/downloads"
}
```

Common status codes:

| Code | Description |
|------|-------------|
| 400 | Bad request / validation error |
| 401 | Authentication required |
| 403 | Insufficient permissions |
| 404 | Resource not found |
| 429 | Rate limited |
| 500 | Internal server error |
