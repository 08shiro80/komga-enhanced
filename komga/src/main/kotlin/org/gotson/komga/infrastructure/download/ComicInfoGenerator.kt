package org.gotson.komga.infrastructure.download

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val logger = KotlinLogging.logger {}

@Component
class ComicInfoGenerator {
  companion object {
    private val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".heic")
  }

  private fun copyEntry(
    zin: ZipFile,
    source: ZipEntry,
    zout: ZipOutputStream,
    useStored: Boolean,
  ) {
    val isImage = IMAGE_EXTS.any { source.name.lowercase().endsWith(it) }
    val out = ZipEntry(source.name)
    if (useStored && isImage && source.size >= 0 && source.crc >= 0) {
      out.method = ZipEntry.STORED
      out.size = source.size
      out.compressedSize = source.size
      out.crc = source.crc
    }
    zout.putNextEntry(out)
    zin.getInputStream(source).use { it.copyTo(zout) }
    zout.closeEntry()
  }

  fun generateComicInfoXml(
    mangaInfo: MangaInfo,
    chapterInfo: ChapterInfo?,
    chapterUrl: String? = null,
  ): String {
    val seriesTitle = mangaInfo.title.escapeXml()
    val author = mangaInfo.author?.escapeXml() ?: ""
    val description = mangaInfo.description?.escapeXml() ?: ""
    val genres = mangaInfo.genres.joinToString(", ") { it.escapeXml() }
    val chapterTitle = chapterInfo?.chapterTitle?.escapeXml() ?: ""
    val chapterNumber = chapterInfo?.chapterNumber
    val volume = chapterInfo?.volume
    val scanlationGroup =
      chapterInfo?.scanlationGroup?.escapeXml()
        ?: mangaInfo.scanlationGroup?.escapeXml()
        ?: ""
    val pageCount = chapterInfo?.pages ?: 0
    val publishDate = chapterInfo?.publishDate
    val language = chapterInfo?.language ?: "en"
    val mangaType = if (language == "ja") "YesAndRightToLeft" else "Yes"

    return """<?xml version="1.0"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <Title>$chapterTitle</Title>
  <Series>$seriesTitle</Series>
  ${if (chapterNumber != null) "<Number>$chapterNumber</Number>" else ""}
  ${if (volume != null) "<Volume>$volume</Volume>" else ""}
  ${if (description.isNotBlank()) "<Summary>$description</Summary>" else ""}
  ${
      if (publishDate != null && publishDate.length >= 4) {
        "<Year>${publishDate.substring(0, 4)}</Year>"
      } else if (mangaInfo.year != null) {
        "<Year>${mangaInfo.year}</Year>"
      } else {
        ""
      }
    }
  ${if (publishDate != null && publishDate.length >= 7) "<Month>${publishDate.substring(5, 7)}</Month>" else ""}
  ${if (publishDate != null && publishDate.length >= 10) "<Day>${publishDate.substring(8, 10)}</Day>" else ""}
  <Writer>$author</Writer>
  <Translator>$scanlationGroup</Translator>
  <Publisher>${mangaInfo.publisher.escapeXml()}</Publisher>
  ${if (genres.isNotBlank()) "<Genre>$genres</Genre>" else ""}
  <Web>${(chapterUrl ?: mangaInfo.sourceUrl ?: "").escapeXml()}</Web>
  ${if (pageCount > 0) "<PageCount>$pageCount</PageCount>" else ""}
  <LanguageISO>$language</LanguageISO>
  <Manga>$mangaType</Manga>
  ${if (mangaInfo.publicationDemographic != null) "<AgeRating>${mapDemographicToAgeRating(mangaInfo.publicationDemographic)}</AgeRating>" else ""}
</ComicInfo>"""
  }

  fun generateZipComment(
    mangaInfo: MangaInfo,
    chapterInfo: ChapterInfo?,
    chapterId: String? = null,
  ): String {
    val lines = mutableListOf<String>()
    if (mangaInfo.mangaDexId != null) lines.add("Title UUID: ${mangaInfo.mangaDexId}")
    if (chapterId != null) lines.add("Chapter UUID: $chapterId")
    if (chapterInfo?.chapterNumber != null) lines.add("Chapter: ${chapterInfo.chapterNumber}")
    if (chapterInfo?.volume != null) lines.add("Volume: ${chapterInfo.volume}")
    return lines.joinToString("\n")
  }

  fun injectComicInfo(
    cbzPath: Path,
    comicInfoXml: String,
    zipComment: String,
  ) {
    try {
      writeInjected(cbzPath, comicInfoXml, zipComment, useStored = true)
    } catch (e: java.util.zip.ZipException) {
      logger.warn(e) { "STORED inject failed for ${cbzPath.fileName}, retrying DEFLATED" }
      writeInjected(cbzPath, comicInfoXml, zipComment, useStored = false)
    }
    verifyZipComment(cbzPath)
  }

  private fun writeInjected(
    cbzPath: Path,
    comicInfoXml: String,
    zipComment: String,
    useStored: Boolean,
  ) {
    ZipFile(cbzPath.toFile()).use { zin ->
      org.gotson.komga.infrastructure.util.CbzSafeWriter.safelyReplace(cbzPath, releaseTarget = { zin.close() }) { outStream ->
        ZipOutputStream(outStream).use { zipOut ->
          zipOut.setComment(zipComment)
          zipOut.putNextEntry(ZipEntry("ComicInfo.xml"))
          zipOut.write(comicInfoXml.toByteArray(Charsets.UTF_8))
          zipOut.closeEntry()
          for (entry in zin.entries()) {
            if (entry.name == "ComicInfo.xml") continue
            copyEntry(zin, entry, zipOut, useStored)
          }
        }
      }
    }
  }

  fun injectComicInfoWithRetry(
    cbzPath: Path,
    comicInfoXml: String,
    zipComment: String,
    maxRetries: Int = 5,
    retryDelayMs: Long = 1000L,
  ) {
    var useStored = true
    for (attempt in 1..maxRetries) {
      try {
        ZipFile(cbzPath.toFile()).use { zin ->
          org.gotson.komga.infrastructure.util.CbzSafeWriter.safelyReplace(cbzPath, releaseTarget = { zin.close() }) { outStream ->
            ZipOutputStream(outStream).use { zipOut ->
              zipOut.setComment(zipComment)
              val writtenEntries = mutableSetOf<String>()
              zipOut.putNextEntry(ZipEntry("ComicInfo.xml"))
              zipOut.write(comicInfoXml.toByteArray(Charsets.UTF_8))
              zipOut.closeEntry()
              writtenEntries.add("ComicInfo.xml")
              for (entry in zin.entries()) {
                if (entry.name in writtenEntries) continue
                writtenEntries.add(entry.name)
                copyEntry(zin, entry, zipOut, useStored)
              }
            }
          }
        }
        verifyZipComment(cbzPath)
        return
      } catch (e: java.nio.file.FileSystemException) {
        if (attempt < maxRetries) {
          logger.debug { "File locked, retrying in ${retryDelayMs}ms (attempt $attempt/$maxRetries): ${cbzPath.fileName}" }
          Thread.sleep(retryDelayMs * attempt)
        } else {
          logger.warn(e) { "Failed to add ComicInfo.xml to ${cbzPath.fileName} after $maxRetries retries" }
        }
      } catch (e: java.util.zip.ZipException) {
        if (useStored) {
          logger.warn(e) { "STORED inject failed for ${cbzPath.fileName}, switching to DEFLATED for next attempt" }
          useStored = false
        } else {
          logger.warn(e) { "DEFLATED inject also failed for ${cbzPath.fileName}, giving up" }
          return
        }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to add ComicInfo.xml to ${cbzPath.fileName}" }
        return
      }
    }
  }

  // Returns the <Number> from the CBZ's ComicInfo.xml, or null if the file has no
  // ComicInfo / no number / is unreadable. Lets resume identify a finished chapter
  // from the source-written metadata instead of relying on the filename convention.
  fun readChapterNumber(cbzFile: File): String? =
    try {
      ZipFile(cbzFile).use { zip ->
        val entry = zip.getEntry("ComicInfo.xml")
        if (entry == null) {
          null
        } else {
          val xml = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
          Regex("<Number>(.*?)</Number>")
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.ifEmpty { null }
        }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to read ComicInfo.xml number from ${cbzFile.name}" }
      null
    }

  // Returns the <Web> value (concatenated href list as written by the source). The
  // repair-comicinfo fix uses this to recognize CBZs that came from a non-MangaDex
  // source (mangabuddy, cubari, ...) and must not be re-injected with MangaDex
  // metadata even under force, otherwise the original source metadata is destroyed.
  fun readWebUrl(cbzFile: File): String? =
    try {
      ZipFile(cbzFile).use { zip ->
        val entry = zip.getEntry("ComicInfo.xml")
        if (entry == null) {
          null
        } else {
          val xml = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
          Regex("<Web>(.*?)</Web>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.ifEmpty { null }
        }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to read ComicInfo.xml Web from ${cbzFile.name}" }
      null
    }

  fun verifyZipComment(cbzPath: Path) {
    try {
      ZipFile(cbzPath.toFile()).use { zf ->
        val comment = zf.comment
        if (comment.isNullOrBlank()) {
          logger.warn { "ZIP comment MISSING after write: ${cbzPath.fileName}" }
        } else {
          logger.debug { "ZIP comment verified (${comment.length} chars): ${cbzPath.fileName}" }
        }
      }
    } catch (e: Exception) {
      logger.warn { "Could not verify ZIP comment for ${cbzPath.fileName}: ${e.message}" }
    }
  }

  private fun mapDemographicToAgeRating(demographic: String): String =
    when (demographic.lowercase()) {
      "shounen" -> "Teen"
      "shoujo" -> "Everyone 10+"
      "seinen" -> "Mature 17+"
      "josei" -> "Mature 17+"
      else -> "Unknown"
    }

  private fun String.escapeXml() =
    this
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
}
