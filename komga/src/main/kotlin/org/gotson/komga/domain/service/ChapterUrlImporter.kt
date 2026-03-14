package org.gotson.komga.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.ChapterUrl
import org.gotson.komga.domain.model.ChapterUrlImportResult
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

@Service
class ChapterUrlImporter(
  private val chapterUrlRepository: ChapterUrlRepository,
  private val seriesRepository: SeriesRepository,
  private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) {
  companion object {
    const val TRACKER_FILENAME = ".chapter-urls.json"
    private val WEB_REGEX = Regex("<Web>(.+?)</Web>")
    private val NUMBER_REGEX = Regex("<Number>(.+?)</Number>")
    private val VOLUME_REGEX = Regex("<Volume>(.+?)</Volume>")
    private val TITLE_REGEX = Regex("<Title>(.+?)</Title>")
    private val LANGUAGE_REGEX = Regex("<LanguageISO>(.+?)</LanguageISO>")
    private val TRANSLATOR_REGEX = Regex("<Translator>(.+?)</Translator>")
  }

  fun scanAndImportLibrary(
    libraryPath: Path,
    libraryId: String,
  ): List<ChapterUrlImportResult> {
    cleanupTrackerFiles(libraryPath)

    val allSeries = seriesRepository.findAllByLibraryId(libraryId)
    val results = mutableListOf<ChapterUrlImportResult>()

    for (series in allSeries) {
      try {
        syncMangaDexUuid(series)
        val result = importFromSeriesPath(series.path, series.id)
        if (result.imported > 0 || result.totalInFile > 0) {
          results.add(result)
        }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to import chapter URLs for series ${series.name}" }
      }
    }

    return results
  }

  fun importFromSeriesPath(
    seriesPath: Path,
    seriesId: String? = null,
  ): ChapterUrlImportResult {
    val dir = seriesPath.toFile()
    if (!dir.isDirectory) {
      return ChapterUrlImportResult(seriesId = seriesId ?: "", totalInFile = 0, imported = 0, skippedDuplicates = 0)
    }

    val resolvedSeriesId = seriesId ?: return ChapterUrlImportResult(seriesId = "", totalInFile = 0, imported = 0, skippedDuplicates = 0)

    val existingUrls = chapterUrlRepository.findUrlsBySeriesId(resolvedSeriesId).toSet()
    val cbzFiles =
      dir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "cbz" } ?: emptyList()

    var totalFound = 0
    var imported = 0
    var skipped = 0

    for (cbzFile in cbzFiles) {
      val info = extractComicInfo(cbzFile) ?: continue
      if (info.url == null || !info.url.contains("mangadex.org/chapter/")) continue

      totalFound++

      if (info.url in existingUrls) {
        skipped++
        continue
      }

      try {
        val chapterUrl =
          ChapterUrl(
            id = TsidCreator.getTsid256().toString(),
            seriesId = resolvedSeriesId,
            url = info.url,
            chapter = info.chapterNumber ?: 0.0,
            volume = info.volume,
            title = info.title,
            lang = info.language ?: "en",
            downloadedAt = LocalDateTime.now(),
            source = "comicinfo-import",
            scanlationGroup = info.scanlationGroup,
          )
        chapterUrlRepository.insert(chapterUrl)
        imported++
      } catch (e: Exception) {
        logger.debug { "Failed to insert chapter URL ${info.url} for series $resolvedSeriesId: ${e.message}" }
      }
    }

    return ChapterUrlImportResult(
      seriesId = resolvedSeriesId,
      totalInFile = totalFound,
      imported = imported,
      skippedDuplicates = skipped,
    )
  }

  private data class ComicInfoData(
    val url: String?,
    val chapterNumber: Double?,
    val volume: Int?,
    val title: String?,
    val language: String?,
    val scanlationGroup: String?,
  )

  private fun extractComicInfo(cbzFile: File): ComicInfoData? {
    try {
      ZipInputStream(cbzFile.inputStream().buffered()).use { zipIn ->
        var entry = zipIn.nextEntry
        while (entry != null) {
          if (entry.name == "ComicInfo.xml") {
            val xml = zipIn.readBytes().toString(Charsets.UTF_8)
            return parseComicInfoXml(xml)
          }
          entry = zipIn.nextEntry
        }
      }
    } catch (e: Exception) {
      logger.debug { "Failed to read ComicInfo.xml from ${cbzFile.name}: ${e.message}" }
    }
    return null
  }

  private fun parseComicInfoXml(xml: String): ComicInfoData {
    val url =
      WEB_REGEX
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.unescapeXml()
    val chapterNumber =
      NUMBER_REGEX
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.toDoubleOrNull()
    val volume =
      VOLUME_REGEX
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
    val title =
      TITLE_REGEX
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.unescapeXml()
    val language =
      LANGUAGE_REGEX
        .find(xml)
        ?.groupValues
        ?.get(1)
    val scanlationGroup =
      TRANSLATOR_REGEX
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.unescapeXml()

    return ComicInfoData(
      url = url,
      chapterNumber = chapterNumber,
      volume = volume,
      title = title,
      language = language,
      scanlationGroup = scanlationGroup,
    )
  }

  private fun String.unescapeXml() =
    this
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")

  private fun syncMangaDexUuid(series: org.gotson.komga.domain.model.Series) {
    val seriesJson = series.path.resolve("series.json").toFile()
    if (!seriesJson.exists()) return
    try {
      val json = objectMapper.readValue(seriesJson, Map::class.java)
      val metadata = json["metadata"] as? Map<*, *> ?: return
      val comicId = metadata["comicid"] as? String ?: return
      if (comicId.isBlank()) return
      val existing = seriesRepository.findByMangaDexUuid(comicId)
      if (existing?.id == series.id) return
      if (existing != null) {
        logger.debug { "mangaDexUuid $comicId already assigned to series ${existing.id}, skipping ${series.id}" }
        return
      }
      seriesRepository.update(series.copy(mangaDexUuid = comicId), updateModifiedTime = false)
      logger.info { "Set mangaDexUuid=$comicId on series ${series.id} (${series.name}) from series.json" }
    } catch (e: Exception) {
      logger.debug { "Failed to read mangaDexUuid from series.json for ${series.name}: ${e.message}" }
    }
  }

  private fun cleanupTrackerFiles(libraryPath: Path) {
    try {
      Files.walk(libraryPath, 2).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .filter { it.fileName.toString() == TRACKER_FILENAME }
          .forEach { trackerFile ->
            try {
              Files.delete(trackerFile)
              logger.info { "Cleaned up legacy tracker file: $trackerFile" }
            } catch (e: Exception) {
              logger.warn(e) { "Failed to delete legacy tracker file: $trackerFile" }
            }
          }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Error scanning for legacy tracker files: $libraryPath" }
    }
  }
}
