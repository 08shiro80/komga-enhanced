package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.service.OnlineMetadataProvider
import org.gotson.komga.infrastructure.metadata.anilist.AniListMetadataPlugin
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
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
) {
  private fun getMetadataProvider(pluginId: String): OnlineMetadataProvider? =
    when (pluginId) {
      "mangadex-metadata" -> mangaDexMetadataPlugin
      "anilist-metadata" -> aniListMetadataPlugin
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

  @org.springframework.web.bind.annotation.PostMapping("{id}/config")
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
}

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
