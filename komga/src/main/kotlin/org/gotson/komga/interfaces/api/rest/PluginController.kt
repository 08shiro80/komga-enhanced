package org.gotson.komga.interfaces.api.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.MarkSelectedPreference
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.ThumbnailSeries
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.OnlineMetadataProvider
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.infrastructure.download.MangaDexSubscriptionSyncer
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.gotson.komga.infrastructure.metadata.anilist.AniListMetadataPlugin
import org.gotson.komga.infrastructure.metadata.kitsu.KitsuMetadataPlugin
import org.gotson.komga.infrastructure.metadata.mangadex.MangaDexMetadataPlugin
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration.TagNames
import org.gotson.komga.interfaces.api.rest.dto.PluginDto
import org.gotson.komga.interfaces.api.rest.dto.PluginUpdateDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/v1/plugins", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class PluginController(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: org.gotson.komga.domain.persistence.PluginConfigRepository,
  private val pluginLogRepository: org.gotson.komga.domain.persistence.PluginLogRepository,
  private val mangaDexMetadataPlugin: MangaDexMetadataPlugin,
  private val aniListMetadataPlugin: AniListMetadataPlugin,
  private val kitsuMetadataPlugin: KitsuMetadataPlugin,
  private val mangaDexSubscriptionSyncer: MangaDexSubscriptionSyncer,
  private val seriesRepository: SeriesRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val contentDetector: ContentDetector,
  private val imageAnalyzer: ImageAnalyzer,
  private val objectMapper: ObjectMapper,
) {
  private val coverClient = RestClient.create()

  private val allowedCoverHosts = listOf("mangadex.org", "anilist.co", "kitsu.io", "media.kitsu.app")

  private fun getMetadataProvider(pluginId: String): OnlineMetadataProvider? =
    when (pluginId) {
      "mangadex-metadata" -> mangaDexMetadataPlugin
      "anilist-metadata" -> aniListMetadataPlugin
      "kitsu-metadata" -> kitsuMetadataPlugin
      else -> null
    }

  @GetMapping
  @Operation(summary = "List all plugins", tags = [TagNames.PLUGINS])
  fun getAllPlugins(): List<PluginDto> {
    val plugins = pluginRepository.findAll()
    logger.info { "Returning ${plugins.size} plugins" }
    plugins.forEach { p ->
      logger.info { "Plugin: ${p.id}, type=${p.pluginType}, enabled=${p.enabled}" }
    }
    return plugins.map { it.toDto() }.sortedBy { it.name }
  }

  @GetMapping("{id}")
  @Operation(summary = "Get plugin by ID", tags = [TagNames.PLUGINS])
  fun getPluginById(
    @PathVariable id: String,
  ): PluginDto =
    pluginRepository.findByIdOrNull(id)?.toDto()
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found: $id")

  @PatchMapping("{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Update plugin (enable/disable)", tags = [TagNames.PLUGINS])
  fun updatePlugin(
    @PathVariable id: String,
    @Valid @RequestBody update: PluginUpdateDto,
  ) {
    logger.info { "Updating plugin $id: enabled=${update.enabled}" }
    val plugin =
      pluginRepository.findByIdOrNull(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found: $id")

    pluginRepository.update(
      plugin.copy(
        enabled = update.enabled,
        lastUpdated = LocalDateTime.now(),
      ),
    )
    logger.info { "Plugin $id updated successfully" }

    if (id == "mangadex-subscription") {
      mangaDexSubscriptionSyncer.restart()
    }
  }

  @DeleteMapping("{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Uninstall plugin", tags = [TagNames.PLUGINS])
  fun deletePlugin(
    @PathVariable id: String,
  ) {
    pluginRepository.delete(id)
  }

  @GetMapping("{id}/search")
  @Operation(summary = "Search for manga using metadata plugin", tags = [TagNames.PLUGINS])
  fun searchMetadata(
    @PathVariable id: String,
    @RequestParam query: String,
  ): List<org.gotson.komga.domain.service.MetadataSearchResult> {
    val plugin =
      pluginRepository.findByIdOrNull(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found: $id")

    if (!plugin.enabled) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plugin is not enabled: $id")
    }

    val provider =
      getMetadataProvider(id)
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plugin does not support metadata search: $id")

    return provider.search(query)
  }

  @GetMapping("{id}/metadata/{externalId}")
  @Operation(summary = "Get detailed metadata from plugin", tags = [TagNames.PLUGINS])
  fun getMetadata(
    @PathVariable id: String,
    @PathVariable externalId: String,
  ): org.gotson.komga.domain.service.MetadataDetails {
    val plugin =
      pluginRepository.findByIdOrNull(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found: $id")

    if (!plugin.enabled) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plugin is not enabled: $id")
    }

    val provider =
      getMetadataProvider(id)
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plugin does not support metadata search: $id")

    return provider.getMetadata(externalId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata not found for external ID: $externalId")
  }

  @GetMapping("{id}/config")
  @Operation(summary = "Get plugin configuration", tags = [TagNames.PLUGINS])
  fun getPluginConfig(
    @PathVariable id: String,
  ): Map<String, String> =
    pluginConfigRepository
      .findByPluginId(id)
      .associate { it.configKey to (it.configValue ?: "") }

  @PostMapping("{id}/config")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Update plugin configuration", tags = [TagNames.PLUGINS])
  fun updatePluginConfig(
    @PathVariable id: String,
    @RequestBody config: Map<String, String>,
  ) {
    val plugin =
      pluginRepository.findByIdOrNull(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin not found: $id")

    // Delete existing config
    pluginConfigRepository.deleteByPluginId(id)

    // Insert new config
    config.forEach { (key, value) ->
      val pluginConfig =
        org.gotson.komga.domain.model.PluginConfig(
          id =
            com.github.f4b6a3.tsid.TsidCreator
              .getTsid256()
              .toString(),
          pluginId = id,
          configKey = key,
          configValue = value,
        )
      pluginConfigRepository.insert(pluginConfig)
    }

    if (id == "mangadex-subscription") {
      mangaDexSubscriptionSyncer.restart()
    }
  }

  @GetMapping("{id}/logs")
  @Operation(summary = "Get plugin logs", tags = [TagNames.PLUGINS])
  fun getPluginLogs(
    @PathVariable id: String,
    @RequestParam(required = false) level: org.gotson.komga.domain.model.LogLevel?,
    @RequestParam(defaultValue = "0") page: Int,
    @RequestParam(defaultValue = "100") size: Int,
  ): org.springframework.data.domain.Page<org.gotson.komga.domain.model.PluginLog> {
    val pageable =
      org.springframework.data.domain.PageRequest.of(
        page,
        size,
        org.springframework.data.domain.Sort
          .by(org.springframework.data.domain.Sort.Direction.DESC, "createdDate"),
      )

    return if (level != null) {
      pluginLogRepository.findByPluginIdAndLevel(id, level, pageable)
    } else {
      pluginLogRepository.findByPluginId(id, pageable)
    }
  }

  @DeleteMapping("{id}/logs")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Clear plugin logs", tags = [TagNames.PLUGINS])
  fun clearPluginLogs(
    @PathVariable id: String,
  ) {
    pluginLogRepository.deleteByPluginId(id)
  }

  @PostMapping("apply-metadata/{seriesId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun applyMetadata(
    @PathVariable seriesId: String,
    @RequestBody request: PluginApplyMetadataRequest,
  ) {
    val series =
      seriesRepository.findByIdOrNull(seriesId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found")

    logger.info { "Applying plugin metadata for series $seriesId: ${request.title}" }

    writeSeriesJson(series.path, request)

    logger.info { "Plugin metadata applied for series $seriesId" }
  }

  @PostMapping("apply-cover/{seriesId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun applyCover(
    @PathVariable seriesId: String,
    @RequestBody request: PluginApplyCoverRequest,
  ) {
    val series =
      seriesRepository.findByIdOrNull(seriesId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found")

    if (request.coverUrl.isBlank()) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cover URL is required")
    }

    downloadAndSetCover(series.id, series.path, request.coverUrl)
  }

  private fun writeSeriesJson(
    seriesPath: java.nio.file.Path,
    request: PluginApplyMetadataRequest,
  ) {
    val alternateTitles =
      request.alternativeTitles?.map { (title, lang) ->
        mapOf("title" to title, "language" to lang)
      } ?: emptyList()

    val metadata =
      mutableMapOf<String, Any>(
        "type" to "comicSeries",
        "name" to (request.title ?: "Unknown"),
      )
    request.summary?.let { metadata["description_text"] = it }
    request.publisher?.let { metadata["publisher"] = it }
    request.ageRating?.let {
      metadata["age_rating"] =
        when {
          it <= 0 -> "All"
          it <= 9 -> "9+"
          it <= 12 -> "12+"
          it <= 15 -> "15+"
          it <= 17 -> "17+"
          else -> "Adult"
        }
    }
    request.releaseDate?.toIntOrNull()?.let { metadata["year"] = it }
    request.status?.let {
      metadata["status"] =
        when (it.lowercase()) {
          "ongoing" -> "Continuing"
          "completed" -> "Ended"
          "hiatus" -> "Hiatus"
          "cancelled" -> "Cancelled"
          else -> it
        }
    }
    request.externalId?.let { metadata["comicid"] = it }
    if (request.genres?.isNotEmpty() == true) metadata["genres"] = request.genres
    if (request.tags?.isNotEmpty() == true) metadata["tags"] = request.tags
    if (alternateTitles.isNotEmpty()) metadata["alternate_titles"] = alternateTitles
    request.authors?.let { authorList ->
      val writers =
        authorList
          .filter { it.role.equals("author", ignoreCase = true) || it.role.equals("writer", ignoreCase = true) }
          .map { it.name }
          .distinct()
      val artists =
        authorList
          .filter { it.role.equals("artist", ignoreCase = true) || it.role.equals("penciller", ignoreCase = true) }
          .map { it.name }
          .distinct()
      if (writers.isNotEmpty()) metadata["authors"] = writers
      if (artists.isNotEmpty()) metadata["artists"] = artists
    }

    val seriesJson = mapOf("metadata" to metadata)
    val seriesJsonFile = seriesPath.resolve("series.json").toFile()
    val newContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(seriesJson)

    logger.info { "Writing series.json to ${seriesJsonFile.absolutePath}" }
    val tempFile = File(seriesJsonFile.parent, ".series.json.tmp")
    tempFile.writeText(newContent)
    try {
      Files.move(
        tempFile.toPath(),
        seriesJsonFile.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(tempFile.toPath(), seriesJsonFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    logger.info { "series.json written successfully" }
  }

  private fun downloadAndSetCover(
    seriesId: String,
    seriesPath: java.nio.file.Path,
    coverUrl: String,
  ) {
    val uri = URI.create(coverUrl)
    val host = uri.host ?: return
    if (allowedCoverHosts.none { host.endsWith(it) }) {
      logger.warn { "Cover URL host not allowed: $host" }
      return
    }

    logger.info { "Downloading cover for series $seriesId from $coverUrl" }

    val imageBytes =
      try {
        coverClient
          .get()
          .uri(uri)
          .retrieve()
          .body(ByteArray::class.java) ?: return
      } catch (e: Exception) {
        logger.error(e) { "Failed to download cover from $coverUrl" }
        return
      }

    val mediaType =
      imageBytes
        .inputStream()
        .buffered()
        .use { contentDetector.detectMediaType(it) }
    if (!contentDetector.isImage(mediaType)) {
      logger.warn { "Downloaded file is not an image: $mediaType" }
      return
    }

    val ext =
      when (mediaType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
      }
    val coverFile = seriesPath.resolve("cover.$ext").toFile()
    val tempCover = File(coverFile.parent, ".cover.$ext.tmp")
    try {
      tempCover.writeBytes(imageBytes)
      Files.move(
        tempCover.toPath(),
        coverFile.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      )
      logger.info { "Cover saved to ${coverFile.absolutePath}" }
    } catch (e: Exception) {
      logger.error(e) { "Failed to save cover file to disk" }
      tempCover.delete()
    }

    seriesLifecycle.addThumbnailForSeries(
      ThumbnailSeries(
        seriesId = seriesId,
        thumbnail = imageBytes,
        type = ThumbnailSeries.Type.USER_UPLOADED,
        fileSize = imageBytes.size.toLong(),
        mediaType = mediaType,
        dimension = imageAnalyzer.getDimension(imageBytes.inputStream().buffered()) ?: Dimension(0, 0),
      ),
      MarkSelectedPreference.YES,
    )
    logger.info { "Cover set for series $seriesId" }
  }
}

data class PluginApplyAuthor(
  val name: String,
  val role: String,
)

data class PluginApplyCoverRequest(
  val coverUrl: String,
)

data class PluginApplyMetadataRequest(
  val title: String? = null,
  val summary: String? = null,
  val publisher: String? = null,
  val ageRating: Int? = null,
  val releaseDate: String? = null,
  val status: String? = null,
  val externalId: String? = null,
  val genres: List<String>? = null,
  val tags: List<String>? = null,
  val authors: List<PluginApplyAuthor>? = null,
  val alternativeTitles: Map<String, String>? = null,
)

fun Plugin.toDto() =
  PluginDto(
    id = id,
    name = name,
    version = version,
    enabled = enabled,
    pluginType = pluginType.name,
    description = description,
    author = author,
    entryPoint = entryPoint,
    sourceUrl = sourceUrl,
    installedDate = installedDate,
    lastUpdated = lastUpdated,
    configSchema = configSchema,
    dependencies = dependencies,
  )
