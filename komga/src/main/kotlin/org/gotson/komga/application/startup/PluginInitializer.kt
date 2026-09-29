package org.gotson.komga.application.startup

import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.infrastructure.scrobbler.PluginVersions
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
          author = "Kasch_X",
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
                  "description": "Shared MangaDex credential — used by gallery-dl Downloader, MangaDex Subscription Sync, and Manga Scrobbler unless they override it."
                },
                "mangadex_password": {
                  "type": "string",
                  "title": "MangaDex Password",
                  "format": "password",
                  "description": "Shared MangaDex credential — see Username field."
                },
                "mangadex_client_id": {
                  "type": "string",
                  "title": "MangaDex Client ID",
                  "description": "MangaDex personal API client ID (mangadex.org/settings → API Clients). Required only for the OAuth2 flows used by Subscription Sync and Manga Scrobbler — gallery-dl itself doesn't need it. Stored here so all plugins share one place."
                },
                "mangadex_client_secret": {
                  "type": "string",
                  "title": "MangaDex Client Secret",
                  "format": "password",
                  "description": "MangaDex personal API client secret. See Client ID field."
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
                },
                "chapter_naming": {
                  "type": "string",
                  "title": "Chapter Naming Template (gallery-dl directory)",
                  "description": "gallery-dl directory template for chapter folders/CBZ names. Leave blank to keep the per-site default. Common fields: {chapter}, {chapter_minor}, {volume}, {title}, {group}, {lang}. Examples: 'c{chapter:>03}{chapter_minor} [{group:J, }]' or 'Chapter {chapter:>03}{chapter_minor}'. Supported prefixes for chapter detection: 'c<num>', 'ch. <num>', 'Chapter <num>'.",
                  "default": ""
                },
                "flaresolverr_url": {
                  "type": "string",
                  "title": "FlareSolverr URL",
                  "description": "Optional FlareSolverr endpoint (e.g. http://192.168.1.10:8191/v1) used to bypass Cloudflare challenges for sites like mgeko.cc, mangaclash.com, deatte5.com. Cookies + User-Agent are cached for 20 minutes per host. Leave blank to disable.",
                  "default": ""
                }
              },
              "required": ["mangadex_username", "mangadex_password"]
            }
            """.trimIndent(),
          dependencies = null,
        ),
        Plugin(
          id = "mangadex-metadata",
          name = "MangaDex",
          version = "1.0.0",
          author = "Kasch_X",
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
          id = "manga-scrobbler",
          name = "Manga Scrobbler (AniList / MyAnimeList / Kitsu / MangaDex)",
          version = PluginVersions.MANGA_SCROBBLER,
          author = "Jack O'Hagan",
          description = "Syncs read progress to AniList, MyAnimeList, Kitsu, and/or MangaDex when a book is marked completed. Supports auto-refresh for expiring MAL/Kitsu OAuth2 tokens. Resolves tracker IDs from SeriesMetadata links (anilist.co / myanimelist.net / kitsu.app / mangadex.org) or via manual JSON mappings.",
          enabled = false,
          pluginType = PluginType.SCROBBLER,
          entryPoint = "org.gotson.komga.infrastructure.scrobbler.MangaScrobblerPlugin",
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
                  "enum": ["anilist", "mal", "kitsu", "mangadex", "both", "both_kitsu", "all"]
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
                  "description": "OAuth2 access token. MAL tokens expire ~monthly — auto-refresh is supported when mal_client_id/mal_client_secret/mal_refresh_token are configured."
                },
                "mal_refresh_token": {
                  "type": "string",
                  "title": "MyAnimeList Refresh Token",
                  "format": "password",
                  "description": "OAuth2 refresh token returned during initial authorization."
                },
                "mal_client_id": {
                  "type": "string",
                  "title": "MyAnimeList Client ID",
                  "description": "MAL OAuth2 app client ID (required for token refresh)."
                },
                "mal_client_secret": {
                  "type": "string",
                  "title": "MyAnimeList Client Secret",
                  "format": "password",
                  "description": "MAL OAuth2 app client secret (required for token refresh)."
                },
                "kitsu_token": {
                  "type": "string",
                  "title": "Kitsu Access Token",
                  "format": "password",
                  "description": "OAuth2 bearer token from kitsu.app/api/oauth/token. Auto-refresh is supported when kitsu_refresh_token/kitsu_client_id/kitsu_client_secret are configured."
                },
                "kitsu_refresh_token": {
                  "type": "string",
                  "title": "Kitsu Refresh Token",
                  "format": "password",
                  "description": "OAuth2 refresh token returned during initial authorization."
                },
                "kitsu_client_id": {
                  "type": "string",
                  "title": "Kitsu Client ID",
                  "description": "Kitsu OAuth2 app client ID (required for token refresh)."
                },
                "kitsu_client_secret": {
                  "type": "string",
                  "title": "Kitsu Client Secret",
                  "format": "password",
                  "description": "Kitsu OAuth2 app client secret (required for token refresh)."
                },
                "mangadex_username": {
                  "type": "string",
                  "title": "MangaDex Username",
                  "description": "Optional override — leave blank to use the gallery-dl Downloader's `mangadex_username` (then MangaDex Subscription Sync's `username` as second fallback)."
                },
                "mangadex_password": {
                  "type": "string",
                  "title": "MangaDex Password",
                  "format": "password",
                  "description": "Optional override — leave blank to use gallery-dl Downloader / Subscription Sync as fallback."
                },
                "mangadex_client_id": {
                  "type": "string",
                  "title": "MangaDex Client ID",
                  "description": "Optional override — leave blank to use gallery-dl Downloader / Subscription Sync as fallback."
                },
                "mangadex_client_secret": {
                  "type": "string",
                  "title": "MangaDex Client Secret",
                  "format": "password",
                  "description": "Optional override — leave blank to use gallery-dl Downloader / Subscription Sync as fallback."
                },
                "mangadex_max_content_rating": {
                  "type": "string",
                  "title": "MangaDex: max content rating to sync",
                  "default": "pornographic",
                  "enum": ["safe", "suggestive", "erotica", "pornographic"],
                  "description": "Only push read progress to MangaDex for manga up to this content rating (inclusive). Manga rated higher are skipped. Ordering: safe < suggestive < erotica < pornographic."
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
                },
                "exclude_library_ids": {
                  "type": "string",
                  "title": "Exclude libraries (CSV of library IDs)",
                  "description": "Optional. Komga library IDs to never scrobble (e.g. your Western comics library when this plugin tracks manga). Find IDs in the library URL or API."
                }
              },
              "required": []
            }
            """.trimIndent(),
          dependencies = null,
        ),
        Plugin(
          id = "comic-scrobbler",
          name = "Comic Scrobbler (Metron)",
          version = PluginVersions.COMIC_SCROBBLER,
          author = "Jack O'Hagan",
          description = "Syncs comic read progress to Metron (metron.cloud) when a book is marked completed. Resolves issue IDs from series links (metron.cloud/issue/... or metron.cloud/series/... from Metron Metadata Provider), auto-search by series name, or via manual JSON mappings.",
          enabled = false,
          pluginType = PluginType.SCROBBLER,
          entryPoint = "org.gotson.komga.infrastructure.comicscrobbler.ComicScrobblerPlugin",
          sourceUrl = "https://metron.cloud",
          installedDate = LocalDateTime.now(),
          lastUpdated = LocalDateTime.now(),
          configSchema =
            """
            {
              "type": "object",
              "properties": {
                "metron_username": {
                  "type": "string",
                  "title": "Metron Username",
                  "description": "Your Metron account username (metron.cloud)"
                },
                "metron_password": {
                  "type": "string",
                  "title": "Metron Password",
                  "format": "password",
                  "description": "Your Metron account password"
                },

                "auto_detect_links": {
                  "type": "string",
                  "title": "Auto-detect issue IDs from series links",
                  "default": "true",
                  "enum": ["false", "true"],
                  "description": "If true, extract issue/series IDs from metron.cloud URLs in SeriesMetadata.links."
                },
                "mappings": {
                  "type": "string",
                  "title": "Manual issue mappings (JSON)",
                  "description": "Fallback when auto-detect/search fails. Example: {\"Amazing Spider-Man\":{\"metron_issue_id\":12345}}"
                },
                "sync_user_id": {
                  "type": "string",
                  "title": "Restrict to user ID",
                  "description": "Optional. Only syncs reads from this Komga user."
                },
                "exclude_library_ids": {
                  "type": "string",
                  "title": "Exclude libraries (CSV of library IDs)",
                  "description": "Optional. Library IDs to never send to Metron (e.g. manga libraries when this plugin is for Western comics)."
                }
              },
              "required": ["metron_username", "metron_password"]
            }
            """.trimIndent(),
          dependencies = null,
        ),
        Plugin(
          id = "auto-metadata",
          name = "Auto Metadata Match",
          version = PluginVersions.AUTO_METADATA,
          author = "Jack O'Hagan",
          description = "Automatically match new series against the configured metadata providers (AniList, MangaDex, Kitsu) on scan/import. Komf-style: walks a priority list, scores candidates by normalized-title similarity, and applies the first match above the score threshold. Writes multi-source tracker_links when secondary scores pass. Existing series can be bulk-matched via POST /api/v1/automatch/libraries/{id}.",
          enabled = true,
          pluginType = PluginType.PROCESSOR,
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
                  "description": "Token-set Jaccard score for choosing the winning metadata provider and writing series.json. 1.0 = normalized titles are exactly equal. 0.85 is a good default."
                },
                "min_score_tracker_links": {
                  "type": "string",
                  "title": "Minimum score for extra tracker links (0.0–min_score)",
                  "description": "Optional. Each enabled provider whose best search result scores at or above this value gets a URL in series.json tracker_links (in addition to web_url from the winner). If unset, defaults to min_score minus 0.08, capped at min_score. Lower = more extra links (higher false-positive risk)."
                },
                "exclude_library_ids": {
                  "type": "string",
                  "title": "Exclude libraries (CSV of library IDs)",
                  "description": "Optional. Komga libraries where auto-match must not run (new-series enqueue, bulk queue, and per-series apply). Does not affect other metadata providers."
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
          author = "Kasch_X",
          description = "Watches your MangaDex follow feed for new chapters and auto-downloads them. Requires a MangaDex personal API client (register at mangadex.org/settings).",
          enabled = false,
          pluginType = PluginType.PROCESSOR,
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
                  "description": "Optional override — leave blank to use the gallery-dl Downloader's `mangadex_client_id`."
                },
                "client_secret": {
                  "type": "string",
                  "title": "Client Secret",
                  "format": "password",
                  "description": "Optional override — leave blank to use the gallery-dl Downloader's `mangadex_client_secret`."
                },
                "username": {
                  "type": "string",
                  "title": "MangaDex Username",
                  "description": "Optional override — leave blank to use the gallery-dl Downloader's `mangadex_username`."
                },
                "password": {
                  "type": "string",
                  "title": "MangaDex Password",
                  "format": "password",
                  "description": "Optional override — leave blank to use the gallery-dl Downloader's `mangadex_password`."
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
              "required": []
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
        } else {
          // keep built-in metadata in sync with code; preserve the user's enabled flag and install date
          val synced =
            existing.copy(
              name = plugin.name,
              version = plugin.version,
              author = plugin.author,
              description = plugin.description,
              pluginType = plugin.pluginType,
              entryPoint = plugin.entryPoint,
              sourceUrl = plugin.sourceUrl,
              configSchema = plugin.configSchema,
            )
          if (synced != existing) {
            pluginRepository.update(synced)
            logger.info("Synced default plugin metadata: ${plugin.name}")
          }
        }
      } catch (e: Exception) {
        logger.error("Failed to install default plugin: ${plugin.name}", e)
      }
    }

    logger.info("Default plugins initialization complete")
  }
}
