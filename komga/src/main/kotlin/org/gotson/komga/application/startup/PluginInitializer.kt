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
      )

    // Insert missing plugins only (upsert behavior)
    defaultPlugins.forEach { plugin ->
      try {
        if (pluginRepository.findByIdOrNull(plugin.id) == null) {
          pluginRepository.insert(plugin)
          logger.info("Installed default plugin: ${plugin.name}")
        } else {
          logger.debug("Plugin already exists: ${plugin.name}")
        }
      } catch (e: Exception) {
        logger.error("Failed to install default plugin: ${plugin.name}", e)
      }
    }

    logger.info("Default plugins initialization complete")
  }
}
