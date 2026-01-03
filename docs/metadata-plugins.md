# Metadata Plugins

Enhance your library with rich metadata from MangaDex and AniList.

## Available Plugins

| Plugin | Source | Features |
|--------|--------|----------|
| MangaDex | MangaDex API | Titles, descriptions, authors, tags, covers |
| AniList | AniList GraphQL | Titles, descriptions, genres, scores, staff |

## MangaDex Metadata Plugin

Fetches metadata directly from MangaDex for downloaded manga.

### Features

- Multi-language title support (10+ languages)
- Author and artist information
- Genre and tag mapping
- Cover art
- Publication status
- Content ratings

### Configuration

```bash
curl -X PUT http://localhost:25600/api/v1/plugins/mangadex/config \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "preferredTitleLanguage": "en",
    "fallbackLanguages": ["ja-ro", "ja"]
  }'
```

### Language Options

| Code | Language |
|------|----------|
| `en` | English |
| `ja` | Japanese |
| `ja-ro` | Japanese Romanized |
| `ko` | Korean |
| `ko-ro` | Korean Romanized |
| `zh` | Chinese (Simplified) |
| `zh-hk` | Chinese (Traditional) |
| `es` | Spanish |
| `fr` | French |
| `de` | German |

### How It Works

1. During library scan, checks for MangaDex URL in `series.json`
2. Fetches metadata from MangaDex API
3. Maps fields to Komga metadata format
4. Updates series metadata

### series.json Format

Create `series.json` in your series folder:

```json
{
  "mangadexId": "a1c7c817-4e59-43b7-9365-09c5f56e5eb1"
}
```

Or with full URL:

```json
{
  "mangadexUrl": "https://mangadex.org/title/a1c7c817-4e59-43b7-9365-09c5f56e5eb1"
}
```

## AniList Metadata Plugin

Fetches metadata from AniList using GraphQL API.

### Features

- English, Romaji, and Native titles
- Detailed descriptions
- Genre mapping
- Staff information
- Scores and ratings
- Cover images

### Configuration

```bash
curl -X PUT http://localhost:25600/api/v1/plugins/anilist/config \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "preferredTitleType": "english",
    "fallbackTitleTypes": ["romaji", "native"]
  }'
```

### Title Type Options

| Type | Example |
|------|---------|
| `english` | "Attack on Titan" |
| `romaji` | "Shingeki no Kyojin" |
| `native` | "進撃の巨人" |

### series.json Format

```json
{
  "anilistId": 16498
}
```

Or with URL:

```json
{
  "anilistUrl": "https://anilist.co/manga/16498"
}
```

## Plugin Priority

When multiple plugins can provide metadata:

1. Local `series.json` overrides always take priority
2. MangaDex plugin runs first (if URL available)
3. AniList plugin runs second (if ID available)
4. Existing Komga metadata preserved if not overwritten

## Automatic Detection

For downloaded manga, metadata sources are detected automatically:

- **MangaDex downloads**: MangaDex ID from download URL
- **ComicInfo.xml**: Extract IDs from Notes or Web fields
- **Folder name**: Match against AniList/MangaDex search

## Field Mapping

### MangaDex to Komga

| MangaDex | Komga |
|----------|-------|
| `title` | Series title |
| `description` | Summary |
| `author` | Writers |
| `artist` | Pencillers |
| `tags` | Tags |
| `status` | Status |
| `contentRating` | Age rating |

### AniList to Komga

| AniList | Komga |
|---------|-------|
| `title` | Series title |
| `description` | Summary |
| `staff (author)` | Writers |
| `staff (artist)` | Pencillers |
| `genres` | Genres |
| `status` | Status |

## Troubleshooting

### Metadata not updating

- Verify plugin is enabled
- Check series.json exists and is valid JSON
- Run library scan or force metadata refresh
- Check application logs for API errors

### Wrong title language

- Adjust `preferredTitleLanguage` setting
- Add fallback languages
- Check if preferred language exists on source

### API rate limits

- AniList: 90 requests per minute
- MangaDex: Automatic rate limiting
- Reduce concurrent scans if hitting limits

## Manual Refresh

Force metadata refresh for a series:

```bash
curl -X POST http://localhost:25600/api/v1/series/{seriesId}/metadata/refresh
```
