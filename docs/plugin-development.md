# Plugin Development Guide

This guide explains how to create plugins for Komga Enhanced.

---

## Plugin Architecture

Plugins are registered in the database and configured via the **Plugin Manager UI** (Settings → Plugins). Each plugin has:

- **ID** — unique identifier (e.g., `my-custom-plugin`)
- **Type** — determines what the plugin does
- **Config Schema** — JSON Schema that generates the settings form
- **Entry Point** — fully qualified Kotlin/Java class name

### Plugin Types

| Type | Purpose | Example |
|------|---------|---------|
| `DOWNLOAD` | Download manga from external sources | gallery-dl-downloader, mangadex-subscription |
| `METADATA` | Fetch metadata from external APIs | mangadex-metadata, anilist-metadata |
| `TASK` | Custom scheduled tasks | — |
| `PROCESSOR` | Content processing | — |
| `NOTIFIER` | Notifications | — |
| `ANALYZER` | Content analysis | — |

---

## Step 1: Define the Plugin

Register your plugin in `PluginInitializer.kt`:

```kotlin
// application/startup/PluginInitializer.kt
Plugin(
  id = "my-downloader",
  name = "My Custom Downloader",
  version = "1.0.0",
  author = "Your Name",
  description = "Downloads manga from example.com",
  enabled = false,
  pluginType = PluginType.DOWNLOAD,
  entryPoint = "org.gotson.komga.infrastructure.download.MyDownloader",
  sourceUrl = "https://example.com",
  installedDate = LocalDateTime.now(),
  lastUpdated = LocalDateTime.now(),
  configSchema = """
    {
      "type": "object",
      "properties": {
        "api_key": {
          "type": "string",
          "title": "API Key",
          "description": "Your example.com API key"
        },
        "max_concurrent": {
          "type": "integer",
          "title": "Max Concurrent Downloads",
          "default": 3
        }
      },
      "required": ["api_key"]
    }
  """.trimIndent(),
  dependencies = null,
)
```

The `configSchema` is **auto-updated** — if you change it, `PluginInitializer` updates the database on next startup.

---

## Step 2: Config Schema (JSON Schema)

The config schema generates the settings form in the Plugin Manager UI.

### Supported Field Types

```json
{
  "type": "object",
  "properties": {
    "username": {
      "type": "string",
      "title": "Username"
    },
    "password": {
      "type": "string",
      "title": "Password",
      "format": "password"
    },
    "language": {
      "type": "string",
      "title": "Language",
      "default": "en",
      "enum": ["en", "de", "fr", "it", "ja"]
    },
    "interval": {
      "type": "integer",
      "title": "Check Interval (minutes)",
      "default": 30,
      "description": "How often to check for updates"
    }
  },
  "required": ["username", "password"]
}
```

| Schema Property | UI Element |
|----------------|------------|
| `"type": "string"` | Text field |
| `"format": "password"` | Password field (masked) |
| `"enum": [...]` | Dropdown select |
| `"type": "integer"` | Number field |
| `"default": value` | Pre-filled default |
| `"description": "..."` | Hint text below field |
| `"required": [...]` | Required field validation |

### Shared Settings

If your plugin needs the same language as other plugins, read from the gallery-dl-downloader config instead of adding your own:

```kotlin
val language =
  pluginConfigRepository
    .findByPluginIdAndKey("gallery-dl-downloader", "default_language")
    ?.configValue ?: "en"
```

---

## Step 3: Implement the Plugin Class

### Reading Config

```kotlin
@Component
class MyDownloader(
  private val pluginConfigRepository: PluginConfigRepository,
  private val pluginLogRepository: PluginLogRepository,
) {
  private val pluginId = "my-downloader"

  // Read all config as a Map
  private fun loadConfig(): Map<String, String?> =
    pluginConfigRepository
      .findByPluginId(pluginId)
      .associate { it.configKey to it.configValue }

  // Read a single config value
  private fun getApiKey(): String? =
    pluginConfigRepository
      .findByPluginIdAndKey(pluginId, "api_key")
      ?.configValue
}
```

### Logging to Plugin Log

Plugin logs are visible in the Plugin Manager UI (Settings → Plugins → Logs tab):

```kotlin
private fun logToDatabase(
  level: LogLevel,
  message: String,
) {
  pluginLogRepository.insert(
    PluginLog(
      id = UUID.randomUUID().toString(),
      pluginId = pluginId,
      logLevel = level,
      message = message,
    ),
  )
}

// Usage
logToDatabase(LogLevel.INFO, "Download started for manga XYZ")
logToDatabase(LogLevel.ERROR, "API returned 403: ${response.body()}")
```

Log levels: `DEBUG`, `INFO`, `WARN`, `ERROR`

### Checking if Plugin is Enabled

