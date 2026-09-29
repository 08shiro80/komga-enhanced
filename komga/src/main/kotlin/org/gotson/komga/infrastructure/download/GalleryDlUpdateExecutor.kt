package org.gotson.komga.infrastructure.download

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.PluginConfig
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

data class GalleryDlUpdateResult(
  val success: Boolean,
  val message: String,
  val installedPath: String? = null,
  val overlaidFiles: Int = 0,
)

data class GalleryDlUpdateStatus(
  val running: Boolean,
  val url: String? = null,
  val result: GalleryDlUpdateResult? = null,
)

/**
 * Updates gallery-dl from a user-supplied URL (e.g. official codeberg/PyPI) into the Komga config
 * dir via `pip install --target`, then re-overlays the fork's Komga integration on top so the
 * bundled fork parts (komga/komgazip postprocessors, custom extractors, core patches) survive.
 * Points the `gallery-dl-downloader` plugin's `gallery_dl_path` at the result (PYTHONPATH override),
 * so the next download subprocess picks it up without a restart.
 */
@Component
class GalleryDlUpdateExecutor(
  private val komgaProperties: KomgaProperties,
  private val pluginConfigRepository: PluginConfigRepository,
) {
  @Volatile
  private var running = false

  @Volatile
  private var currentUrl: String? = null

  @Volatile
  private var lastResult: GalleryDlUpdateResult? = null

  @Synchronized
  fun startUpdate(url: String): Boolean {
    if (running) return false
    running = true
    currentUrl = url.trim()
    lastResult = null
    Thread {
      try {
        lastResult = update(url)
      } finally {
        running = false
      }
    }.apply { isDaemon = true }
      .start()
    return true
  }

  fun status(): GalleryDlUpdateStatus = GalleryDlUpdateStatus(running, currentUrl, lastResult)

  private fun update(url: String): GalleryDlUpdateResult {
    val requested = url.trim()
    if (requested.isEmpty()) return GalleryDlUpdateResult(false, "No URL provided")

    val configDir =
      komgaProperties.configDir
        ?: return GalleryDlUpdateResult(false, "Komga config dir is not configured")

    val stagingDir = File(configDir, "gallery-dl.staging")
    return try {
      runUpdate(requested, configDir, stagingDir)
    } catch (e: Exception) {
      logger.error(e) { "gallery-dl update from $requested failed" }
      GalleryDlUpdateResult(false, "Update failed: ${e.message}")
    } finally {
      if (stagingDir.exists()) stagingDir.deleteRecursively()
    }
  }

  private fun runUpdate(
    requested: String,
    configDir: String,
    stagingDir: File,
  ): GalleryDlUpdateResult {
    // Overlay source = the baked-in fork install (found without any PYTHONPATH override so it is
    // never the previously updated copy). It ships the manifest and all fork files.
    val forkSource =
      locateBundledGalleryDl()
        ?: return GalleryDlUpdateResult(false, "Could not locate the bundled gallery-dl install")

    val manifestFile = forkSource.resolve(MANIFEST_FILE)
    if (!manifestFile.exists()) {
      return GalleryDlUpdateResult(
        false,
        "Fork manifest $MANIFEST_FILE not found in $forkSource — rebuild the image with the fork manifest",
      )
    }
    val forkFiles =
      manifestFile
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    val targetDir = File(configDir, "gallery-dl")
    stagingDir.deleteRecursively()

    // 1) install the requested gallery-dl into staging (--target writes to the config dir, owned
    //    by the Komga user, so no root is needed)
    val pip =
      listOf(
        pythonExecutable(),
        "-m",
        "pip",
        "install",
        "--target",
        stagingDir.absolutePath,
        "--no-cache-dir",
        "--upgrade",
        requested,
      )
    val install = runProcess(pip, timeoutSeconds = 600)
    if (!install.success) {
      stagingDir.deleteRecursively()
      return GalleryDlUpdateResult(false, "pip install failed: ${install.output.takeLast(2000)}")
    }

    val stagedPackage = File(stagingDir, "gallery_dl")
    if (!stagedPackage.isDirectory) {
      stagingDir.deleteRecursively()
      return GalleryDlUpdateResult(
        false,
        "The installed distribution has no gallery_dl module — is the URL a gallery-dl distribution?",
      )
    }

    // 2) re-overlay the fork integration files on top of the fresh upstream (fork version wins)
    var overlaid = 0
    for (rel in forkFiles) {
      val src = forkSource.resolve(rel)
      if (!src.exists()) {
        logger.warn { "Fork manifest lists $rel but it is missing in $forkSource — skipped" }
        continue
      }
      val dst = stagedPackage.resolve(rel)
      dst.parentFile?.mkdirs()
      src.copyTo(dst, overwrite = true)
      overlaid++
    }
    // carry the manifest itself forward so a subsequent update can still read it
    manifestFile.copyTo(stagedPackage.resolve(MANIFEST_FILE), overwrite = true)

    // 3) atomically swap staging into place
    val backupDir = File(configDir, "gallery-dl.old")
    backupDir.deleteRecursively()
    if (targetDir.exists() && !targetDir.renameTo(backupDir)) {
      return GalleryDlUpdateResult(false, "Could not move the previous install aside")
    }
    if (!stagingDir.renameTo(targetDir)) {
      // restore the previous install on failure
      val restored = !backupDir.exists() || backupDir.renameTo(targetDir)
      return GalleryDlUpdateResult(
        false,
        if (restored) {
          "Could not move the new install into place — previous install restored"
        } else {
          "Could not move the new install into place AND failed to restore the previous one — set gallery_dl_path empty to fall back to the bundled fork"
        },
      )
    }
    backupDir.deleteRecursively()

    // 4) point the plugin config at the new install (PYTHONPATH override for download subprocesses)
    setConfig(GALLERY_DL_PATH_KEY, targetDir.absolutePath)

    logger.info { "gallery-dl updated from $requested → $targetDir ($overlaid fork files re-applied)" }
    return GalleryDlUpdateResult(
      success = true,
      message = "gallery-dl updated and fork integration re-applied ($overlaid files). New downloads use it immediately.",
      installedPath = targetDir.absolutePath,
      overlaidFiles = overlaid,
    )
  }

  private fun locateBundledGalleryDl(): File? {
    val result =
      runProcess(
        listOf(
          pythonExecutable(),
          "-c",
          "import gallery_dl, os; print(os.path.dirname(gallery_dl.__file__))",
        ),
        timeoutSeconds = 10,
      )
    if (!result.success) return null
    val path =
      result.output
        .trim()
        .lines()
        .lastOrNull()
        ?.trim()
        .orEmpty()
    return File(path).takeIf { it.isDirectory }
  }

  private fun pythonExecutable(): String =
    listOf("python3", "python").firstOrNull { exe ->
      try {
        ProcessBuilder(exe, "--version")
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor(3, TimeUnit.SECONDS)
      } catch (e: Exception) {
        false
      }
    } ?: "python3"

  private fun setConfig(
    key: String,
    value: String,
  ) {
    val existing = pluginConfigRepository.findByPluginIdAndKey(PLUGIN_ID, key)
    if (existing != null) {
      pluginConfigRepository.update(existing.copy(configValue = value))
    } else {
      pluginConfigRepository.insert(
        PluginConfig(
          id = UUID.randomUUID().toString(),
          pluginId = PLUGIN_ID,
          configKey = key,
          configValue = value,
        ),
      )
    }
  }

  private data class ProcessResult(
    val success: Boolean,
    val output: String,
  )

  private fun runProcess(
    command: List<String>,
    timeoutSeconds: Long,
  ): ProcessResult {
    return try {
      val process =
        ProcessBuilder(command)
          .redirectErrorStream(true)
          .start()
      // Drain the merged output on a separate thread so waitFor's timeout stays effective even
      // when the child process hangs without closing its stream (readText would block forever).
      val output = StringBuilder()
      val drainThread =
        Thread {
          process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line -> output.append(line).append('\n') }
          }
        }
      drainThread.start()
      val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        drainThread.join(5000)
        if (drainThread.isAlive) drainThread.interrupt()
        return ProcessResult(false, "$output\n(timed out after ${timeoutSeconds}s)")
      }
      drainThread.join(5000)
      if (drainThread.isAlive) drainThread.interrupt()
      ProcessResult(process.exitValue() == 0, output.toString())
    } catch (e: Exception) {
      logger.error(e) { "gallery-dl update process failed: ${command.joinToString(" ")}" }
      ProcessResult(false, e.message ?: "process failed")
    }
  }

  companion object {
    private const val PLUGIN_ID = "gallery-dl-downloader"
    private const val GALLERY_DL_PATH_KEY = "gallery_dl_path"
    private const val MANIFEST_FILE = "_komga_fork_files.txt"
  }
}
