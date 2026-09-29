package org.gotson.komga.infrastructure.plugin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.service.Author
import org.gotson.komga.domain.service.MetadataDetails
import org.gotson.komga.domain.service.MetadataSearchResult
import org.gotson.komga.domain.service.OnlineMetadataProvider
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.plugin.api.KomgaPlugin
import org.gotson.komga.infrastructure.plugin.api.KomgaPluginType
import org.gotson.komga.infrastructure.plugin.api.MetadataProviderPlugin
import org.gotson.komga.infrastructure.plugin.api.NotifierPlugin
import org.gotson.komga.infrastructure.plugin.api.PluginContext
import org.gotson.komga.infrastructure.plugin.api.PluginNotification
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Loads, tracks, installs and uninstalls externally provided plugin JARs.
 * Built-in plugins are NOT managed here — they are registered by PluginInitializer.
 */
@Component
class PluginRegistry(
  private val komgaProperties: KomgaProperties,
  private val pluginLoader: PluginLoader,
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
) {
  // Concurrent: mutators are @Synchronized, but reads/iteration (notify, metadataProviderFor,
  // isExternal) run without the lock — notify() is triggered by async download events.
  private val loaded = ConcurrentHashMap<String, LoadedPlugin>()

  private val resourceResolver = PathMatchingResourcePatternResolver()

  private val pluginsDir: Path
    get() = Paths.get(komgaProperties.configDir ?: "${System.getProperty("user.home")}/.komga", "plugins")

  @EventListener(ApplicationReadyEvent::class)
  @Synchronized
  fun loadAllOnStartup() {
    val dir = pluginsDir
    Files.createDirectories(dir)
    copyBundledDefaults()
    Files.newDirectoryStream(dir, "*.jar").use { stream ->
      stream.forEach { jar ->
        try {
          register(pluginLoader.load(jar))
          logger.info { "Loaded external plugin from ${jar.fileName}" }
        } catch (e: Exception) {
          logger.error(e) { "Failed to load external plugin JAR: ${jar.fileName}" }
        }
      }
    }
  }

  /** Copies JARs bundled in the app (classpath `default-plugins/`) into the plugins dir if missing. */
  private fun copyBundledDefaults() {
    val resources =
      try {
        resourceResolver.getResources("classpath*:default-plugins/*.jar")
      } catch (e: Exception) {
        logger.warn(e) { "Failed to scan bundled default plugins" }
        return
      }
    resources.forEach { resource ->
      val name = resource.filename ?: return@forEach
      val target = pluginsDir.resolve(name)
      try {
        // always overwrite so a rebuilt default plugin actually replaces the old jar
        resource.inputStream.use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }
        logger.info { "Installed bundled default plugin: $name" }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to install bundled default plugin: $name" }
      }
    }
  }

  @Synchronized
  fun install(
    originalFilename: String,
    bytes: ByteArray,
  ): Plugin {
    Files.createDirectories(pluginsDir)
    val target = pluginsDir.resolve(sanitizeJarName(originalFilename))
    Files.write(target, bytes)
    val newPlugin =
      try {
        pluginLoader.load(target)
      } catch (e: PluginLoadException) {
        Files.deleteIfExists(target)
        throw e
      }
    loaded.remove(newPlugin.instance.id)?.let { old ->
      closeQuietly(old)
      // Reinstall under a different filename would otherwise leave the old JAR on disk and load
      // both (nondeterministic version) on next startup.
      if (old.jarPath.fileName != target.fileName) Files.deleteIfExists(old.jarPath)
    }
    register(newPlugin)
    return pluginRepository.findById(newPlugin.instance.id)
  }

  @Synchronized
  fun uninstall(id: String): Boolean {
    val plugin = loaded.remove(id) ?: return false
    try {
      plugin.instance.shutdown()
    } catch (e: Exception) {
      logger.warn(e) { "Plugin $id failed to shut down cleanly" }
    }
    closeQuietly(plugin)
    Files.deleteIfExists(plugin.jarPath)
    return true
  }

  fun isExternal(id: String): Boolean = loaded.containsKey(id)

  fun metadataProviderFor(id: String): OnlineMetadataProvider? {
    val plugin = loaded[id]?.instance as? MetadataProviderPlugin ?: return null
    return toOnlineProvider(plugin, id)
  }

  @EventListener
  fun onDownloadCompleted(event: DomainEvent.DownloadCompleted) {
    notify(
      PluginNotification(
        type = "DOWNLOAD_COMPLETED",
        title = event.title ?: "Download completed",
        message = "Downloaded ${event.filesDownloaded} file(s)",
        data =
          mapOf(
            "downloadId" to event.downloadId,
            "libraryId" to (event.libraryId ?: ""),
            "filesDownloaded" to event.filesDownloaded.toString(),
          ),
      ),
    )
  }

  @EventListener
  fun onDownloadFailed(event: DomainEvent.DownloadFailed) {
    notify(
      PluginNotification(
        type = "DOWNLOAD_FAILED",
        title = event.title ?: "Download failed",
        message = event.errorMessage ?: "Unknown error",
        data = mapOf("downloadId" to event.downloadId),
      ),
    )
  }

  private fun notify(notification: PluginNotification) {
    loaded.values.forEach { plugin ->
      val notifier = plugin.instance as? NotifierPlugin ?: return@forEach
      if (pluginRepository.findByIdOrNull(plugin.instance.id)?.enabled != true) return@forEach
      try {
        notifier.onNotification(notification)
      } catch (e: Exception) {
        logger.warn(e) { "Notifier plugin ${plugin.instance.id} failed to handle ${notification.type}" }
      }
    }
  }

  private fun register(newPlugin: LoadedPlugin) {
    val instance = newPlugin.instance
    val domain = toDomain(instance, newPlugin.jarPath)
    if (pluginRepository.findByIdOrNull(instance.id) == null) {
      pluginRepository.insert(domain)
    } else {
      pluginRepository.update(domain)
    }
    loaded[instance.id] = newPlugin
    try {
      instance.initialize(RepositoryPluginContext(instance.id))
    } catch (e: Exception) {
      logger.warn(e) { "Plugin ${instance.id} failed to initialize" }
    }
  }

  private fun toDomain(
    plugin: KomgaPlugin,
    jarPath: Path,
  ): Plugin {
    val existing = pluginRepository.findByIdOrNull(plugin.id)
    val now = LocalDateTime.now()
    return Plugin(
      id = plugin.id,
      name = plugin.name,
      version = plugin.version,
      author = plugin.author,
      description = plugin.description,
      enabled = existing?.enabled ?: true,
      pluginType = toDomainType(plugin.type),
      entryPoint = plugin.javaClass.name,
      sourceUrl = jarPath.fileName.toString(),
      installedDate = existing?.installedDate ?: now,
      lastUpdated = now,
      configSchema = plugin.configSchema ?: existing?.configSchema,
      dependencies = null,
    )
  }

  private fun toDomainType(type: KomgaPluginType): PluginType =
    when (type) {
      KomgaPluginType.METADATA -> PluginType.METADATA
      KomgaPluginType.NOTIFIER -> PluginType.NOTIFIER
      KomgaPluginType.ANALYZER -> PluginType.ANALYZER
      KomgaPluginType.PROCESSOR -> PluginType.PROCESSOR
    }

  private fun toOnlineProvider(
    plugin: MetadataProviderPlugin,
    providerId: String,
  ): OnlineMetadataProvider =
    object : OnlineMetadataProvider {
      override fun search(query: String): List<MetadataSearchResult> =
        plugin.search(query).map {
          MetadataSearchResult(
            externalId = it.externalId,
            title = it.title,
            description = it.description,
            coverUrl = it.coverUrl,
            author = it.author,
            year = it.year,
            status = it.status,
            tags = it.tags,
            provider = providerId,
          )
        }

      override fun getMetadata(externalId: String): MetadataDetails? =
        plugin.getMetadata(externalId)?.let {
          MetadataDetails(
            title = it.title,
            titleSort = it.titleSort,
            summary = it.summary,
            publisher = it.publisher,
            ageRating = it.ageRating,
            releaseDate = it.releaseDate,
            authors = it.authors.map { a -> Author(a.name, a.role) },
            tags = it.tags,
            genres = it.genres,
            language = it.language,
            status = it.status,
            coverUrl = it.coverUrl,
            alternativeTitles = it.alternativeTitles,
          )
        }
    }

  private fun closeQuietly(plugin: LoadedPlugin) {
    try {
      plugin.classLoader.close()
    } catch (e: Exception) {
      logger.warn(e) { "Failed to close class loader for ${plugin.jarPath.fileName}" }
    }
  }

  private fun sanitizeJarName(name: String): String {
    val fileName = Paths.get(name).fileName.toString()
    val base = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return if (base.endsWith(".jar", ignoreCase = true)) base else "$base.jar"
  }

  private inner class RepositoryPluginContext(
    private val pluginId: String,
  ) : PluginContext {
    override val config: Map<String, String>
      get() =
        pluginConfigRepository
          .findByPluginId(pluginId)
          .mapNotNull { c -> c.configValue?.let { c.configKey to it } }
          .toMap()

    override fun info(message: String) = write(LogLevel.INFO, message, null)

    override fun warn(message: String) = write(LogLevel.WARN, message, null)

    override fun error(
      message: String,
      throwable: Throwable?,
    ) = write(LogLevel.ERROR, message, throwable)

    private fun write(
      level: LogLevel,
      message: String,
      throwable: Throwable?,
    ) {
      val line = "[$pluginId] $message"
      when (level) {
        LogLevel.ERROR -> logger.error(throwable) { line }
        LogLevel.WARN -> logger.warn(throwable) { line }
        LogLevel.DEBUG -> logger.debug(throwable) { line }
        else -> logger.info(throwable) { line }
      }
    }
  }
}
