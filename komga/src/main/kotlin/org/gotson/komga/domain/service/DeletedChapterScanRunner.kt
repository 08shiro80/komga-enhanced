package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Runs the deleted-chapters reconciliation in the background so a whole-library scan does not block
 * the request (which would time out on large libraries) and its result survives the caller navigating
 * away: the UI polls [status]. dryRun=true is the preview (no deletions).
 */
@Service
class DeletedChapterScanRunner(
  private val chapterChecker: ChapterChecker,
) {
  private val executor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "deleted-chapters-scan").apply { isDaemon = true }
    }

  private val progress = AtomicReference(DeletedChapterScanProgress.idle())

  fun status(): DeletedChapterScanProgress = progress.get()

  /** Returns false if a scan is already running. */
  fun start(
    libraryId: String,
    dryRun: Boolean,
  ): Boolean {
    val current = progress.get()
    if (current.running) return false
    val next =
      DeletedChapterScanProgress(
        running = true,
        libraryId = libraryId,
        dryRun = dryRun,
        result = null,
        error = null,
        startedAt = Instant.now(),
        finishedAt = null,
      )
    // Atomic check-and-set so two concurrent start() calls cannot both launch a scan.
    if (!progress.compareAndSet(current, next)) return false
    executor.submit {
      try {
        val result = chapterChecker.scanDeletedChaptersForLibrary(libraryId, dryRun = dryRun)
        progress.updateAndGet { it.copy(running = false, result = result, finishedAt = Instant.now()) }
      } catch (e: Exception) {
        logger.error(e) { "Deleted-chapters scan failed for library $libraryId" }
        progress.updateAndGet { it.copy(running = false, error = e.message ?: "Unknown error", finishedAt = Instant.now()) }
      }
    }
    return true
  }
}

data class DeletedChapterScanProgress(
  val running: Boolean,
  val libraryId: String?,
  val dryRun: Boolean,
  val result: DeletedChapterScanResult?,
  val error: String?,
  val startedAt: Instant?,
  val finishedAt: Instant?,
) {
  companion object {
    fun idle() =
      DeletedChapterScanProgress(
        running = false,
        libraryId = null,
        dryRun = true,
        result = null,
        error = null,
        startedAt = null,
        finishedAt = null,
      )
  }
}
