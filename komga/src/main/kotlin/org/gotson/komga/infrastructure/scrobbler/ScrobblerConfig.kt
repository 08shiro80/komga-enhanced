package org.gotson.komga.infrastructure.scrobbler

import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository

class ScrobblerConfig(
  val values: Map<String, String?>,
) {
  private val libraryIds = csv("library_ids")
  private val excludedLibraryIds = csv("exclude_library_ids")

  fun includesLibrary(libraryId: String): Boolean = (libraryIds.isEmpty() || libraryId in libraryIds) && libraryId !in excludedLibraryIds

  private fun csv(key: String): Set<String> =
    values[key]
      ?.split(',')
      ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
      ?.toSet()
      .orEmpty()

  companion object {
    fun load(
      pluginConfigRepository: PluginConfigRepository,
      pluginId: String,
    ) = ScrobblerConfig(pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue })

    fun enabled(
      pluginRepository: PluginRepository,
      pluginId: String,
    ): Boolean = pluginRepository.findByIdOrNull(pluginId)?.enabled == true
  }
}