```kotlin
private fun isEnabled(): Boolean =
  try {
    pluginRepository.findByIdOrNull(pluginId)?.enabled == true
  } catch (_: Exception) {
    false
  }
```

---

## Step 4: Plugin Interfaces (Optional)

Base interfaces are defined in `infrastructure/plugin/PluginApi.kt`:

### MetadataProviderPlugin

For plugins that fetch metadata from external sources:

```kotlin
interface MetadataProviderPlugin : KomgaPlugin {
  fun searchSeries(query: String, language: String?): List<SeriesSearchResult>
  fun getSeriesMetadata(seriesId: String, sourceUrl: String): SeriesMetadataResult?
  fun getBookMetadata(bookId: String, sourceUrl: String): BookMetadataResult?
  fun getSupportedLanguages(): List<String>
  fun canHandle(url: String): Boolean
}
```

### DownloadProviderPlugin

For plugins that download content:

```kotlin
interface DownloadProviderPlugin : KomgaPlugin {
  fun canHandleUrl(url: String): Boolean
  fun getAvailableChapters(sourceUrl: String): List<ChapterInfo>
  fun startDownload(request: DownloadRequest): DownloadQueue
  fun cancelDownload(queueId: String): Boolean
  fun getProgress(queueId: String): DownloadProgress
  fun checkForUpdates(sourceUrl: String, lastKnownChapter: String?): UpdateCheckResult
}
```

---

## Step 5: Restart on Config Change

If your plugin runs a background scheduler, register it for restart when config is saved. In `PluginController.kt`:

```kotlin
// In updatePluginConfig()
if (id == "my-downloader") {
  myDownloader.restart()
}
```

Implement `restart()` in your plugin:

```kotlin
fun restart() {
  stopScheduler()
  // Reset state
  startIfEnabled()  // Reloads config
}
```

---

## REST API

All plugin endpoints require `ADMIN` role.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/plugins` | List all plugins |
| GET | `/api/v1/plugins/{id}` | Get plugin details |
| PATCH | `/api/v1/plugins/{id}` | Enable/disable (`{"enabled": true}`) |
| DELETE | `/api/v1/plugins/{id}` | Uninstall plugin |
| GET | `/api/v1/plugins/{id}/config` | Get config as `Map<String, String>` |
| POST | `/api/v1/plugins/{id}/config` | Save config (replaces all keys) |
| GET | `/api/v1/plugins/{id}/logs` | Get logs (paginated) |
| DELETE | `/api/v1/plugins/{id}/logs` | Clear logs |

---

## Database Tables

| Table | Purpose |
|-------|---------|
| `plugin` | Plugin registration (id, name, type, schema, enabled) |
| `plugin_config` | Key-value config per plugin |
| `plugin_log` | Plugin log entries with level and timestamp |
| `plugin_permission` | Permission grants (future use) |

---

## Example: Minimal Metadata Plugin

```kotlin
@Component
class MyMetadataProvider(
  private val pluginConfigRepository: PluginConfigRepository,
) {
  private val pluginId = "my-metadata"

  fun searchSeries(query: String): List<SeriesSearchResult> {
    val apiKey =
      pluginConfigRepository
        .findByPluginIdAndKey(pluginId, "api_key")
        ?.configValue ?: return emptyList()

    val response = httpClient.send(
      HttpRequest
        .newBuilder()
        .uri(URI.create("https://api.example.com/search?q=$query&key=$apiKey"))
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )

    return parseSearchResults(response.body())
  }
}
```

Register in `PluginInitializer.kt`:

```kotlin
Plugin(
  id = "my-metadata",
  name = "Example Metadata",
  version = "1.0.0",
  author = "Your Name",
  description = "Fetches metadata from example.com",
  enabled = false,
  pluginType = PluginType.METADATA,
  entryPoint = "org.gotson.komga.infrastructure.metadata.MyMetadataProvider",
  sourceUrl = "https://example.com",
  installedDate = LocalDateTime.now(),
  lastUpdated = LocalDateTime.now(),
  configSchema = """
    {
      "type": "object",
      "properties": {
        "api_key": {
          "type": "string",
          "title": "API Key",
          "description": "Get your key at example.com/settings"
        }
      },
      "required": ["api_key"]
    }
  """.trimIndent(),
  dependencies = null,
)
```

---

## Tips

- **Config is key-value** — all values are stored as strings. Parse integers/booleans yourself.
- **Config save replaces all** — the API deletes all existing keys and inserts new ones on save.
- **Schema updates are automatic** — change `configSchema` in `PluginInitializer`, restart Komga.
- **Sort enum values alphabetically** — prevents user confusion with similar codes (e.g., `it` vs `lt`).
- **Use `findByPluginIdAndKey()`** for reading single values, `findByPluginId()` for loading all config at once.
- **Plugin logs are separate from application logs** — use `pluginLogRepository` for user-visible logs in the UI.
