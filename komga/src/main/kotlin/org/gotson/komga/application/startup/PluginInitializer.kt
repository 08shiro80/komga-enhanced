package org.gotson.komga.application.startup

import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.persistence.PluginRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime

private val logger = LoggerFactory.getLogger(PluginInitializer::class.java)

@Component
class PluginInitializer(
  private val pluginRepository: PluginRepository,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun initializeDefaultPlugins() {
    logger.info("Checking for missing default plugins")

    val defaultPlugins =
      listOf(
        Plugin(
          id = "gallery-dl-downloader",
          name = "gallery-dl Downloader",
          version = "1.0.0",
          author = "Komga Team",
          description = "Downloads manga from 1000+ websites using gallery-dl integration. Requires gallery-dl to be installed (pip install gallery-dl). Supports automatic chapter tracking via --download-archive and ComicInfo.xml generation.",
          enabled = true,
          pluginType = PluginType.DOWNLOAD,
          entryPoint = "org.gotson.komga.infrastructure.download.GalleryDlWrapper",
          sourceUrl = "https://github.com/mikf/gallery-dl",
          installedDate = LocalDateTime.now(),
          lastUpdated = LocalDateTime.now(),
          configSchema =
            """
            {
              "type": "object",
              "properties": {
                "mangadex_username": {
                  "type": "string",
                  "title": "MangaDex Username",
                  "description": "Your MangaDex account username for API authentication"
                },
                "mangadex_password": {
                  "type": "string",
                  "title": "MangaDex Password",
                  "format": "password",
                  "description": "Your MangaDex account password for API authentication"
                },
                "default_language": {
                  "type": "string",
                  "title": "Default Language",
                  "description": "Preferred language for downloads (ISO 639-1 code)",
                  "default": "en",
                  "enum": ["ar", "bg", "ca", "cs", "da", "de", "el", "en", "es", "es-la", "fi", "fr", "hi", "hr", "hu", "id", "it", "ja", "ko", "lt", "ms", "nl", "no", "pl", "pt", "pt-br", "ro", "ru", "sv", "th", "tl", "tr", "uk", "vi", "zh", "zh-hk"]
                },
                "folder_naming": {
                  "type": "string",
                  "title": "Folder Naming for New Manga",
                  "description": "How new manga folders are named on first download. 'uuid' uses the MangaDex UUID (e.g. 0c6fe779-...), 'title' uses the manga title (e.g. Roman Club). Existing folders are never renamed.",
                  "default": "uuid",
                  "enum": ["uuid", "title"]
                }
              },
              "required": ["mangadex_username", "mangadex_password"]
            }
            """.trimIndent(),
          dependencies = null,
        ),
        Plugin(
          id = "mangadex-metadata",
          name = "MangaDex Metadata Provider",
          version = "1.0.0",
          author = "Komga Team",
          description = "Fetches manga metadata from MangaDex API v5",
          enabled = true,
          pluginType = PluginType.METADATA,
          entryPoint = "org.gotson.komga.infrastructure.metadata.mangadex.MangaDexMetadataPlugin",
          sourceUrl = "https://api.mangadex.org",
          installedDate = LocalDateTime.now(),
          lastUpdated = LocalDateTime.now(),
          configSchema = null,
          dependencies = null,
        ),
        Plugin(
          id = "anilist-metadata",
          name = "AniList Metadata Provider",
          version = "1.0.0",
          author = "Komga Team",
          description = "Fetches manga and anime metadata from AniList GraphQL API",
          enabled = true,
          pluginType = PluginType.METADATA,
          entryPoint = "org.gotson.komga.infrastructure.metadata.anilist.AniListMetadataPlugin",
          sourceUrl = "https://anilist.co",
          installedDate = LocalDateTime.now(),
          lastUpdated = LocalDateTime.now(),
          configSchema = null,
          dependencies = null,
        ),
        Plugin(
          id = "kitsu-metadata",
          name = "Kitsu Metadata Provider",
          version = "1.0.0",
          author = "Komga Team",
          description = "Fetches manga metadata from the Kitsu API (kitsu.app). Provides series-level metadata including titles, synopsis, genres, authors, age rating, and alternative titles.",
          enabled = true,
          pluginType = PluginType.METADATA,
          entryPoint = "org.gotson.komga.infrastructure.metadata.kitsu.KitsuMetadataPlugin",
          sourceUrl = "https://kitsu.app",
          installedDate = LocalDateTime.now(),
          lastUpdated = LocalDateTime.now(),
          configSchema = null,
          dependencies = null,
        ),
        Plugin(
          id = "scrobbler",
          name = "Scrobbler (AniList / MyAnimeList)",
          version = "1.0.0",
          author = "Komga Team",
          description = "Syncs read progress to AniList and/or MyAnimeList when a book is marked completed. Resolves tracker IDs from SeriesMetadata links (anilist.co / myanimelist.net) or via manual JSON mappings.",
          enabled = false,
          pluginType = PluginType.NOTIFIER,
          entryPoint = "org.gotson.komga.infrastructure.scrobbler.ScrobblerPlugin",
          sourceUrl = null,
          installedDate = LocalDateTime.now(),
          lastUpdated = LocalDateTime.now(),
          configSchema =
            """
            {
              "type": "object",
              "properties": {
                "tracker": {
                  "type": "string",
                  "title": "Trackers to update",
                  "default": "anilist",
                  "enum": ["anilist", "both", "mal"]
                },
                "anilist_token": {
                  "type": "string",
                  "title": "AniList Access Token",
                  "format": "password",
                  "description": "Personal access token from anilist.co/api/v2/oauth/pin (Implicit Grant flow)."
                },
                "mal_access_token": {
                  "type": "string",
                  "title": "MyAnimeList Access Token",
                  "format": "password",
                  "description": "OAuth2 access token. NOTE: MAL tokens expire after ~1 month — refresh handling is not implemented in v1."
                },
                "auto_detect_links": {
                  "type": "string",
                  "title": "Auto-detect tracker IDs from series links",
                  "default": "true",
                  "enum": ["false", "true"],
                  "description": "If true, extract IDs from anilist.co / myanimelist.net URLs in SeriesMetadata.links."
                },
                "mappings": {
                  "type": "string",
                  "title": "Manual series mappings (JSON)",
                  "description": "Override auto-detection. Example: {\"Berserk\":{\"anilist_id\":30002,\"mal_id\":2}}"
                },
                "sync_user_id": {
                  "type": "string",
                  "title": "Restrict to user ID",
                  "description": "Optional. If set, only progress changes from this Komga user ID are synced."
                }
              },
              "required": []
            }
            """.trimIndent(),
          dependencies = null,
        ),
        Plugin(
          id = "auto-metadata",
          name = "Auto Metadata Match",
          version = "1.0.0",
          author = "Komga Team",
          description = "Automatically match new series against the configured metadata providers (AniList, MangaDex, Kitsu) on scan/import. Komf-style: walks a priority list, scores candidates by normalized-title similarity, and applies the first match above the score threshold. Existing series can be bulk-matched via POST /api/v1/automatch/libraries/{id}.",
          enabled = true,
          pluginType = PluginType.METADATA,
          entryPoint = "org.gotson.komga.infrastructure.automatch.AutoMetadataApplier",
          sourceUrl = null,
          installedDate = LocalDateTime.now(),
          lastUpdated = LocalDateTime.now(),
          configSchema =
            """
            {
              "type": "object",
              "properties": {
                "enabled": {
                  "type": "string",
                  "title": "Auto-match new series",
                  "default": "false",
                  "enum": ["false", "true"],
                  "description": "If true, queue a background auto-match task whenever a new series is added (initial scan or import). Existing series are not touched unless you call the bulk endpoint."
                },
                "provider_priority": {
                  "type": "string",
                  "title": "Provider priority (CSV)",
                  "default": "anilist,mangadex,kitsu",
                  "description": "Comma-separated provider tags to try in order. The first provider whose top result scores above min_score wins. Disabled plugins are skipped."
                },
                "min_score": {
                  "type": "string",
                  "title": "Minimum match score (0.0-1.0)",
                  "default": "0.85",
                  "description": "Token-set Jaccard score. 1.0 = normalized titles are exactly equal. 0.85 is a good default; lower if your titles include extra noise that the normalizer cannot strip; raise if you see false positives."
                }
              },
              "required": []
            }
            """.trimIndent(),
          dependencies = null,
        ),
        Plugin(
          id = "mangadex-subscription",
          name = "MangaDex Subscription Sync",
          version = "1.0.0",
          author = "Komga Team",
          description = "Watches your MangaDex follow feed for new chapters and auto-downloads them. Requires a MangaDex personal API client (register at mangadex.org/settings).",
          enabled = false,
          pluginType = PluginType.DOWNLOAD,
          entryPoint = "org.gotson.komga.infrastructure.download.MangaDexSubscriptionSyncer",
          sourceUrl = "https://api.mangadex.org",
          installedDate = LocalDateTime.now(),
          lastUpdated = LocalDateTime.now(),
          configSchema =
            """
            {
              "type": "object",
              "properties": {
                "client_id": {
                  "type": "string",
                  "title": "Client ID",
                  "description": "MangaDex personal API client ID"
                },
                "client_secret": {
                  "type": "string",
                  "title": "Client Secret",
                  "format": "password",
                  "description": "MangaDex personal API client secret"
                },
                "username": {
                  "type": "string",
                  "title": "MangaDex Username"
                },
                "password": {
                  "type": "string",
                  "title": "MangaDex Password",
                  "format": "password"
                },
                "sync_interval_minutes": {
                  "type": "integer",
                  "title": "Check Interval (minutes)",
                  "default": 30,
                  "description": "How often to check the subscription feed for new chapters"
                },
                "target_library": {
                  "type": "string",
                  "title": "Target Library",
                  "description": "Library where new manga will be downloaded. If empty or not found, uses the first library.",
                  "dynamicEnum": "libraries"
                }
              },
              "required": ["client_id", "client_secret", "username", "password"]
            }
            """.trimIndent(),
          dependencies = null,
        ),
      )

    defaultPlugins.forEach { plugin ->
      try {
        val existing = pluginRepository.findByIdOrNull(plugin.id)
        if (existing == null) {
          pluginRepository.insert(plugin)
          logger.info("Installed default plugin: ${plugin.name}")
        } else if (existing.configSchema != plugin.configSchema) {
          pluginRepository.update(existing.copy(configSchema = plugin.configSchema))
          logger.info("Updated configSchema for plugin: ${plugin.name}")
        }
      } catch (e: Exception) {
        logger.error("Failed to install default plugin: ${plugin.name}", e)
      }
    }

    logger.info("Default plugins initialization complete")
  }
}
