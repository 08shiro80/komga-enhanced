package org.gotson.komga.infrastructure.plugin

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
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
import java.nio.file.AtomicMoveNotSupportedException
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

    val preloaded = mutableMapOf<Path, LoadedPlugin>()
    Files.newDirectoryStream(dir, "*.jar").use { stream ->
      stream.forEach { jar ->
        try {
          preloaded[jar] = pluginLoader.load(jar)
        } catch (e: Exception) {
          logger.error(e) { "Failed to load external plugin JAR: ${jar.fileName}" }
        }
      }
    }

    val bundledNames = copyBundledDefaults(preloaded)

    preloaded.entries
      .groupBy { it.value.instance.id }
      .values
      .filter { it.size > 1 }
      .forEach { duplicates ->
        val winner = duplicates.lastOrNull { it.key.fileName.toString() !in bundledNames } ?: duplicates.last()
        duplicates.filter { it !== winner }.forEach { loser ->
          logger.warn { "Duplicate plugin id '${loser.value.instance.id}': ignoring ${loser.key.fileName} in favor of ${winner.key.fileName}" }
          closeQuietly(loser.value)
          preloaded.remove(loser.key)
        }
      }

    preloaded.values.forEach { plugin ->
      try {
        register(plugin)
        logger.info { "Loaded external plugin from ${plugin.jarPath.fileName}" }
      } catch (e: Exception) {
        logger.error(e) { "Failed to register external plugin: ${plugin.jarPath.fileName}" }
        closeQuietly(plugin)
      }
    }
  }

  private fun copyBundledDefaults(preloaded: MutableMap<Path, LoadedPlugin>): Set<String> {
    val resources =
      try {
        resourceResolver.getResources("classpath*:default-plugins/*.jar")
      } catch (e: Exception) {
        logger.warn(e) { "Failed to scan bundled default plugins" }
        return emptySet()
      }
    val bundledNames = mutableSetOf<String>()
    resources.forEach { resource ->
      val name = resource.filename ?: return@forEach
      bundledNames.add(name)
      val target = pluginsDir.resolve(name)
      try {
        val temp = Files.createTempFile(pluginsDir, "bundled-", ".jar.tmp")
        try {
          resource.inputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
          val probed = pluginLoader.load(temp)
          val bundledId = probed.instance.id
          closeQuietly(probed)
          val override = preloaded.entries.firstOrNull { it.value.instance.id == bundledId && it.key.fileName != target.fileName }
          if (override != null) {
            logger.info { "Skipping bundled default plugin $name: plugin id '$bundledId' is provided by user-installed ${override.key.fileName}" }
            return@forEach
          }
          try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
          } catch (e: AtomicMoveNotSupportedException) {
            logger.debug(e) { "Atomic move unsupported, falling back to non-atomic replace for $name" }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
          }
          val reloaded = pluginLoader.load(target)
          preloaded.entries.firstOrNull { it.key.fileName == target.fileName }?.let {
            closeQuietly(it.value)
            preloaded.remove(it.key)
          }
          preloaded[target] = reloaded
          logger.info { "Installed bundled default plugin: $name" }
        } finally {
          Files.deleteIfExists(temp)
        }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to install bundled default plugin: $name" }
      }
    }
    return bundledNames
  }

  @Synchronized
  fun install(
    originalFilename: String,
    bytes: ByteArray,
  ): Plugin {
    Files.createDirectories(pluginsDir)
    val target = pluginsDir.resolve(sanitizeJarName(originalFilename))
    val temp = Files.createTempFile(pluginsDir, "upload-", ".jar.tmp")
    val newId =
      try {
        Files.write(temp, bytes)
        val probed = pluginLoader.load(temp)
        val id = probed.instance.id
        closeQuietly(probed)
        id
      } catch (e: Exception) {
        Files.deleteIfExists(temp)
        throw e
      }
    val owner = loaded.values.firstOrNull { it.jarPath.fileName == target.fileName && it.instance.id != newId }
    if (owner != null) {
      Files.deleteIfExists(temp)
      throw PluginLoadException("Filename ${target.fileName} already belongs to installed plugin '${owner.instance.id}' — rename the JAR or uninstall that plugin first")
    }
    loaded.remove(newId)?.let { old ->
      shutdownAndClose(old)
      // Reinstall under a different filename would otherwise leave the old JAR on disk and load
      // both (nondeterministic version) on next startup.
      if (old.jarPath.fileName != target.fileName) Files.deleteIfExists(old.jarPath)
    }
    try {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
      logger.debug(e) { "Atomic move unsupported, falling back to non-atomic replace for ${target.fileName}" }
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
    val newPlugin = pluginLoader.load(target)
    register(newPlugin)
    return pluginRepository.findById(newPlugin.instance.id)
  }

  @Synchronized
  fun uninstall(id: String): Boolean {
    val plugin = loaded.remove(id) ?: return false
    shutdownAndClose(plugin)
    Files.deleteIfExists(plugin.jarPath)
    return true
  }

  @Synchronized
  @PreDestroy
  fun shutdownAll() {
    loaded.values.forEach { shutdownAndClose(it) }
    loaded.clear()
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

  @EventListener
  fun onNewChaptersQueued(event: DomainEvent.NewChaptersQueued) {
    notify(
      PluginNotification(
        type = "NEW_CHAPTERS_QUEUED",
        title = "New chapters queued",
        message = "Queued ${event.count} item(s) from ${event.source}",
        data =
          mapOf(
            "count" to event.count.toString(),
            "source" to event.source,
            "libraryId" to (event.libraryId ?: ""),
          ),
      ),
    )
  }

  @EventListener
  fun onChapterAutoBlacklisted(event: DomainEvent.ChapterAutoBlacklisted) {
    notify(
      PluginNotification(
        type = "CHAPTER_AUTO_BLACKLISTED",
        title = "Chapter auto-blacklisted",
        message = "Auto-blacklisted chapter ${event.chapterNumber ?: event.chapterUrl} (${event.reason})",
        data =
          mapOf(
            "seriesId" to (event.seriesId ?: ""),
            "chapterUrl" to event.chapterUrl,
            "chapterNumber" to (event.chapterNumber ?: ""),
            "reason" to event.reason,
          ),
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
    loaded.put(instance.id, newPlugin)?.let { old ->
      logger.warn { "Plugin ${instance.id} was already loaded from ${old.jarPath.fileName}, replacing with ${newPlugin.jarPath.fileName}" }
      shutdownAndClose(old)
    }
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

  private fun shutdownAndClose(plugin: LoadedPlugin) {
    try {
      plugin.instance.shutdown()
    } catch (e: Exception) {
      logger.warn(e) { "Plugin ${plugin.instance.id} failed to shut down cleanly" }
    }
    closeQuietly(plugin)
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
