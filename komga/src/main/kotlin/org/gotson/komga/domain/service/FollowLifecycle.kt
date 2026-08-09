package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.DuplicateNameException
import org.gotson.komga.domain.model.EntryNotFoundException
import org.gotson.komga.domain.model.Follow
import org.gotson.komga.domain.persistence.FollowRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class FollowLifecycle(
  private val followRepository: FollowRepository,
  private val chapterChecker: ChapterChecker,
  private val downloadExecutor: DownloadExecutor,
  private val libraryRepository: LibraryRepository,
  private val galleryDlWrapper: GalleryDlWrapper,
) {
  fun getAll(libraryId: String): List<Follow> = followRepository.findAllByLibraryId(libraryId)

  fun getBySeries(seriesId: String): List<Follow> = followRepository.findAllBySeriesId(seriesId)

  fun add(
    libraryId: String,
    url: String,
    title: String? = null,
    seriesId: String? = null,
  ): Follow {
    if (followRepository.existsByLibraryIdAndUrl(libraryId, url)) {
      throw DuplicateNameException("URL already in follow list for this library: $url")
    }
    val follow =
      Follow(
        id = UUID.randomUUID().toString(),
        libraryId = libraryId,
        url = url,
        title = title?.takeIf { it.isNotBlank() } ?: resolveTitle(url),
        seriesId = seriesId,
        enabled = true,
        addedAt = LocalDateTime.now(),
      )
    followRepository.insert(follow)
    logger.info { "Added follow entry: ${follow.id} — $url" }
    return follow
  }

  fun addBatch(
    libraryId: String,
    urls: List<String>,
  ): Pair<Int, Int> {
    var added = 0
    var skipped = 0
    for (raw in urls) {
      val url = raw.trim()
      if (url.isEmpty()) continue
      if (followRepository.existsByLibraryIdAndUrl(libraryId, url)) {
        skipped++
        continue
      }
      followRepository.insert(
        Follow(
          id = UUID.randomUUID().toString(),
          libraryId = libraryId,
          url = url,
          addedAt = LocalDateTime.now(),
        ),
      )
      added++
    }
    logger.info { "Batch follow import for library $libraryId: $added added, $skipped skipped" }
    return added to skipped
  }

  fun update(
    id: String,
    title: String? = null,
    enabled: Boolean? = null,
  ): Follow {
    val existing =
      followRepository.findById(id)
        ?: throw EntryNotFoundException("Follow entry not found: $id")
    val updated =
      existing.copy(
        title = title ?: existing.title,
        enabled = enabled ?: existing.enabled,
      )
    followRepository.update(updated)
    return updated
  }

  fun delete(id: String) {
    followRepository.delete(id)
    logger.info { "Deleted follow entry: $id" }
  }

  fun deleteBatch(ids: List<String>) {
    if (ids.isEmpty()) return
    followRepository.deleteByIds(ids)
    logger.info { "Deleted ${ids.size} follow entries" }
  }

  private fun resolveTitle(url: String): String? =
    try {
      val mangaDexId = GalleryDlWrapper.extractMangaDexId(url)
      val raw =
        if (mangaDexId != null) {
          galleryDlWrapper.getMangaMetadata(mangaDexId)?.title
        } else {
          galleryDlWrapper.getChapterInfo(url).title
        }
      raw?.takeIf { it.isNotBlank() && it != "Unknown" }
    } catch (e: Exception) {
      logger.warn(e) { "Could not auto-resolve follow title for $url" }
      null
    }

  fun importFromFollowTxt(libraryId: String): Pair<Int, Int> {
    val library =
      libraryRepository.findByIdOrNull(libraryId)
        ?: throw EntryNotFoundException("Library not found: $libraryId")
    val followFile = library.path.resolve("follow.txt").toFile()
    if (!followFile.exists()) {
      logger.info { "No follow.txt to import for library $libraryId at ${followFile.path}" }
      return 0 to 0
    }
    val urls =
      followFile
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
    logger.info { "Importing ${urls.size} URLs from follow.txt for library $libraryId" }
    return addBatch(libraryId, urls)
  }

  @EventListener(ApplicationReadyEvent::class)
  fun autoImportFollowTxtOnFirstStart() {
    libraryRepository.findAll().forEach { library ->
      try {
        if (followRepository.findAllByLibraryId(library.id).isNotEmpty()) return@forEach
        val (added, _) = importFromFollowTxt(library.id)
        if (added > 0) {
          logger.info { "Auto-imported $added follow.txt URLs for library ${library.id}" }
        }
      } catch (e: Exception) {
        logger.warn(e) { "Auto-import of follow.txt failed for library ${library.id}" }
      }
    }
  }

  fun checkNow(libraryId: String): Int {
    val follows = followRepository.findAllByLibraryId(libraryId).filter { it.enabled }
    if (follows.isEmpty()) return 0

    val seriesIdByUrl = follows.associate { it.url to it.seriesId }
    val summary = chapterChecker.checkUrls(follows.map { it.url })
    var queued = 0
    for (result in summary.results.filter { it.needsDownload }) {
      if (!downloadExecutor.isUrlAlreadyQueued(result.url)) {
        downloadExecutor.createDownload(
          sourceUrl = result.url,
          libraryId = libraryId,
          title = result.title,
          createdBy = "follow-list",
          overrides = seriesIdByUrl[result.url]?.let { ChapterDownloadOverrides(seriesId = it) },
        )
        queued++
      }
    }

    val now = LocalDateTime.now()
    follows.forEach { followRepository.updateLastChecked(it.id, now) }
    logger.info { "Follow check for library $libraryId: queued $queued of ${follows.size} follows" }
    return queued
  }

  @EventListener
  fun onSeriesDeleted(event: DomainEvent.SeriesDeleted) {
    followRepository.clearSeriesId(event.series.id)
  }
}
