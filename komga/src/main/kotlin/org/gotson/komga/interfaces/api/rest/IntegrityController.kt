package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import jakarta.annotation.PreDestroy
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.infrastructure.background.BackgroundJobTracker
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipException
import java.util.zip.ZipFile

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/v1/media-management/integrity", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class IntegrityController(
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val backgroundJobTracker: BackgroundJobTracker,
  private val taskEmitter: TaskEmitter,
) {
  private val inProgress = AtomicBoolean(false)
  private val processed = AtomicInteger(0)
  private val total = AtomicInteger(0)
  private val flagged = AtomicInteger(0)

  private val repairInProgress = AtomicBoolean(false)
  private val repairProcessed = AtomicInteger(0)
  private val repairTotal = AtomicInteger(0)
  private val repairFixed = AtomicInteger(0)
  private val repairPartial = AtomicInteger(0)
  private val repairFailed = AtomicInteger(0)

  private val executor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "integrity-repair").apply { isDaemon = true }
    }

  @PreDestroy
  fun shutdown() {
    executor.shutdownNow()
  }

  @GetMapping("status")
  fun status(): Map<String, Any> {
    val flaggedNow = if (inProgress.get()) flagged.get().toLong() else mediaRepository.countByStatus(Media.Status.ERROR)
    return mapOf(
      "inProgress" to inProgress.get(),
      "processed" to processed.get(),
      "total" to total.get(),
      "flagged" to flaggedNow,
      "repairInProgress" to repairInProgress.get(),
      "repairProcessed" to repairProcessed.get(),
      "repairTotal" to repairTotal.get(),
      "repairFixed" to repairFixed.get(),
      "repairPartial" to repairPartial.get(),
      "repairFailed" to repairFailed.get(),
    )
  }

  @PostMapping("verify")
  @Operation(summary = "Verify ZIP integrity of all books; flag corrupt CBZs as ERROR")
  fun verifyAll() {
    if (!inProgress.compareAndSet(false, true)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "Integrity verification already running")
    }
    processed.set(0)
    flagged.set(0)
    total.set(0)
    backgroundJobTracker.start("Verify Integrity")
    executor.submit {
      try {
        runVerify()
      } finally {
        inProgress.set(false)
        backgroundJobTracker.stop("Verify Integrity")
      }
    }
  }

  @PostMapping("rescan")
  @Operation(summary = "Re-verify ZIP integrity per ERROR-flagged book, then queue AnalyzeBook for the survivors")
  fun rescanFlagged(): Map<String, Int> {
    val errorBooks =
      bookRepository.findAll().filter { book ->
        runCatching { mediaRepository.findById(book.id).status == Media.Status.ERROR }.getOrDefault(false)
      }
    var stillCorrupt = 0
    var queued = 0
    for (book in errorBooks) {
      val verifyOk =
        runCatching {
          ZipFile(book.path.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
              zip.getInputStream(entry).use { stream ->
                val buf = ByteArray(8192)
                while (stream.read(buf) >= 0) Unit
              }
            }
          }
          true
        }.getOrElse { false }
      if (verifyOk) {
        taskEmitter.analyzeBook(book)
        queued++
      } else {
        stillCorrupt++
      }
    }
    logger.info { "Rescan: $queued AnalyzeBook queued, $stillCorrupt still corrupt (kept as ERROR)" }
    return mapOf("queued" to queued, "stillCorrupt" to stillCorrupt)
  }

  @PostMapping("repair")
  @Operation(summary = "Run zip -FF on all books flagged as ERROR; restore status when fully recovered")
  fun repairAll() {
    if (!repairInProgress.compareAndSet(false, true)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "Repair already running")
    }
    repairProcessed.set(0)
    repairTotal.set(0)
    repairFixed.set(0)
    repairPartial.set(0)
    repairFailed.set(0)
    backgroundJobTracker.start("Repair Corrupt")
    executor.submit {
      try {
        runRepair()
      } finally {
        repairInProgress.set(false)
        backgroundJobTracker.stop("Repair Corrupt")
      }
    }
  }

  private fun runVerify() {
    val books = bookRepository.findAll()
    total.set(books.size)
    logger.info { "Integrity check: verifying ${books.size} books" }
    for (book in books) {
      val ext = book.path.toString().lowercase()
      if (!ext.endsWith(".cbz") && !ext.endsWith(".zip")) {
        processed.incrementAndGet()
        continue
      }
      val firstFailure = runVerifyPass(book.path)
      if (firstFailure != null) {
        Thread.sleep(VERIFY_RETRY_DELAY_MS)
        val secondFailure = runVerifyPass(book.path)
        if (secondFailure != null) {
          logger.warn { "Verify: ${book.path} failed twice (${secondFailure.message}) — flagging ERROR" }
          flagBookAsError(book.id, secondFailure.message)
        } else {
          logger.info { "Verify: ${book.path} recovered on retry (1st: ${firstFailure.message}) — NOT flagging" }
        }
      }
      processed.incrementAndGet()
    }
    logger.info { "Integrity check done: ${flagged.get()} corrupt of ${total.get()} books" }
  }

  private fun runVerifyPass(path: Path): Throwable? =
    try {
      ZipFile(path.toFile()).use { zip ->
        zip.entries().asSequence().forEach { entry ->
          zip.getInputStream(entry).use { stream ->
            val buf = ByteArray(8192)
            while (stream.read(buf) >= 0) Unit
          }
        }
      }
      null
    } catch (e: ZipException) {
      e
    } catch (e: IOException) {
      e
    }

  private fun runRepair() {
    val errorBooks =
      bookRepository.findAll().filter { book ->
        runCatching { mediaRepository.findById(book.id).status == Media.Status.ERROR }.getOrDefault(false)
      }
    repairTotal.set(errorBooks.size)
    logger.info { "Repair: attempting zip -FF on ${errorBooks.size} flagged books" }
    for (book in errorBooks) {
      val srcPath = book.path
      val outcome =
        runCatching { repairCbz(srcPath) }.getOrElse {
          logger.warn(it) { "Repair: exception on ${srcPath.fileName}" }
          RepairOutcome.Failed("exception: ${it.message}")
        }
      when (outcome) {
        is RepairOutcome.Fixed -> {
          val current = mediaRepository.findById(book.id)
          mediaRepository.update(current.copy(status = Media.Status.OUTDATED, comment = null))
          repairFixed.incrementAndGet()
          logger.info { "Repair: fixed ${srcPath.fileName} (${outcome.entries} entries) — flagged OUTDATED for re-analyze" }
        }
        is RepairOutcome.Partial -> {
          val current = mediaRepository.findById(book.id)
          mediaRepository.update(
            current.copy(
              status = Media.Status.ERROR,
              comment = "Partial repair: ${outcome.recovered}/${outcome.original} entries — needs re-download",
            ),
          )
          repairPartial.incrementAndGet()
          logger.warn { "Repair: partial ${srcPath.fileName} (${outcome.recovered}/${outcome.original}) — keep ERROR" }
        }
        is RepairOutcome.Failed -> {
          repairFailed.incrementAndGet()
          logger.warn { "Repair: failed ${srcPath.fileName}: ${outcome.reason}" }
        }
      }
      repairProcessed.incrementAndGet()
    }
    logger.info { "Repair done: fixed=${repairFixed.get()} partial=${repairPartial.get()} failed=${repairFailed.get()}" }
  }

  private fun repairCbz(srcPath: Path): RepairOutcome {
    // Best-effort baseline: if the central directory is so corrupt that ZipFile throws, that is exactly the
    // case zip -FF exists to repair — proceed with an unknown (-1) count instead of aborting the repair.
    val originalCount = runCatching { countEntries(srcPath) }.getOrDefault(-1)
    val tmpFixed = Files.createTempFile(srcPath.parent, ".repair_", ".cbz")
    Files.deleteIfExists(tmpFixed)
    val process =
      ProcessBuilder("zip", "-FF", srcPath.toString(), "--out", tmpFixed.toString())
        .redirectErrorStream(true)
        .start()
    val finished = process.waitFor(600, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      Files.deleteIfExists(tmpFixed)
      return RepairOutcome.Failed("timeout")
    }
    if (!Files.isRegularFile(tmpFixed) || Files.size(tmpFixed) == 0L) {
      Files.deleteIfExists(tmpFixed)
      return RepairOutcome.Failed("zip -FF produced no output (exit=${process.exitValue()})")
    }
    val recoveredCount =
      runCatching { countEntries(tmpFixed) }.getOrElse {
        Files.deleteIfExists(tmpFixed)
        return RepairOutcome.Failed("fixed file unreadable: ${it.message}")
      }
    if (recoveredCount > 0 && (originalCount < 0 || recoveredCount >= originalCount)) {
      Files.move(tmpFixed, srcPath, StandardCopyOption.REPLACE_EXISTING)
      return RepairOutcome.Fixed(recoveredCount)
    }
    Files.deleteIfExists(tmpFixed)
    return RepairOutcome.Partial(recoveredCount, originalCount)
  }

  private fun countEntries(p: Path): Int = ZipFile(p.toFile()).use { it.entries().asSequence().count() }

  private fun flagBookAsError(
    bookId: String,
    msg: String?,
  ) {
    val current = mediaRepository.findById(bookId)
    if (current.status != Media.Status.ERROR) {
      mediaRepository.update(current.copy(status = Media.Status.ERROR, comment = "Corrupt CBZ: $msg"))
      flagged.incrementAndGet()
      logger.warn { "Integrity: flagged $bookId as ERROR ($msg)" }
    }
  }

  private sealed class RepairOutcome {
    data class Fixed(
      val entries: Int,
    ) : RepairOutcome()

    data class Partial(
      val recovered: Int,
      val original: Int,
    ) : RepairOutcome()

    data class Failed(
      val reason: String,
    ) : RepairOutcome()
  }

  companion object {
    private const val VERIFY_RETRY_DELAY_MS = 2000L
  }
}
