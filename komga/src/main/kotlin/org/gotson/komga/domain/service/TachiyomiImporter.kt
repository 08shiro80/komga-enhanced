@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.gotson.komga.domain.model.Library
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

private val logger = KotlinLogging.logger {}

/**
 * Service for importing Tachiyomi/Mihon backup files.
 * Extracts MangaDex URLs and adds them to a library's follow.txt file.
 */
@Service
class TachiyomiImporter {
  // MangaDex source ID in Tachiyomi
  private val mangaDexSourceId = 2499283573021220255L

  private val protobuf =
    ProtoBuf {
      encodeDefaults = false
    }

  /**
   * Imports manga from a Tachiyomi backup file.
   * Supports .proto.gz (Tachiyomi) and .tachibk (Mihon/forks) formats.
   */
  fun importBackup(
    backupFile: Path,
    targetLibrary: Library,
    sourceFilter: Set<String>? = null,
  ): TachiyomiImportResult {
    logger.info { "Importing Tachiyomi backup: $backupFile" }

    val backup = parseBackup(backupFile)

    // Filter for MangaDex manga only
    val mangaDexManga =
      backup.backupManga.filter { manga ->
        manga.source == mangaDexSourceId
      }

    logger.info { "Found ${backup.backupManga.size} total manga, ${mangaDexManga.size} from MangaDex" }

    // Log all sources for debugging
    val sourceCounts = backup.backupManga.groupBy { it.source }.mapValues { it.value.size }
    logger.info { "Sources in backup: $sourceCounts" }

    val imported = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val urlsToAdd = mutableListOf<String>()

    val followFile = targetLibrary.path.resolve("follow.txt")

    // Load existing URLs to avoid duplicates
    val existingUrls = loadExistingUrls(followFile)

    mangaDexManga.forEach { manga ->
      try {
        val mangaDexUrl = convertToMangaDexUrl(manga.url)

        if (mangaDexUrl != null) {
          if (existingUrls.contains(mangaDexUrl)) {
            skipped.add(manga.title)
            logger.debug { "Skipped (already exists): ${manga.title}" }
          } else {
            urlsToAdd.add(mangaDexUrl)
            imported.add(manga.title)
            logger.debug { "Will import: ${manga.title} -> $mangaDexUrl" }
          }
        } else {
          errors.add("${manga.title}: Invalid URL format (${manga.url})")
          logger.warn { "Could not convert URL for ${manga.title}: ${manga.url}" }
        }
      } catch (e: Exception) {
        errors.add("${manga.title}: ${e.message}")
        logger.warn(e) { "Error processing ${manga.title}" }
      }
    }

    // Write all URLs at once
    if (urlsToAdd.isNotEmpty()) {
      appendUrlsToFollowFile(followFile, urlsToAdd)
    }

    logger.info {
      "Import complete: ${imported.size} imported, ${skipped.size} skipped, ${errors.size} errors"
    }

    return TachiyomiImportResult(
      totalInBackup = backup.backupManga.size,
      mangaDexCount = mangaDexManga.size,
      imported = imported,
      skipped = skipped,
      errors = errors,
    )
  }

  @OptIn(ExperimentalSerializationApi::class)
  private fun parseBackup(file: Path): Backup {
    logger.info { "Parsing backup file: ${file.fileName}" }

    val bytes =
      try {
        // Read and decompress the gzip file
        GZIPInputStream(Files.newInputStream(file)).use { gzip ->
          gzip.readBytes()
        }
      } catch (e: java.util.zip.ZipException) {
        // File might not be gzipped, try reading directly
        logger.debug { "Not a gzip file, trying direct read" }
        Files.readAllBytes(file)
      }

    logger.info { "Read ${bytes.size} bytes from backup" }

    // Log first few bytes for debugging
    val hexPreview = bytes.take(50).joinToString(" ") { "%02x".format(it) }
    logger.debug { "First 50 bytes (hex): $hexPreview" }

    return try {
      val backup = protobuf.decodeFromByteArray<Backup>(bytes)
      logger.info { "Successfully parsed as Protobuf: ${backup.backupManga.size} manga found" }

      // Log first manga for debugging
      if (backup.backupManga.isNotEmpty()) {
        val first = backup.backupManga.first()
        logger.info { "First manga: source=${first.source}, url=${first.url}, title=${first.title}" }
      }

      backup
    } catch (protoError: Exception) {
      logger.error(protoError) { "Protobuf parsing failed" }

      // Try as JSON (legacy format)
      try {
        val jsonString = bytes.decodeToString()
        logger.info { "Trying JSON parse, content starts with: ${jsonString.take(100)}" }
        parseJsonBackup(jsonString)
      } catch (jsonError: Exception) {
        logger.error(jsonError) { "JSON parsing also failed" }
        throw IllegalArgumentException(
          "Could not parse backup file. Proto error: ${protoError.message}",
          protoError,
        )
      }
    }
  }

