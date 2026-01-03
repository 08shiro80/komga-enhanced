# Download System Guide

The download system allows you to download manga directly from MangaDex into your Komga library.

## How It Works

1. **Queue-based processing** - Downloads are added to a queue and processed sequentially
2. **gallery-dl integration** - Uses [gallery-dl](https://github.com/mikf/gallery-dl) as the download engine
3. **Automatic metadata** - ComicInfo.xml is injected into each CBZ with MangaDex metadata
4. **Chapter tracking** - Downloaded chapters are tracked to prevent duplicates

## Prerequisites

Install gallery-dl:

```bash
pip install gallery-dl
```

## Adding a Download

### Via API

```bash
curl -X POST http://localhost:25600/api/v1/downloads \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://mangadex.org/title/a1c7c817-4e59-43b7-9365-09c5f56e5eb1",
    "libraryId": "your-library-id"
  }'
```

### Via Web UI

1. Navigate to **Downloads** in the sidebar
2. Click **Add Download**
3. Paste MangaDex URL
4. Select target library
5. Click **Start Download**

## Download States

| Status | Description |
|--------|-------------|
| `PENDING` | Waiting in queue |
| `DOWNLOADING` | Currently downloading |
| `COMPLETED` | Successfully finished |
| `FAILED` | Download failed (will retry) |
| `PAUSED` | Manually paused |
| `CANCELLED` | Manually cancelled |

## Real-time Progress

Connect to the SSE endpoint for live updates:

```javascript
const eventSource = new EventSource('/api/v1/downloads/progress');

eventSource.onmessage = (event) => {
  const progress = JSON.parse(event.data);
  console.log(`${progress.title}: ${progress.percent}%`);
};
```

## Crash Recovery

Each chapter is saved to the database immediately after download. If Komga crashes:

1. Completed chapters remain in the database
2. On restart, downloads resume from the last completed chapter
3. No re-downloading of already saved chapters

## Rate Limiting

The system respects MangaDex API rate limits:

- Requests are throttled automatically
- Configurable delay between chapter downloads
- Prevents API bans

## ComicInfo.xml

Each downloaded CBZ includes metadata:

```xml
<ComicInfo>
  <Title>Chapter 1</Title>
  <Series>Manga Title</Series>
  <Number>1</Number>
  <Volume>1</Volume>
  <Writer>Author Name</Writer>
  <Penciller>Artist Name</Penciller>
  <LanguageISO>en</LanguageISO>
  <Manga>YesAndRightToLeft</Manga>
</ComicInfo>
```

## Clearing Downloads

Clear completed, failed, or cancelled downloads:

```bash
# Clear completed
curl -X DELETE http://localhost:25600/api/v1/downloads/clear/completed

# Clear failed
curl -X DELETE http://localhost:25600/api/v1/downloads/clear/failed

# Clear cancelled
curl -X DELETE http://localhost:25600/api/v1/downloads/clear/cancelled
```

## Troubleshooting

### Download fails immediately

- Check if gallery-dl is installed: `gallery-dl --version`
- Verify MangaDex URL is valid
- Check network connectivity

### Chapters are re-downloading

- Verify `.chapter-urls.json` exists in series folder
- Check database for chapter URL records
- Run library scan to import existing URLs

### Rate limit errors

- Increase delay between downloads in configuration
- Wait for rate limit to reset (usually 1 hour)
