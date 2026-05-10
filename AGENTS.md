# AGENTS.md — komga-enhanced

Notes for an AI coding agent (or a tired human) joining this repo. The
upstream Komga project documents itself elsewhere; this file is just the
local fork's context.

## What this fork is

Local fork of [komga](https://github.com/gotson/komga), tagged
`v1.24.4-fork-0.1.4.x`. Adds a plugin system layered on top of upstream's
`OnlineMetadataProvider` interface, plus three first-party plugins:

| Plugin | Type | Where it lives |
|---|---|---|
| `anilist-metadata` | METADATA | `komga/src/main/kotlin/org/gotson/komga/infrastructure/metadata/anilist/AniListMetadataPlugin.kt` |
| `mangadex-metadata` | METADATA | `komga/src/main/kotlin/org/gotson/komga/infrastructure/metadata/mangadex/MangaDexMetadataPlugin.kt` |
| `kitsu-metadata` | METADATA | `komga/src/main/kotlin/org/gotson/komga/infrastructure/metadata/kitsu/KitsuMetadataPlugin.kt` |
| `scrobbler` | NOTIFIER | `komga/src/main/kotlin/org/gotson/komga/infrastructure/scrobbler/ScrobblerPlugin.kt` |

Plus the supporting Mylar `series.json` reader (used as the apply path —
see below).

## Build & deploy

The project ships a single root `Dockerfile` that does Gradle + node, then
runs the result via Spring Boot's layered jarmode. Image is consumed by
`/home/jack/docker/komga/docker-compose.yml`.

```sh
# rebuild image
cd /home/jack/dev/komga-enhanced
docker build -t komga-scrobbler:latest .

# IMPORTANT: docker restart will not pick up a new image. Use compose recreate.
cd /home/jack/docker/komga
docker compose up -d --force-recreate
```

A clean rebuild takes ~5 min. The Spring Boot fat-jar inside the container
lives at `/app/application.jar`; if its mtime predates your build, the
container is still on the old image — recreate.

## Architecture pitfall: the scrobbler ↔ AniList plugin handshake

Don't read these in isolation; they only make sense together.

```
        AniList plugin search/apply              user reads chapter
                  │                                       │
                  ▼                                       ▼
PluginController.applyMetadata()           ReadProgress event
                  │                                       │
                  ▼                                       │
       writes series.json                                 │
                  │                                       ▼
                  ▼                          ScrobblerPlugin (NOTIFIER)
   library scan / metadata refresh                        │
                  │                                       │
                  ▼                            reads series.metadata.links
   MylarSeriesProvider parses series.json                 │
                  │                                       │
                  ▼                          extracts anilist.co/manga/<id>
   produces SeriesMetadataPatch                           │
   with WebLink to the right host          ──────────────▶│
                  │                                       │
                  ▼                                       ▼
   SeriesMetadata.links populated         pushes progress to AniList GraphQL
```

Three things must hold for the scrobbler to actually fire on AniList:
1. `series.json` is parseable by `MylarSeriesProvider` (Status enum must
   accept whatever the source wrote — see the patches in `Status.kt`).
2. `series.json` carries a `web_url` pointing to the right tracker host
   (anilist.co / mangadex.org / kitsu.app). `comicid` alone is insufficient
   because Mylar historically assumed it was always a MangaDex UUID.
3. `MylarSeriesProvider` produces a `WebLink` whose URL the scrobbler can
   regex-extract a tracker ID from.

If any of those break silently, the scrobbler logs nothing user-visible —
you only learn it failed by checking AniList and seeing no progress
update.

## Where the bodies are buried

**`MylarSeriesProvider.kt`** ─ the apply path is "AniList plugin → write
`series.json` → re-scan → MylarSeriesProvider parses". So MylarSeriesProvider
is the *real* metadata sink for all metadata plugins — not the
`OnlineMetadataProvider` interface that those plugins implement directly.
This is non-obvious. If you're tempted to add a `links` field directly to
`MetadataDetails`, you'll be surprised that nothing reads it from there;
`MetadataDetails` only flows through the apply UI, not the refresh path.

**`Status.kt`** ─ the enum is matched against literal strings written by
multiple sources (Mylar/ComicTagger lowercase, AniList GraphQL UPPERCASE,
some manga-py forks Title-Case). Always add aliases in all three cases when
extending; Jackson's enum deserializer is exact-match only. There's no
`@JsonCreator` doing case-insensitive coercion.

**`PluginController.applyMetadata` → `writeSeriesJson`** ─ When a user
clicks "Apply" on a metadata search result, the controller writes
`series.json` and **returns**. The next library scan / metadata refresh is
what actually applies that data. So a bug in `writeSeriesJson` won't show
up at the click — it shows up an arbitrary number of seconds later when
the refresh queue gets to it. Long book queues delay the series-level
patch (each book gets a `RefreshBookMetadata` task ahead of the
series-level `RefreshSeriesMetadata`).

**`PluginApplyMetadataRequest.provider`** ─ Optional field. If callers
don't pass it, `writeSeriesJson` falls back to a UUID-vs-digits heuristic.
The current fork's WebUI sends it (`EditSeriesDialog.vue` →
`komga-plugins.service.ts`); third-party clients don't.

