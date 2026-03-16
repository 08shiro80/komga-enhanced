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
                  "enum": ["en", "ja", "de", "fr", "es", "it", "pt", "ru", "zh", "ko"]
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
          id = "mangadex-subscription",
          name = "MangaDex Subscription Sync",
          version = "1.0.0",
          author = "Komga Team",
          description = "Watches your MangaDex subscription feed for new chapters and auto-downloads them via CustomList. Requires a MangaDex personal API client (register at mangadex.org/settings).",
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
                "language": {
                  "type": "string",
                  "title": "Language",
                  "default": "en",
                  "description": "Preferred chapter language (ISO 639-1)",
                  "enum": ["en", "ja", "de", "fr", "es", "it", "pt", "ru", "zh", "ko"]
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
