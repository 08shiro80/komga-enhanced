---
name: mangadex-api
description: MangaDex API reference for debugging downloads, chapter checking, and metadata fetching
---

# MangaDex API Reference

Base URL: `https://api.mangadex.org`

## All Endpoints

### Manga
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/manga` | Search manga |
| POST | `/manga` | Create manga |
| GET | `/manga/{id}` | Manga details (title, altTitles, status, tags) |
| PUT | `/manga/{id}` | Update manga |
| DELETE | `/manga/{id}` | Delete manga |
| GET | `/manga/{id}/feed` | Chapter list (paginated, max 500) |
| GET | `/manga/{id}/aggregate` | Unique chapter numbers grouped by volume |
| GET | `/manga/{id}/status` | Reading status |
| POST | `/manga/{id}/status` | Set reading status |
| POST | `/manga/{id}/follow` | Follow manga |
| DELETE | `/manga/{id}/follow` | Unfollow manga |
| GET | `/manga/{id}/recommendation` | Recommendations |
| GET | `/manga/random` | Random manga |
| GET | `/manga/tag` | All manga tags |
| GET | `/manga/status` | All reading statuses |
| GET | `/manga/read` | Read chapter markers (batch) |
| GET | `/manga/{id}/read` | Read markers for manga |
| POST | `/manga/{id}/read` | Mark chapters read/unread |
| GET | `/manga/draft/{id}` | Get manga draft |
| POST | `/manga/draft/{id}/commit` | Commit manga draft |

### Chapter
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/chapter` | Search chapters |
| GET | `/chapter/{id}` | Single chapter details |
| PUT | `/chapter/{id}` | Update chapter |
| DELETE | `/chapter/{id}` | Delete chapter |

### AtHome (Image Server)
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/at-home/server/{chapterId}` | Image server URL + page filenames |

### Cover
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/cover` | Search covers |
| GET | `/cover/{id}` | Cover details |
| POST | `/cover/{mangaOrCoverId}` | Upload cover |
| PUT | `/cover/{mangaOrCoverId}` | Edit cover |
| DELETE | `/cover/{mangaOrCoverId}` | Delete cover |
| - | `https://uploads.mangadex.org/covers/{mangaId}/{filename}` | Cover image URL |

### Author
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/author` | Search authors |
| POST | `/author` | Create author |
| GET | `/author/{id}` | Author details |
| PUT | `/author/{id}` | Update author |
| DELETE | `/author/{id}` | Delete author |

### ScanlationGroup
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/group` | Search groups |
| POST | `/group` | Create group |
| GET | `/group/{id}` | Group details |
| PUT | `/group/{id}` | Update group |
| DELETE | `/group/{id}` | Delete group |
| POST | `/group/{id}/follow` | Follow group |
| DELETE | `/group/{id}/follow` | Unfollow group |

### CustomList
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/list` | Create custom list |
| GET | `/list/{id}` | List details |
| PUT | `/list/{id}` | Update list |
| DELETE | `/list/{id}` | Delete list |
| POST | `/list/{id}/follow` | Follow list |
| DELETE | `/list/{id}/follow` | Unfollow list |
| GET | `/list/{id}/feed` | List chapter feed |
| POST | `/manga/{id}/list/{listId}` | Add manga to list |
| DELETE | `/manga/{id}/list/{listId}` | Remove manga from list |
| GET | `/user/list` | My custom lists |
| GET | `/user/{id}/list` | User's public lists |

### Follows / Feed
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/user/follows/manga/feed` | Followed manga chapter feed |
| GET | `/user/follows/manga` | List followed manga |
| GET | `/user/follows/manga/{id}` | Check if following manga |
| GET | `/user/follows/group` | List followed groups |
| GET | `/user/follows/group/{id}` | Check if following group |
| GET | `/user/follows/user` | List followed users |
| GET | `/user/follows/user/{id}` | Check if following user |
| GET | `/user/follows/list` | List followed custom lists |
| GET | `/user/follows/list/{id}` | Check if following list |

### User / Account
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/user` | Search users |
| GET | `/user/{id}` | User details |
| DELETE | `/user/{id}` | Delete user |
| GET | `/user/me` | Current user info |
| POST | `/user/delete/{code}` | Confirm account deletion |

### Authentication
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/auth/login` | Login (personal API client) |
| GET | `/auth/check` | Check auth status |
| POST | `/auth/logout` | Logout |
| POST | `/auth/refresh` | Refresh token |

