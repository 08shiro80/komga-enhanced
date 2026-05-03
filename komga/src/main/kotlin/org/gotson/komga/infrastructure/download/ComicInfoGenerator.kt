package org.gotson.komga.infrastructure.download

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val logger = KotlinLogging.logger {}

@Component
class ComicInfoGenerator {
  fun generateComicInfoXml(
    mangaInfo: MangaInfo,
    chapterInfo: ChapterInfo?,
    chapterUrl: String? = null,
  ): String {
    val seriesTitle = mangaInfo.title.escapeXml()
    val author = mangaInfo.author?.escapeXml() ?: ""
    val description = mangaInfo.description?.escapeXml() ?: ""
    val genres = mangaInfo.genres.joinToString(", ") { it.escapeXml() }
    val tags = mangaInfo.tags.joinToString(", ") { it.escapeXml() }
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
  ${if (tags.isNotBlank()) "<Tags>$tags</Tags>" else ""}
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
    val tempFile = cbzPath.resolveSibling("${cbzPath.fileName}.comicinfo.tmp")
    try {
      val allEntries = mutableListOf<Pair<ZipEntry, ByteArray>>()

      ZipInputStream(Files.newInputStream(cbzPath)).use { zipIn ->
        var entry = zipIn.nextEntry
        while (entry != null) {
          if (entry.name != "ComicInfo.xml") {
            allEntries.add(entry to zipIn.readBytes())
          }
          entry = zipIn.nextEntry
        }
      }

      ZipOutputStream(Files.newOutputStream(tempFile)).use { zipOut ->
        zipOut.setComment(zipComment)
        zipOut.putNextEntry(ZipEntry("ComicInfo.xml"))
        zipOut.write(comicInfoXml.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        for ((entry, data) in allEntries) {
          zipOut.putNextEntry(ZipEntry(entry.name))
          zipOut.write(data)
          zipOut.closeEntry()
        }
      }

      Files.move(tempFile, cbzPath, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(tempFile)
    }
    verifyZipComment(cbzPath)
  }

  fun injectComicInfoWithRetry(
    cbzPath: Path,
    comicInfoXml: String,
    zipComment: String,
    maxRetries: Int = 5,
    retryDelayMs: Long = 1000L,
  ) {
    for (attempt in 1..maxRetries) {
      val tempFile = cbzPath.resolveSibling("${cbzPath.fileName}.tmp")
      try {
        try {
          val allEntries = mutableListOf<Pair<ZipEntry, ByteArray>>()

          ZipInputStream(Files.newInputStream(cbzPath)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
              if (entry.name != "ComicInfo.xml") {
                allEntries.add(entry to zipIn.readBytes())
              }
              entry = zipIn.nextEntry
            }
          }

          ZipOutputStream(Files.newOutputStream(tempFile)).use { zipOut ->
            zipOut.setComment(zipComment)
            val writtenEntries = mutableSetOf<String>()

            zipOut.putNextEntry(ZipEntry("ComicInfo.xml"))
            zipOut.write(comicInfoXml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
            writtenEntries.add("ComicInfo.xml")

            for ((entry, data) in allEntries) {
              if (entry.name !in writtenEntries) {
                writtenEntries.add(entry.name)
                zipOut.putNextEntry(ZipEntry(entry.name))
                zipOut.write(data)
                zipOut.closeEntry()
              }
            }
          }

          Files.move(tempFile, cbzPath, StandardCopyOption.REPLACE_EXISTING)
          verifyZipComment(cbzPath)
          return
        } finally {
          Files.deleteIfExists(tempFile)
        }
      } catch (e: java.nio.file.FileSystemException) {
        if (attempt < maxRetries) {
          logger.debug { "File locked, retrying in ${retryDelayMs}ms (attempt $attempt/$maxRetries): ${cbzPath.fileName}" }
          Thread.sleep(retryDelayMs * attempt)
        } else {
          logger.warn(e) { "Failed to add ComicInfo.xml to ${cbzPath.fileName} after $maxRetries retries" }
        }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to add ComicInfo.xml to ${cbzPath.fileName}" }
        return
      }
    }
  }

  fun hasComicInfoXml(cbzFile: File): Boolean {
    try {
      ZipInputStream(cbzFile.inputStream().buffered()).use { zipIn ->
        var entry = zipIn.nextEntry
        while (entry != null) {
          if (entry.name == "ComicInfo.xml") return true
          entry = zipIn.nextEntry
        }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to check ComicInfo.xml in ${cbzFile.name}" }
      return false
    }
    return false
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
