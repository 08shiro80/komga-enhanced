# Page Splitting Guide

Split tall webtoon/long-strip pages into readable segments, similar to TachiyomiSY's "split tall images" feature.

## Why Split Pages?

Long vertical webtoon pages can be:

- Difficult to read on certain devices
- Slow to render in web readers
- Incompatible with page-based readers
- Memory-intensive to display

Splitting divides these into standard page-sized segments.

## Finding Oversized Pages

### Via API

```bash
curl "http://localhost:25600/api/v1/media-management/oversized-pages?minHeight=10000"
```

Response:

```json
{
  "content": [
    {
      "bookId": "abc123",
      "bookTitle": "Chapter 1",
      "seriesTitle": "My Webtoon",
      "pageNumber": 1,
      "width": 800,
      "height": 15000,
      "aspectRatio": 0.053
    }
  ],
  "totalElements": 42
}
```

### Query Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `minHeight` | `10000` | Minimum pixel height to consider "oversized" |
| `libraryId` | - | Filter by library |
| `seriesId` | - | Filter by series |

## Splitting Pages

### Split Single Book

```bash
curl -X POST "http://localhost:25600/api/v1/media-management/oversized-pages/split/abc123" \
  -H "Content-Type: application/json" \
  -d '{
    "maxHeight": 2000
  }'
```

### Split All Oversized Pages

```bash
curl -X POST "http://localhost:25600/api/v1/media-management/oversized-pages/split-all" \
  -H "Content-Type: application/json" \
  -d '{
    "maxHeight": 2000,
    "minHeight": 10000
  }'
```

## Split Options

| Option | Default | Description |
|--------|---------|-------------|
| `maxHeight` | `2000` | Target height for split segments |
| `minHeight` | `10000` | Only split pages taller than this |
| `preserveOriginal` | `true` | Keep original file (renamed) |

## How It Works

1. **Detect** tall pages exceeding `minHeight`
2. **Calculate** optimal split points
3. **Split** image into segments of `maxHeight`
4. **Repackage** CBZ with new pages
5. **Update** database with new page count
6. **Preserve** original file (optional)

### Example

A page with height 15,000px split at maxHeight=2000:

```
Original: page_001.jpg (800x15000)

Split into:
- page_001.jpg (800x2000)
- page_002.jpg (800x2000)
- page_003.jpg (800x2000)
- page_004.jpg (800x2000)
- page_005.jpg (800x2000)
- page_006.jpg (800x2000)
- page_007.jpg (800x2000)
- page_008.jpg (800x1000)  <- remaining height
```

## Via Web UI

1. Navigate to **Media Management** > **Oversized Pages**
2. Review detected pages with thumbnails
3. Select books to split
4. Configure split height
5. Click **Split Selected** or **Split All**

## Best Practices

### Choosing maxHeight

| Use Case | Recommended maxHeight |
|----------|----------------------|
| Mobile reading | 1500-2000 |
| Desktop reading | 2000-3000 |
| E-reader (Kobo) | 1200-1600 |

### When to Split

- Webtoons with very long vertical pages
- Manga with combined double-page spreads
- Before syncing to Kobo/e-readers

### When NOT to Split

- Standard manga pages (typically 1200-1800px tall)
- Content where page breaks would disrupt flow
- Already split content

## Troubleshooting

### Pages not detected

- Lower `minHeight` threshold
- Run library scan to update page dimensions
- Check that media analysis is complete

### Split creates too many pages

- Increase `maxHeight` value
- Only split specifically problematic books

### Original files lost

- Check backup was created (`.original` suffix)
- Enable `preserveOriginal` option

## Reverting Splits

If you need to restore original files:

1. Find original file: `Chapter_001.cbz.original`
2. Delete split version: `rm Chapter_001.cbz`
3. Rename original: `mv Chapter_001.cbz.original Chapter_001.cbz`
4. Rescan library