### ApiClient
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/client` | List API clients |
| POST | `/client` | Create API client |
| GET | `/client/{id}` | Client details |
| POST | `/client/{id}` | Edit client |
| DELETE | `/client/{id}` | Delete client |
| GET | `/client/{id}/secret` | Get client secret |
| POST | `/client/{id}/secret` | Regenerate secret |

### Infrastructure
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/ping` | Health check |

### Legacy
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/legacy/mapping` | Map old IDs to new UUIDs |

## Response Structures

### `/manga/{id}` title resolution
```
data.attributes.title.en → primary title
data.attributes.altTitles[] → array of {lang: title} objects
```
Title priority: altTitles `en` → title `en` → first available title

### `/manga/{id}/feed` chapter entry
```json
{
  "id": "chapter-uuid",
  "attributes": {
    "chapter": "5",
    "volume": "1",
    "title": "Chapter Title",
    "translatedLanguage": "en",
    "publishAt": "2024-01-15T00:00:00+00:00",
    "pages": 24
  },
  "relationships": [
    { "type": "scanlation_group", "id": "group-uuid", "attributes": { "name": "Group Name" } }
  ]
}
```

### `/manga/{id}/aggregate` response
```json
{
  "volumes": {
    "1": {
      "volume": "1",
      "count": 5,
      "chapters": {
        "1": { "chapter": "1", "id": "uuid", "others": ["uuid2"], "count": 2 },
        "2": { "chapter": "2", "id": "uuid", "others": [], "count": 1 }
      }
    }
  }
}
```
- `chapters` keys = unique chapter numbers
- `count` = number of group uploads for that chapter
- `others` = additional chapter UUIDs from other groups

### Auth token flow (personal API client)
```
POST /auth/login { username, password, client_id, client_secret, grant_type: "password" }
→ { access_token, refresh_token, expires_in }
POST /auth/refresh { refresh_token, client_id, client_secret, grant_type: "refresh_token" }
```

## Important Gotchas

### Multi-Group Chapters
- `feed?limit=0` total counts ALL uploads (chapter 5 from 3 groups = 3 in total)
- `aggregate` counts unique chapter numbers (chapter 5 from 3 groups = 1)
- CBZ files on disk may include multiple groups per chapter number

### Rate Limits
- 5 requests/second per IP
- HTTP 429 → `Retry-After` header (seconds)
- gallery-dl crashes on 429: `ValueError: Either 'seconds' or 'until' is required`

### Exit Codes (gallery-dl)
| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error (network, parse) |
| 2 | No images extracted |
| 4 | Chapter unavailable (paid/deleted/DMCA) |

### Paid/Unavailable Chapters
- J-Novel Club, other licensed chapters → redirect to paid service
- gallery-dl returns exit code 4
- Always fail, never downloadable
- Auto-blacklisted after 3 attempts (`.chapter-failures.json`)

### Pagination
- Max `limit=500` per request
- Use `offset` for pagination: `offset=0`, `offset=500`, etc.
- `total` in response = total matching entries

### Language Filter
- `translatedLanguage[]=en` for English
- Config key `default_language` in plugin settings
- Without filter: returns ALL languages

### Feed Query Parameters
- `translatedLanguage[]=en` — filter language
- `limit=100` — results per page (max 500)
- `offset=0` — pagination offset
- `order[chapter]=asc` — sort by chapter number
- `order[publishAt]=desc` — sort by publish date
- `publishAtSince=2024-01-01T00:00:00` — filter by publish date
- `includeFuturePublishAt=0` — exclude future chapters
- `includeEmptyPages=0` — exclude chapters with 0 pages

## Debug Commands

```bash
# Chapter count (includes multi-group)
curl -s "https://api.mangadex.org/manga/{id}/feed?translatedLanguage[]=en&limit=0" | jq '.total'

# Unique chapter count
curl -s "https://api.mangadex.org/manga/{id}/aggregate?translatedLanguage[]=en" | jq '[.volumes[].chapters | keys[]] | length'

# Chapter details
curl -s "https://api.mangadex.org/chapter/{chapterId}" | jq '.data.attributes'

# Find multi-group chapters
curl -s "https://api.mangadex.org/manga/{id}/aggregate?translatedLanguage[]=en" | jq '.volumes[].chapters | to_entries[] | select(.value.count > 1)'

# Manga search by title
curl -s "https://api.mangadex.org/manga?title=Beast+Tamer&limit=5" | jq '.data[] | {id, title: .attributes.title}'

# Followed manga feed (authenticated)
curl -s -H "Authorization: Bearer {token}" "https://api.mangadex.org/user/follows/manga/feed?limit=100&order[publishAt]=desc"

# Check auth
curl -s -H "Authorization: Bearer {token}" "https://api.mangadex.org/auth/check"
```
