# Follow List Setup

Automatically download new chapters from your favorite manga using per-library follow lists.

## Overview

Each library has a **follow list stored in the database**: a set of manga URLs (MangaDex or any site
supported by gallery-dl) that Komga periodically checks for new chapters and downloads automatically.

Manage the follow list through the **Downloads → Followed** page in the UI, or through the API below.
A `follow.txt` file in the library root is **only a one-time legacy import source** (see
[Legacy follow.txt import](#legacy-followtxt-import)) — it is not the live control file; adding or
removing a follow is done against the database.

## Managing Follows (API)

All follow endpoints are per library and require an admin session. Base path:
`/api/v1/downloads/follows/{libraryId}`.

List the current follows:

```bash
curl http://localhost:25600/api/v1/downloads/follows/{libraryId}
```

Add a single follow:

```bash
curl -X POST http://localhost:25600/api/v1/downloads/follows/{libraryId} \
  -H "Content-Type: application/json" \
  -d '{"url": "https://mangadex.org/title/a1c7c817-4e59-43b7-9365-09c5f56e5eb1"}'
```

Add many at once:

```bash
curl -X POST http://localhost:25600/api/v1/downloads/follows/{libraryId}/batch \
  -H "Content-Type: application/json" \
  -d '{"urls": ["https://mangadex.org/title/...", "https://mangahere.cc/manga/one_piece/"]}'
```

Enable/disable or delete a follow:

```bash
curl -X PATCH  http://localhost:25600/api/v1/downloads/follows/{libraryId}/{id} \
  -H "Content-Type: application/json" -d '{"enabled": false}'
curl -X DELETE http://localhost:25600/api/v1/downloads/follows/{libraryId}/{id}
```

> URLs must be `http(s)` links. MangaDex title URLs get checked through the MangaDex feed API; other
> sites are enumerated via gallery-dl.

## Check Schedule

The check schedule is configured **per library** (not via a global property). Get it:

```bash
curl http://localhost:25600/api/v1/downloads/follows/{libraryId}/schedule
```

Update it:

```bash
curl -X PUT http://localhost:25600/api/v1/downloads/follows/{libraryId}/schedule \
  -H "Content-Type: application/json" \
  -d '{"enabled": true, "scheduleMode": "interval", "intervalHours": 24}'
```

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `true` | Enable/disable scheduled checks for this library |
| `scheduleMode` | `"interval"` | `"interval"` or `"fixed_time"` |
| `intervalHours` | `24` | Hours between checks (used in `interval` mode) |
| `checkTime` | `null` | Time in `HH:mm` (used in `fixed_time` mode) |

## How It Works

1. **Scheduler runs** per library at the configured interval (or fixed time).
2. **Reads the follow list** for that library from the database.
3. **Checks for new chapters** — MangaDex titles through the MangaDex feed API, other sites through
   gallery-dl (`--simulate`).
4. **Compares** against the chapter-URL tracking database (populated by the download flow and by the
   `.chapter-urls.json` sidecars written by gallery-dl).
5. **Downloads** only chapters that are not already tracked and not blacklisted.
6. **Updates** the chapter-URL tracking database.

## Manual Trigger

Force an immediate check for one library:

```bash
curl -X POST http://localhost:25600/api/v1/downloads/follows/{libraryId}/check-now
```

Response: `{ "queued": <int> }` — the number of downloads queued.

## Legacy follow.txt import

If you have an existing `follow.txt` in the library root (one URL per line; `#` comments and blank
lines ignored), import it once into the database follow list:

```bash
curl -X POST http://localhost:25600/api/v1/downloads/follows/{libraryId}/import-follow-txt
```

After import, manage follows through the API/UI above. `follow.txt` is kept as a safety-net snapshot
but is no longer the control file.

## Best Practices

1. **Start small** — add a few manga first to test.
2. **Use the schedule** — 24 hours is usually sufficient; `fixed_time` avoids checking during peak hours.
3. **Check logs** — monitor for download errors.

## Troubleshooting

### New chapters not downloading

- Verify the follow exists and is `enabled` (`GET …/follows/{libraryId}`).
- Verify the schedule is enabled (`GET …/follows/{libraryId}/schedule`).
- Verify URLs are valid (MangaDex or a gallery-dl-supported site).
- Check the download queue for pending/failed items.

### Duplicate downloads

- Run a library scan so existing `.chapter-urls.json` sidecars are imported into the tracking database.
- Check the chapter-URL database for gaps.

### Scheduler not running

- Verify the schedule is `enabled` for the library.
- Check application logs for scheduler errors.
