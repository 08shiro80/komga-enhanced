package org.gotson.komga.infrastructure.download

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val logger = KotlinLogging.logger {}

@Component
class ChapterMatcher {
  companion object {
    val cbzUuidRegex = """[\[\(]([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})[\]\)]""".toRegex()
    private val chapterNumCRegex = Regex("""^c(\d+(?:\.\d+)?[a-z]?)""")
    private val chapterNumChRegex = Regex("""^ch\.?\s*(\d+(?:\.\d+)?[a-z]?)""")
    private val chapterNumChapterRegex = Regex("""^chapter[\s_]+(\d+(?:\.\d+)?[a-z]?)""")
    private val zipCommentUuidRegex = Regex("Chapter UUID:\\s*([0-9a-f-]+)")
    private val comicInfoWebRegex = Regex("<Web>(.+?)</Web>")
    private val mangadexChapterUrlRegex = Regex("https://mangadex\\.org/chapter/[0-9a-f-]+")
    private val volumePrefixRegex = Regex("^v\\d+ .+")
    private val scanlationGroupRegex = """\[([^\]]+)\]\s*$""".toRegex()
    private val chapterNumericSplitRegex = Regex("""^(\d+(?:\.\d+)?)([^\d.].*)?$""")
  }

  fun extractChapterId(cbzPath: Path): String? {
    val fromFilename = cbzUuidRegex.find(cbzPath.fileName.toString())?.groupValues?.get(1)
    if (fromFilename != null) return fromFilename
    return try {
      java.util.zip.ZipFile(cbzPath.toFile()).use { zip ->
        zip.comment?.let { zipCommentUuidRegex.find(it)?.groupValues?.get(1) }
          ?: run {
            val entry = zip.getEntry("ComicInfo.xml") ?: return@use null
            val xml =
              zip
                .getInputStream(entry)
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)
            comicInfoWebRegex
              .find(xml)
              ?.groupValues
              ?.get(1)
              ?.substringAfterLast("/chapter/", "")
              ?.takeIf { it.isNotBlank() }
          }
      }
    } catch (e: Exception) {
      logger.debug(e) { "Failed to read chapter ID from ${cbzPath.fileName}" }
      null
    }
  }

  fun extractChapterNumberFromFilename(filename: String): String? {
    var name = filename.substringBeforeLast('.').lowercase()
    if (volumePrefixRegex.matches(name)) name = name.substringAfter(" ")
    val match =
      chapterNumCRegex.find(name)
        ?: chapterNumChapterRegex.find(name)
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
    val name = if (volumePrefixRegex.matches(nameLower)) nameLower.substringAfter(" ") else nameLower
    val cMatch = chapterNumCRegex.find(name)
    if (cMatch != null) return cMatch.groupValues[1]
    val chapterMatch = chapterNumChapterRegex.find(name)
    if (chapterMatch != null) return chapterMatch.groupValues[1]
    val chMatch = chapterNumChRegex.find(name)
    if (chMatch != null) return chMatch.groupValues[1]
    return null
  }

  fun padChapterNumber(chapterNumStr: String): String {
    val match = chapterNumericSplitRegex.matchEntire(chapterNumStr) ?: return chapterNumStr
    val numericPart = match.groupValues[1]
    val suffix = match.groupValues[2]
    return try {
      val num = numericPart.toDouble()
      val paddedNumeric =
        if (num == num.toLong().toDouble()) {
          String.format(Locale.ROOT, "%03d", num.toLong())
        } else {
          val intPart = num.toLong()
          val decimalPart = numericPart.substringAfter(".", "")
          String.format(Locale.ROOT, "%03d.%s", intPart, decimalPart)
        }
      paddedNumeric + suffix
    } catch (e: NumberFormatException) {
      logger.debug(e) { "Could not pad chapter number: $chapterNumStr" }
      chapterNumStr
    }
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
      logger.warn(e) { "Failed to extract URL from ZIP comment: ${cbzFile.name}" }
      null
    }

  fun extractChapterUrlsFromCbzFiles(destDir: File): Set<String> =
    extractChapterUrlsPerCbzFile(destDir)
      .flatten()
      .toSet()

  // Per-CBZ chapter URLs (one entry per file, in directory order). Callers that need the aggregate use
  // extractChapterUrlsFromCbzFiles; the deleted-chapters guard needs the per-file view to tell "every CBZ
  // carries an identifiable URL" from "the aggregate count happens to match" (one CBZ with two URLs would
  // otherwise mask one with none, allowing a false "stale" deletion).
  fun extractChapterUrlsPerCbzFile(destDir: File): List<Set<String>> {
    val cbzFiles =
      destDir
        .listFiles()
        ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
        ?: return emptyList()
    return cbzFiles.map { extractUrlsFromCbz(it) }
  }

  private fun extractUrlsFromCbz(cbzFile: File): Set<String> {
    val urls = mutableSetOf<String>()
    try {
      // Collect BOTH the zip-comment UUID and the ComicInfo <Web> chapter URLs: a CBZ can carry a
      // different UUID in each (e.g. re-downloaded from another group), and the tracked CHAPTER_URL
      // may match either. Extracting only one (and skipping the other) causes false "stale" verdicts.
      extractUrlFromZipComment(cbzFile)?.let { urls.add(it) }
      java.util.zip.ZipFile(cbzFile).use { zip ->
        val entry = zip.getEntry("ComicInfo.xml")
        if (entry != null) {
          val xml =
            zip
              .getInputStream(entry)
              .use { it.readBytes() }
              .toString(Charsets.UTF_8)
          val match = comicInfoWebRegex.find(xml)
          if (match != null) {
            val url =
              match.groupValues[1]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
            // <Web> may hold several space-separated URLs (manga-level + one or more chapter URLs);
            // extract each MangaDex chapter URL individually so it matches the tracked chapter URLs.
            mangadexChapterUrlRegex.findAll(url).forEach { urls.add(it.value) }
          }
        }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to read chapter URL from ${cbzFile.name}" }
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

  fun extractScanlationGroup(fileName: String): String? =
    scanlationGroupRegex
      .find(fileName)
      ?.groupValues
      ?.get(1)
      ?.trim()
}
