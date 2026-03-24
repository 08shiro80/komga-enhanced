package org.gotson.komga.infrastructure.download

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

@Component
class ChapterMatcher {
  companion object {
    val cbzUuidRegex = """[\[\(]([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})[\]\)]""".toRegex()
    private val chapterNumCRegex = Regex("""^c(\d+(?:\.\d+)?)""")
    private val chapterNumChRegex = Regex("""^ch\.?\s*(\d+(?:\.\d+)?)""")
    private val zipCommentUuidRegex = Regex("Chapter UUID:\\s*([0-9a-f-]+)")
    private val comicInfoWebRegex = Regex("<Web>(.+?)</Web>")
    private val volumePrefixRegex = Regex("^v\\d+ .+")
    private val bracketGroupRegex = Regex("\\[(.+?)]$")
    private val scanlationGroupRegex = """\[([^\]]+)\]\s*$""".toRegex()
  }

  fun extractChapterId(cbzPath: Path): String? {
    val filename = cbzPath.fileName.toString()
    return cbzUuidRegex.find(filename)?.groupValues?.get(1)
  }

  fun extractChapterNumberFromFilename(filename: String): String? {
    val name = filename.substringBeforeLast('.').lowercase()
    val match =
      chapterNumCRegex.find(name)
        ?: chapterNumChRegex.find(name)
    val raw = match?.groupValues?.get(1) ?: return null
    return try {
      val num = raw.toDouble()
      if (num == num.toLong().toDouble()) num.toLong().toString() else raw
    } catch (e: NumberFormatException) {
      logger.debug(e) { "Could not parse chapter number: $raw" }
      raw
    }
  }

  fun extractChapterNumFromFilename(nameLower: String): String? {
    val cMatch = chapterNumCRegex.find(nameLower)
    if (cMatch != null) return cMatch.groupValues[1]
    val chMatch = chapterNumChRegex.find(nameLower)
    if (chMatch != null) return chMatch.groupValues[1]
    return null
  }

  fun padChapterNumber(chapterNumStr: String): String =
    try {
      val num = chapterNumStr.toDouble()
      if (num == num.toLong().toDouble()) {
        String.format("%03d", num.toLong())
      } else {
        val intPart = num.toLong()
        val decimalPart = chapterNumStr.substringAfter(".", "")
        String.format("%03d.%s", intPart, decimalPart)
      }
    } catch (e: NumberFormatException) {
      logger.debug(e) { "Could not pad chapter number: $chapterNumStr" }
      chapterNumStr
    }

  fun normalizeDoubleBracketFilenames(dir: File) {
    val cbzFiles =
      dir
        .listFiles()
        ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
        ?: return

    for (file in cbzFiles) {
      val name = file.nameWithoutExtension
      if (name.contains("[[") || name.contains("]]")) {
        val normalized =
          name
            .replace(Regex("""\[\['?"""), "[")
            .replace(Regex("""'?\]\]"""), "]")
        val newFile = File(dir, "$normalized.cbz")
        if (!newFile.exists() && normalized != name) {
          Files.move(file.toPath(), newFile.toPath())
          logger.debug { "Normalized filename: ${file.name} -> $normalized.cbz" }
        }
      }
    }
  }

  fun extractUrlFromZipComment(cbzFile: File): String? =
    try {
      java.util.zip.ZipFile(cbzFile).use { zip ->
        val comment = zip.comment ?: return null
        val uuid =
          zipCommentUuidRegex
            .find(comment)
            ?.groupValues
            ?.get(1)
            ?: return null
        "https://mangadex.org/chapter/$uuid"
      }
    } catch (e: Exception) {
      logger.debug(e) { "Failed to extract URL from ZIP comment: ${cbzFile.name}" }
      null
    }

  fun extractChapterUrlsFromCbzFiles(destDir: File): Set<String> {
    val urls = mutableSetOf<String>()
    val cbzFiles =
      destDir
        .listFiles()
        ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
        ?: return urls

    for (cbzFile in cbzFiles) {
      try {
        val urlFromComment = extractUrlFromZipComment(cbzFile)
        if (urlFromComment != null) {
          urls.add(urlFromComment)
          continue
        }
        ZipInputStream(cbzFile.inputStream().buffered()).use { zipIn ->
          var entry = zipIn.nextEntry
          while (entry != null) {
            if (entry.name == "ComicInfo.xml") {
              val xml = zipIn.readBytes().toString(Charsets.UTF_8)
              val match = comicInfoWebRegex.find(xml)
              if (match != null) {
                val url =
                  match.groupValues[1]
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                if (url.contains("mangadex.org/chapter/")) {
                  urls.add(url)
                }
              }
              break
            }
            entry = zipIn.nextEntry
          }
        }
      } catch (e: Exception) {
        logger.debug { "Failed to read chapter URL from ${cbzFile.name}: ${e.message}" }
      }
    }
    return urls
  }

  fun findSameGroupDuplicates(chapters: List<ChapterDownloadInfo>): List<ChapterDownloadInfo> {
    val duplicates = mutableListOf<ChapterDownloadInfo>()
    chapters
      .filter { it.scanlationGroup != null }
      .groupBy { Pair(it.chapterNumber, it.scanlationGroup) }
      .values
      .filter { it.size > 1 }
      .forEach { group ->
        val newest = group.maxByOrNull { it.publishDate ?: "" }
        group.filter { it !== newest }.forEach { duplicates.add(it) }
      }
    return duplicates
  }

  fun matchesChapterNumber(
    name: String,
    paddedChapter: String,
    chapterStr: String,
  ): Boolean {
    val chapterPart =
      if (volumePrefixRegex.matches(name)) name.substringAfter(" ") else name
    return chapterPart.startsWith("c$paddedChapter ") || chapterPart == "c$paddedChapter" ||
      chapterPart.startsWith("c$chapterStr ") || chapterPart == "c$chapterStr" ||
      chapterPart.startsWith("ch. $paddedChapter ") || chapterPart.startsWith("ch. $paddedChapter-") || chapterPart == "ch. $paddedChapter"
  }

  fun matchesChapterAndGroup(
    name: String,
    paddedChapter: String,
    chapterStr: String,
    groupName: String?,
  ): Boolean {
    if (!matchesChapterNumber(name, paddedChapter, chapterStr)) return false
    if (groupName == null) return true
    val bracketGroup =
      bracketGroupRegex
        .find(name)
        ?.groupValues
        ?.get(1)
        ?.lowercase()
    return bracketGroup != null && bracketGroup == groupName
  }

  fun extractScanlationGroup(fileName: String): String? =
    scanlationGroupRegex
      .find(fileName)
      ?.groupValues
      ?.get(1)
      ?.trim()
}
