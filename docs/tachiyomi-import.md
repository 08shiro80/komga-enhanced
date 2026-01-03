# Tachiyomi/Mihon Migration Guide

Import your MangaDex library from Tachiyomi, Mihon, or Mihon forks directly into Komga.

## Supported Formats

| App | Backup Format | Extension |
|-----|---------------|-----------|
| Tachiyomi (legacy) | Protocol Buffer + gzip | `.proto.gz` |
| Mihon | TAR archive | `.tachibk` |
| TachiyomiSY | TAR archive | `.tachibk` |
| TachiyomiJ2K | TAR archive | `.tachibk` |
| Yokai | TAR archive | `.tachibk` |

## Creating a Backup

### In Tachiyomi/Mihon

1. Go to **Settings** > **Data and storage**
2. Tap **Create backup**
3. Save the backup file
4. Transfer to your computer

## Importing to Komga

### Via Web UI

1. Navigate to **Settings** > **Tachiyomi Import**
2. Select your target library
3. Upload your backup file
4. Review extracted URLs
5. Click **Import**

### Via API

```bash
curl -X POST http://localhost:25600/api/v1/tachiyomi/import \
  -F "file=@backup.tachibk" \
  -F "libraryId=your-library-id"
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

## What Gets Imported

The importer extracts:

- **MangaDex URLs** from your library
- Filters for MangaDex source only (source ID: `2499283573021220255`)
- Other sources are ignored

## Import Process

1. **Parse backup** - Decompress and read backup data
2. **Filter sources** - Keep only MangaDex entries
3. **Extract URLs** - Convert source IDs to MangaDex URLs
4. **Check duplicates** - Skip URLs already in follow.txt
5. **Add to follow.txt** - Append new URLs to library's follow list

## Post-Import

After importing:

1. **Check follow.txt** - Verify URLs were added
2. **Trigger download check** - Manually or wait for scheduler
3. **Monitor downloads** - Watch progress in download queue

## Duplicate Handling

The importer automatically:

- Checks existing follow.txt entries
- Checks chapter URL database
- Skips already-known URLs
- Reports skipped count in response

## Limitations

- **MangaDex only** - Other sources (MangaPlus, etc.) are not imported
- **No reading progress** - Only manga URLs are imported, not chapter progress
- **No categories** - Tachiyomi categories are not preserved

## Troubleshooting

### "Invalid backup format"

- Verify file is a valid Tachiyomi/Mihon backup
- Check file extension matches format
- Try re-creating the backup

### No URLs imported

- Verify you have MangaDex manga in your Tachiyomi library
- Check that MangaDex extension was installed in Tachiyomi
- Try a fresh backup

### Some manga missing

- Manga from non-MangaDex sources are intentionally skipped
- Check if manga was added via a different source in Tachiyomi

## Example Workflow

```bash
# 1. Import backup
curl -X POST http://localhost:25600/api/v1/tachiyomi/import \
  -F "file=@mihon-backup.tachibk" \
  -F "libraryId=abc123"

# 2. Check follow.txt was updated
cat /manga/follow.txt

# 3. Trigger immediate check
curl -X POST http://localhost:25600/api/v1/downloads/follow-check/abc123

# 4. Monitor downloads
curl http://localhost:25600/api/v1/downloads
```