  private fun parseJsonBackup(jsonString: String): Backup {
    val mangaList = mutableListOf<BackupManga>()

    // Find backupManga array in JSON
    val mangaPattern = Regex(""""source"\s*:\s*(\d+).*?"url"\s*:\s*"([^"]+)".*?"title"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)

    mangaPattern.findAll(jsonString).forEach { match ->
      val source = match.groupValues[1].toLongOrNull() ?: 0
      val url = match.groupValues[2]
      val title = match.groupValues[3]
      mangaList.add(BackupManga(source = source, url = url, title = title))
    }

    logger.info { "Parsed ${mangaList.size} manga from JSON" }
    return Backup(backupManga = mangaList)
  }

  private fun loadExistingUrls(followFile: Path): Set<String> {
    if (!Files.exists(followFile)) {
      return emptySet()
    }

    return try {
      Files
        .readAllLines(followFile)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()
    } catch (e: Exception) {
      logger.warn(e) { "Could not read existing follow.txt" }
      emptySet()
    }
  }

  private fun appendUrlsToFollowFile(
    followFile: Path,
    urls: List<String>,
  ) {
    val sb = StringBuilder()

    // Check if file exists and ensure it ends with newline
    if (Files.exists(followFile)) {
      val content = Files.readString(followFile)
      if (content.isNotEmpty() && !content.endsWith("\n")) {
        sb.append("\n")
      }
    } else {
      // Create header for new file
      sb.append("# MangaDex URLs to follow\n")
      sb.append("# Imported from Tachiyomi backup\n")
    }

    // Add all URLs, each on its own line
    urls.forEach { url ->
      sb.append(url)
      sb.append("\n")
    }

    // Write all at once
    Files.writeString(
      followFile,
      sb.toString(),
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND,
    )

    logger.info { "Wrote ${urls.size} URLs to follow.txt" }
  }

  /**
   * Converts a Tachiyomi manga URL to a full MangaDex URL.
   * Tachiyomi stores URLs in format: /title/{uuid}
   */
  private fun convertToMangaDexUrl(tachiyomiUrl: String): String? {
    // UUID pattern: 8-4-4-4-12 hex digits
    val uuidPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", RegexOption.IGNORE_CASE)
    val uuid = uuidPattern.find(tachiyomiUrl)?.value

    return uuid?.let { "https://mangadex.org/title/$it" }
  }
}

// === Protobuf Data Classes for Tachiyomi/Mihon Backup ===
// Based on: https://github.com/mihonapp/mihon/tree/main/app/src/main/java/eu/kanade/tachiyomi/data/backup/models

@Serializable
data class Backup(
  @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
  @ProtoNumber(2) val backupCategories: List<BackupCategory> = emptyList(),
  @ProtoNumber(101) val backupSources: List<BackupSource> = emptyList(),
)

@Serializable
data class BackupManga(
  @ProtoNumber(1) val source: Long = 0,
  @ProtoNumber(2) val url: String = "",
  @ProtoNumber(3) val title: String = "",
  @ProtoNumber(4) val artist: String = "",
  @ProtoNumber(5) val author: String = "",
  @ProtoNumber(6) val description: String = "",
  @ProtoNumber(7) val genre: List<String> = emptyList(),
  @ProtoNumber(8) val status: Int = 0,
  @ProtoNumber(9) val thumbnailUrl: String = "",
  @ProtoNumber(13) val dateAdded: Long = 0,
  @ProtoNumber(14) val viewer: Int = 0,
  @ProtoNumber(16) val chapters: List<BackupChapter> = emptyList(),
  @ProtoNumber(17) val categories: List<Long> = emptyList(),
  @ProtoNumber(18) val tracking: List<BackupTracking> = emptyList(),
  @ProtoNumber(100) val favorite: Boolean = false,
  @ProtoNumber(101) val chapterFlags: Int = 0,
  @ProtoNumber(102) val brokenHistory: List<BrokenBackupHistory> = emptyList(),
  @ProtoNumber(103) val viewer_flags: Int = 0,
  @ProtoNumber(104) val history: List<BackupHistory> = emptyList(),
  @ProtoNumber(105) val updateStrategy: Int = 0,
  @ProtoNumber(106) val lastModifiedAt: Long = 0,
  @ProtoNumber(107) val favoriteModifiedAt: Long = 0,
  @ProtoNumber(108) val excludedScanlators: List<String> = emptyList(),
  @ProtoNumber(109) val version: Long = 0,
  @ProtoNumber(110) val notes: String = "",
)

@Serializable
data class BackupCategory(
  @ProtoNumber(1) val name: String = "",
  @ProtoNumber(2) val order: Long = 0,
  @ProtoNumber(100) val flags: Long = 0,
)

@Serializable
data class BackupSource(
  @ProtoNumber(1) val name: String = "",
  @ProtoNumber(2) val sourceId: Long = 0,
)

@Serializable
data class BackupChapter(
  @ProtoNumber(1) val url: String = "",
  @ProtoNumber(2) val name: String = "",
  @ProtoNumber(3) val scanlator: String = "",
  @ProtoNumber(4) val read: Boolean = false,
  @ProtoNumber(5) val bookmark: Boolean = false,
  @ProtoNumber(6) val lastPageRead: Long = 0,
  @ProtoNumber(7) val dateFetch: Long = 0,
  @ProtoNumber(8) val dateUpload: Long = 0,
  @ProtoNumber(9) val chapterNumber: Float = 0f,
  @ProtoNumber(10) val sourceOrder: Long = 0,
  @ProtoNumber(11) val lastModifiedAt: Long = 0,
  @ProtoNumber(12) val version: Long = 0,
)

@Serializable
data class BackupTracking(
  @ProtoNumber(1) val syncId: Int = 0,
  @ProtoNumber(2) val libraryId: Long = 0,
  @ProtoNumber(3) val mediaId: Long = 0,
  @ProtoNumber(4) val trackingUrl: String = "",
  @ProtoNumber(5) val title: String = "",
  @ProtoNumber(6) val lastChapterRead: Float = 0f,
  @ProtoNumber(7) val totalChapters: Int = 0,
  @ProtoNumber(8) val score: Float = 0f,
  @ProtoNumber(9) val status: Int = 0,
  @ProtoNumber(10) val startedReadingDate: Long = 0,
  @ProtoNumber(11) val finishedReadingDate: Long = 0,
  @ProtoNumber(100) val mediaIdLong: Long = 0,
)

@Serializable
data class BackupHistory(
  @ProtoNumber(1) val url: String = "",
  @ProtoNumber(2) val lastRead: Long = 0,
  @ProtoNumber(3) val readDuration: Long = 0,
)

@Serializable
data class BrokenBackupHistory(
  @ProtoNumber(0) val url: String = "",
  @ProtoNumber(1) val lastRead: Long = 0,
  @ProtoNumber(2) val readDuration: Long = 0,
)

data class TachiyomiImportResult(
  val totalInBackup: Int,
  val mangaDexCount: Int,
  val imported: List<String>,
  val skipped: List<String>,
  val errors: List<String>,
) {
  val successCount: Int get() = imported.size
  val hasErrors: Boolean get() = errors.isNotEmpty()
}
