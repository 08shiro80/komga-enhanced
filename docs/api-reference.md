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

## Follow Configuration API

### Get Configuration

```http
GET /api/v1/downloads/follow-config
```

Response:

```json
{
  "libraries": [
    {
      "libraryId": "lib123",
      "libraryName": "Manga",
      "enabled": true,
      "checkIntervalHours": 24,
      "lastCheckAt": "2024-01-15T10:00:00Z",
      "nextCheckAt": "2024-01-16T10:00:00Z",
      "urlCount": 15
    }
  ]
}
```

### Update Configuration

```http
PUT /api/v1/downloads/follow-config
Content-Type: application/json

{
  "libraryId": "lib123",
  "enabled": true,
  "checkIntervalHours": 12
}
```

### Get Follow List

```http
GET /api/v1/downloads/follow-config/{libraryId}/urls
```

Response:

```json
{
  "urls": [
    "https://mangadex.org/title/...",
    "https://mangadex.org/title/..."
  ]
}
```

### Update Follow List

```http
PUT /api/v1/downloads/follow-config/{libraryId}/urls
Content-Type: application/json

{
  "urls": [
    "https://mangadex.org/title/...",
    "https://mangadex.org/title/..."
  ]
}
```

### Trigger Check

```http
POST /api/v1/downloads/follow-check/{libraryId}
```

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
POST /api/v1/tachiyomi/import
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
POST /api/v1/chapter-urls/check
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
DELETE /api/v1/chapter-urls/series/{seriesId}
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
