# Metadata Plugins

Enhance your library with rich metadata from MangaDex, AniList, and Kitsu.

## Available Plugins

| Plugin | Source | Features |
|--------|--------|----------|
| MangaDex | MangaDex API | Titles, descriptions, authors, tags, covers |
| AniList | AniList GraphQL | Titles, descriptions, genres, scores, staff |
| Kitsu | Kitsu API | Titles, synopsis, authors, genres, age rating, covers |

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

The MangaDex metadata plugin (ID `mangadex-metadata`) needs no per-plugin configuration — it fetches
metadata automatically for series that carry a MangaDex URL/ID. The preferred title language follows the
**gallery-dl Downloader** plugin's `default_language` setting (see [Download System Guide](downloads.md)).

Plugins in general are configured through schema-driven endpoints; the request body is defined by each
plugin's own `configSchema`, so it differs per plugin:

```bash
# read a plugin's config schema + current values
curl http://localhost:25600/api/v1/plugins/{pluginId}/config
# save config values
curl -X POST http://localhost:25600/api/v1/plugins/{pluginId}/config \
  -H "Content-Type: application/json" -d '{ "key": "value" }'
```

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

AniList is an external plugin (ID `anilist-metadata`). Any configurable options it exposes are set
through the generic schema-driven endpoint `GET`/`POST /api/v1/plugins/anilist-metadata/config` — the
body shape is defined by the plugin's own `configSchema`. AniList provides English, Romaji and Native
titles (e.g. "Attack on Titan" / "Shingeki no Kyojin" / "進撃の巨人").

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

## Kitsu Metadata Plugin

Fetches metadata from the Kitsu API. No API key required.

### Features

- Canonical and alternative titles in multiple languages
- Synopsis
- Author and staff information
- Genre mapping
- Age rating
- Cover images
- Publication status

### How It Works

Search for a manga by title via **Series → Search Online Metadata → Kitsu**. Select a result to apply metadata.

## Plugin Priority

When multiple plugins can provide metadata:

1. Local `series.json` overrides always take priority
2. MangaDex plugin runs first (if URL available)
3. AniList plugin runs second (if ID available)
4. Kitsu plugin available for manual search
5. Existing Komga metadata preserved if not overwritten

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

- Set the preferred language via the gallery-dl Downloader's `default_language`
- Check if the preferred language exists on the source

### API rate limits

- AniList: 90 requests per minute
- MangaDex: Automatic rate limiting
- Reduce concurrent scans if hitting limits

## Manual Metadata Apply

Apply metadata from the configured providers to a series (this is what the **Series → Search Online
Metadata** flow calls). The request body carries the selected match returned by the plugin search:

```bash
curl -X POST http://localhost:25600/api/v1/plugins/apply-metadata/{seriesId} \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "mangadex",
    "externalId": "a1c7c817-4e59-43b7-9365-09c5f56e5eb1",
    "title": "Series Title",
    "summary": "…",
    "status": "ONGOING",
    "genres": ["Action"],
    "tags": ["Long Strip"]
  }'
```

The body is a resolved-metadata payload written to `series.json` (fields: `title`, `summary`,
`publisher`, `ageRating`, `releaseDate`, `status`, `externalId`, `genres`, `tags`, `authors`,
`alternativeTitles`, `provider` — all optional).
