# Follow List Setup

Automatically download new chapters from your favorite manga using follow lists.

## Overview

A `follow.txt` file in your library root tells Komga which manga to monitor for new chapters. The system periodically checks for updates and downloads them automatically.

## Creating a Follow List

1. Navigate to your library folder (e.g., `/manga/`)
2. Create a file named `follow.txt`
3. Add MangaDex URLs, one per line

### Example follow.txt

```
# My favorite manga to follow
https://mangadex.org/title/a1c7c817-4e59-43b7-9365-09c5f56e5eb1
https://mangadex.org/title/32d76d19-8a05-4db0-9fc2-e0b0648fe9d0
https://mangadex.org/title/239d6260-d71f-43b0-afff-074e3619e3de

# Comments start with #
# Empty lines are ignored
```

## Configuration

### Via API

Get current configuration:

```bash
curl http://localhost:25600/api/v1/downloads/follow-config
```

Update configuration:

```bash
curl -X PUT http://localhost:25600/api/v1/downloads/follow-config \
  -H "Content-Type: application/json" \
  -d '{
    "libraryId": "your-library-id",
    "enabled": true,
    "checkIntervalHours": 24
  }'
```

### Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `enabled` | `true` | Enable/disable follow list checking |
| `checkIntervalHours` | `24` | Hours between checks |

## How It Works

1. **Scheduler runs** at configured interval
2. **Reads follow.txt** from each library
3. **Checks MangaDex** for new chapters
4. **Compares** against downloaded chapter history
5. **Downloads** only new chapters
6. **Updates** chapter tracking database

## Per-Library Configuration

Each library can have its own follow list and settings:

```
/manga/
├── follow.txt          # Library-specific follow list
├── Series A/
│   └── ...
└── Series B/
    └── ...

/comics/
├── follow.txt          # Different follow list for comics library
└── ...
```

## Checking Status

View scheduled tasks:

```bash
curl http://localhost:25600/api/v1/downloads/scheduler
```

Response:

```json
{
  "libraries": [
    {
      "libraryId": "abc123",
      "libraryName": "Manga",
      "enabled": true,
      "nextCheckAt": "2024-01-15T12:00:00Z",
      "lastCheckAt": "2024-01-14T12:00:00Z",
      "urlCount": 15
    }
  ]
}
```

## Manual Trigger

Force an immediate check:

```bash
curl -X POST http://localhost:25600/api/v1/downloads/follow-check/{libraryId}
```

## Best Practices

1. **Start small** - Add a few manga first to test
2. **Use comments** - Document why you're following each manga
3. **Check logs** - Monitor for download errors
4. **Set reasonable intervals** - 24 hours is usually sufficient

## Troubleshooting

### New chapters not downloading

- Verify `follow.txt` exists and is readable
- Check if follow list checking is enabled
- Verify MangaDex URLs are valid
- Check download queue for pending items

### Duplicate downloads

- Run library scan to import existing `.chapter-urls.json`
- Check chapter URL database for gaps

### Scheduler not running

- Verify application has write access to config directory
- Check application logs for scheduler errors
- Restart application if needed
