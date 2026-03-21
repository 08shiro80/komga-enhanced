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
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

@Service
class ChapterUrlImporter(
  private val chapterUrlRepository: ChapterUrlRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: org.gotson.komga.domain.persistence.SeriesMetadataRepository,
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
    val total = allSeries.size
    logger.info { "Chapter URL import: scanning $total series" }

    for ((index, series) in allSeries.withIndex()) {
      try {
        if ((index + 1) % 25 == 0 || index == 0) {
          logger.info { "Chapter URL import: ${index + 1}/$total — ${series.name}" }
        }
        syncMangaDexUuid(series)
        val result = importFromSeriesPath(series.path, series.id)
        if (result.imported > 0 || result.totalInFile > 0) {
          results.add(result)
        }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to import chapter URLs for series ${series.name}" }
      }
    }
    logger.info { "Chapter URL import: done ($total series, ${results.sumOf { it.imported }} imported)" }

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

    if (existingUrls.isNotEmpty() && existingUrls.size >= cbzFiles.size) {
      return ChapterUrlImportResult(
        seriesId = resolvedSeriesId,
        totalInFile = existingUrls.size,
        imported = 0,
        skippedDuplicates = existingUrls.size,
      )
    }

    var totalFound = 0
    var imported = 0
    var skipped = 0

    for (cbzFile in cbzFiles) {
      val zipComment = extractZipCommentData(cbzFile)
      val comicInfo = if (zipComment == null) extractComicInfo(cbzFile) else null
      val url =
        zipComment?.url
          ?: comicInfo?.url?.takeIf { it.contains("mangadex.org/chapter/") }
          ?: continue
      totalFound++

      if (url in existingUrls) {
        skipped++
        continue
      }

      try {
        val chapterUrl =
          ChapterUrl(
            id = TsidCreator.getTsid256().toString(),
            seriesId = resolvedSeriesId,
            url = url,
            chapter = zipComment?.chapter ?: comicInfo?.chapterNumber ?: 0.0,
            volume = zipComment?.volume ?: comicInfo?.volume,
            title = comicInfo?.title,
            lang = comicInfo?.language ?: "en",
            downloadedAt = LocalDateTime.now(),
            source = "comicinfo-import",
            scanlationGroup = comicInfo?.scanlationGroup,
          )
        chapterUrlRepository.insert(chapterUrl)
        imported++
      } catch (e: Exception) {
        logger.debug { "Failed to insert chapter URL $url for series $resolvedSeriesId: ${e.message}" }
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

  private data class ZipCommentData(
    val url: String,
    val chapter: Double?,
    val volume: Int?,
  )

  private val chapterUuidRegex = Regex("Chapter UUID:\\s*([0-9a-f-]+)")
  private val chapterNumberCommentRegex = Regex("Chapter:\\s*([\\d.]+)")
  private val volumeCommentRegex = Regex("Volume:\\s*(\\d+)")

  private fun extractZipCommentData(cbzFile: File): ZipCommentData? {
    try {
      ZipFile(cbzFile).use { zf ->
        val comment = zf.comment ?: return null
        val chapterUuid =
          chapterUuidRegex.find(comment)?.groupValues?.get(1) ?: return null
        return ZipCommentData(
          url = "https://mangadex.org/chapter/$chapterUuid",
          chapter =
            chapterNumberCommentRegex
              .find(comment)
              ?.groupValues
              ?.get(1)
              ?.toDoubleOrNull(),
          volume =
            volumeCommentRegex
              .find(comment)
              ?.groupValues
              ?.get(1)
              ?.toIntOrNull(),
        )
      }
    } catch (_: Exception) {
      return null
    }
  }

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
    var web: String? = null
    var number: String? = null
    var volume: String? = null
    var title: String? = null
    var language: String? = null
    var translator: String? = null

    for (line in xml.lineSequence()) {
      if (web == null) WEB_REGEX.find(line)?.let { web = it.groupValues[1].unescapeXml() }
      if (number == null) NUMBER_REGEX.find(line)?.let { number = it.groupValues[1] }
      if (volume == null) VOLUME_REGEX.find(line)?.let { volume = it.groupValues[1] }
      if (title == null) TITLE_REGEX.find(line)?.let { title = it.groupValues[1].unescapeXml() }
      if (language == null) LANGUAGE_REGEX.find(line)?.let { language = it.groupValues[1] }
      if (translator == null) TRANSLATOR_REGEX.find(line)?.let { translator = it.groupValues[1].unescapeXml() }
      if (web != null && number != null && volume != null && title != null && language != null && translator != null) break
    }

    return ComicInfoData(
      url = web,
      chapterNumber = number?.toDoubleOrNull(),
      volume = volume?.toIntOrNull(),
      title = title,
      language = language,
      scanlationGroup = translator,
    )
  }

  private fun String.unescapeXml() =
    this
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")

  private val mangaDexTitleRegex = Regex("mangadex\\.org/title/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
  private val uuidRegex = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")

  fun syncMangaDexUuidForSeries(series: org.gotson.komga.domain.model.Series) = syncMangaDexUuid(series)

  private fun syncMangaDexUuid(series: org.gotson.komga.domain.model.Series) {
    val uuid =
      extractUuidFromSeriesJson(series)
        ?: extractUuidFromFolderName(series)
        ?: extractUuidFromMetadataLinks(series)
        ?: return

    val existing = seriesRepository.findByMangaDexUuid(uuid)
    if (existing?.id == series.id) return
    if (existing != null) {
      logger.debug { "mangaDexUuid $uuid already assigned to series ${existing.id}, skipping ${series.id}" }
      return
    }
    seriesRepository.update(series.copy(mangaDexUuid = uuid), updateModifiedTime = false)
    logger.info { "Set mangaDexUuid=$uuid on series ${series.id} (${series.name})" }
  }

  private fun extractUuidFromSeriesJson(series: org.gotson.komga.domain.model.Series): String? {
    val seriesJson = series.path.resolve("series.json").toFile()
    if (!seriesJson.exists()) return null
    return try {
      val json = objectMapper.readValue(seriesJson, Map::class.java)
      val metadata = json["metadata"] as? Map<*, *> ?: return null
      val comicId = metadata["comicid"] as? String
      if (comicId.isNullOrBlank()) null else comicId
    } catch (_: Exception) {
      null
    }
  }

  private fun extractUuidFromFolderName(series: org.gotson.komga.domain.model.Series): String? {
    val folderName = series.path.toFile().name
    return if (uuidRegex.matches(folderName)) folderName else null
  }

  private fun extractUuidFromMetadataLinks(series: org.gotson.komga.domain.model.Series): String? =
    try {
      val metadata = seriesMetadataRepository.findByIdOrNull(series.id)
      metadata
        ?.links
        ?.firstNotNullOfOrNull { link ->
          mangaDexTitleRegex.find(link.url.toString())?.groupValues?.get(1)
        }
    } catch (_: Exception) {
      null
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
