package org.gotson.komga.infrastructure.download

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Component
class GalleryDlProcess {
  private val objectMapper: ObjectMapper = jacksonObjectMapper()

  fun isInstalled(galleryDlPath: String?): Boolean {
    return try {
      val command = getCommand(galleryDlPath) + "--version"
      val process =
        applyEnv(ProcessBuilder(), galleryDlPath)
          .command(command)
          .start()
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return false
      }
      process.exitValue() == 0
    } catch (e: Exception) {
      logger.warn(e) { "gallery-dl not found" }
      false
    }
  }

  fun getCommand(galleryDlPath: String?): List<String> =
    try {
      val process =
        applyEnv(ProcessBuilder(), galleryDlPath)
          .command("gallery-dl", "--version")
          .start()
      process.waitFor(2, TimeUnit.SECONDS)
      if (process.exitValue() == 0) {
        listOf("gallery-dl")
      } else {
        getPythonCommand(galleryDlPath)
      }
    } catch (e: Exception) {
      getPythonCommand(galleryDlPath)
    }

  fun applyEnv(
    processBuilder: ProcessBuilder,
    galleryDlPath: String?,
  ): ProcessBuilder {
    if (galleryDlPath != null) {
      val env = processBuilder.environment()
      val existing = env["PYTHONPATH"]
      env["PYTHONPATH"] =
        if (existing.isNullOrBlank()) galleryDlPath else "$galleryDlPath${File.pathSeparator}$existing"
      logger.debug { "Set PYTHONPATH=$galleryDlPath for gallery-dl" }
    }
    return processBuilder
  }

  private fun getPythonCommand(galleryDlPath: String?): List<String> {
    val pythonCmds = listOf("python3", "python")
    return pythonCmds
      .firstOrNull { python ->
        try {
          val process =
            applyEnv(ProcessBuilder(), galleryDlPath)
              .command(python, "-m", "gallery_dl", "--version")
              .start()
          process.waitFor(2, TimeUnit.SECONDS)
          process.exitValue() == 0
        } catch (e: Exception) {
          false
        }
      }?.let { listOf(it, "-m", "gallery_dl") } ?: listOf("gallery-dl")
  }

  fun createTempConfigFile(
    pluginConfig: Map<String, String>,
    defaultLanguage: String,
  ): File {
    val tempFile = File.createTempFile("gallery-dl-", ".json")
    val mangadexUsername = pluginConfig["mangadex_username"]
    val mangadexPassword = pluginConfig["mangadex_password"]
    val websiteConfigs = getDefaultWebsiteConfigs(defaultLanguage).toMutableMap()

    if (!mangadexUsername.isNullOrBlank() || !mangadexPassword.isNullOrBlank()) {
      val mangadexConfig = websiteConfigs["mangadex"]?.toMutableMap() ?: mutableMapOf()
      if (!mangadexUsername.isNullOrBlank()) mangadexConfig["username"] = mangadexUsername
      if (!mangadexPassword.isNullOrBlank()) mangadexConfig["password"] = mangadexPassword
      websiteConfigs["mangadex"] = mangadexConfig
    }

    val config =
      mutableMapOf<String, Any>(
        "extractor" to
          mutableMapOf<String, Any>(
            "base-directory" to "",
          ).apply { putAll(websiteConfigs) },
        "postprocessors" to
          listOf(
            mapOf(
              "name" to "zip",
              "extension" to "cbz",
              "compression" to "store",
              "keep-files" to false,
            ),
          ),
      )

    tempFile.writeText(objectMapper.writeValueAsString(config))
    logger.debug { "Created config file with ${websiteConfigs.size} website configs" }
    return tempFile
  }

  fun createInfoConfigFile(
    mangadexUsername: String?,
    mangadexPassword: String?,
    defaultLanguage: String,
  ): File {
    val tempFile = File.createTempFile("gallery-dl-info-", ".json")
    val config =
      mutableMapOf<String, Any>(
        "extractor" to
          mutableMapOf<String, Any>(
            "base-directory" to "",
            "mangadex" to
              mutableMapOf<String, Any>("lang" to defaultLanguage).apply {
                if (!mangadexUsername.isNullOrBlank()) this["username"] = mangadexUsername
                if (!mangadexPassword.isNullOrBlank()) this["password"] = mangadexPassword
              },
          ),
      )
    tempFile.writeText(objectMapper.writeValueAsString(config))
    return tempFile
  }

  fun createCoverConfigFile(
    mangadexUsername: String?,
    mangadexPassword: String?,
  ): File {
    val tempFile = File.createTempFile("gallery-dl-cover-", ".json")
    val config =
      mutableMapOf<String, Any>(
        "extractor" to
          mutableMapOf<String, Any>(
            "base-directory" to "",
            "mangadex" to
              mutableMapOf<String, Any>("lang" to "en").apply {
                if (!mangadexUsername.isNullOrBlank()) this["username"] = mangadexUsername
                if (!mangadexPassword.isNullOrBlank()) this["password"] = mangadexPassword
              },
          ),
      )
    tempFile.writeText(objectMapper.writeValueAsString(config))
    return tempFile
  }

  fun getDefaultWebsiteConfigs(defaultLanguage: String): Map<String, Map<String, Any>> =
    mapOf(
      "mangadex" to
        mapOf(
          "lang" to defaultLanguage,
          "api" to "api",
          "data-saver" to false,
          "path-restrict" to "auto",
          "path-replace" to "_",
          "directory" to listOf("{volume:?v/ /}c{chapter:>03}{chapter_minor} [{group:J, }]"),
          "filename" to "{page:>03}.{extension}",
        ),
      "mangahere" to
        mapOf(
          "directory" to listOf("c{chapter:>03}{chapter_minor} [{group:J, }]"),
          "filename" to "{page:>03}.{extension}",
        ),
      "comick" to
        mapOf(
          "lang" to defaultLanguage,
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "batoto" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "mangasee" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "mangakakalot" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "manganato" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "webtoons" to
        mapOf(
          "directory" to listOf("e{episode:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "asurascans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "flamescans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "reaperscans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "mangaplus" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "imgur" to
        mapOf(
          "directory" to listOf("{album[title]}"),
          "filename" to "{num:>03}.{extension}",
        ),
      "nhentai" to
        mapOf(
          "directory" to listOf("{gallery_id}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "exhentai" to
        mapOf(
          "directory" to listOf("{gallery_id}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "rawkuma" to
        mapOf(
          "directory" to listOf("{chapter_id}"),
          "filename" to "{page:>03}.{extension}",
        ),
    )
}