**Tasks queue is single-threaded** ─ `taskProcessor-N` threads pull from a
shared work queue. After `POST /api/v1/series/{id}/metadata/refresh` you
typically wait through ~`booksCount` book refresh tasks before the
series-level apply runs. For a 1200-chapter One Piece this is multiple
minutes. Verifying via "Apply metadata for series: X" log line is the
reliable signal; don't trust the API response timing.

**Scrobbler logging** ─ The success path **is silent**. The only line
emitted at INFO is `Scrobbler plugin loaded (enabled=true)` at startup.
Decision lines like `Already synced chapter 1 or higher for '<series>'` are
DEBUG. Set:

```
POST /actuator/loggers/org.gotson.komga.infrastructure.scrobbler
{"configuredLevel":"DEBUG"}
```

This is reset on container recreate.

**Scrobbler "Already synced ≥N"** ─ Misleading. The scrobbler only emits
this when its decision is *no-op*. Successful pushes don't log at DEBUG
either — they just happen. Verify by querying AniList directly:

```js
// from a tab on https://anilist.co (uses session cookie)
fetch('/graphql', {
  method:'POST', credentials:'include',
  headers:{'Content-Type':'application/json'},
  body: JSON.stringify({
    query: 'query($id:Int){Media(id:$id,type:MANGA){title{english} mediaListEntry{progress status updatedAt}}}',
    variables: {id: 86848}
  })
}).then(r=>r.json()).then(console.log)
```

If `/graphql` ever returns `Forbidden. (Use graphql subdomain)`, hit
`https://graphql.anilist.co/` from a CORS-capable origin instead (e.g.
shell with the user's bearer token).

## The Komga REST API is your friend

For diagnostics, drive the API rather than the UI. From an authenticated
browser tab:

```js
// Series + metadata
fetch('/api/v1/series/<id>', {credentials:'include'}).then(r=>r.json())

// Patch links / lock
fetch('/api/v1/series/<id>/metadata', {
  method:'PATCH', credentials:'include',
  headers:{'Content-Type':'application/json'},
  body: JSON.stringify({linksLock:false, links:[]})
})

// Trigger metadata refresh
fetch('/api/v1/series/<id>/metadata/refresh', {method:'POST', credentials:'include'})

// Mark a book complete (triggers scrobbler)
fetch('/api/v1/books/<bookId>/read-progress', {
  method:'PATCH', credentials:'include',
  headers:{'Content-Type':'application/json'},
  body: JSON.stringify({completed:true})
})

// Plugin loggers (via Spring actuator)
fetch('/actuator/loggers/<dotted.logger.name>', {
  method:'POST', credentials:'include',
  headers:{'Content-Type':'application/json'},
  body: JSON.stringify({configuredLevel:'DEBUG'})
})
```

`/api/v1/logs` is live (what the UI logs page consumes). `/actuator/logfile`
is a buffered snapshot — can be **30+ minutes stale**. For real-time
debugging, prefer `docker logs komga`.

## Migration helpers in this repo

These are throwaway scripts kept around because the scenarios recur:

| Script | What it does |
|---|---|
| `migrate-series-json-weburl.sh` | Adds `web_url` to legacy series.json files where `comicid` is all-digits (i.e. AniList-shaped). Idempotent. Dry-run by default; set `APPLY=1`. |
| `wait-ready.sh` | Polls `localhost:25600` until Komga is up after a recreate. |
| `wait-mylar.sh` / `verify-mylar.sh` / `verify-onepiece.sh` | Wait for the queued series-level refresh to land for Lookism / One Piece, then dump the relevant log lines. |
| `check-migration.sh` | Spot-check `web_url` / `comicid` / `status` across the three test series. |

## Suwayomi quirk

The user's library is sourced from Suwayomi at
`/mnt/drive2/suwayomi/downloads/mangas/<source>/<series>/`. Suwayomi
**rewrites `series.json`** on every chapter download and uses uppercase
status strings (`RELEASING`, `FINISHED`, `HIATUS`, `CANCELLED`). Any change
to that file made by Komga's apply flow will be clobbered the next time
Suwayomi touches the series — so any patch we want to persist must either:
- be a JSON key Suwayomi doesn't overwrite (untested), or
- be re-applied each time Komga refreshes (which is the path we picked —
  Komga reads the latest Suwayomi file and translates the AniList-shaped
  `comicid` + status into the right `WebLink`).

The migration script's `web_url` injection is a **one-shot bridge** for
*existing* Komga `series.json` files written by the old buggy apply flow.
Going forward, `writeSeriesJson` writes `web_url` itself; for Suwayomi-
authored files, the heuristic in `MylarSeriesProvider` (digits → AniList)
covers them without any disk migration.

## Containers in this homelab

Komga runs in WSL2 Ubuntu-22.04 alongside ~25 other containers. Compose
file: `/home/jack/docker/komga/docker-compose.yml`. Named volumes are
**bind mounts**: `/home/jack/docker/komga:/config` and
`/mnt/drive2:/data`. The `:/data` mount is what makes the manga library
reachable to Komga at `/data/suwayomi/...`.

Image is rebuilt locally as `komga-scrobbler:latest`. There is no
registry push.
