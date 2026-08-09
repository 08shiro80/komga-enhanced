package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.gotson.komga.domain.model.DuplicateNameException
import org.gotson.komga.domain.model.EntryNotFoundException
import org.gotson.komga.domain.model.Follow
import org.gotson.komga.domain.model.FollowSchedule
import org.gotson.komga.domain.persistence.DownloadQueueRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.service.DownloadExecutor
import org.gotson.komga.domain.service.FollowLifecycle
import org.gotson.komga.domain.service.FollowScheduleLifecycle
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.gotson.komga.infrastructure.download.MangaDexSubscriptionSyncer
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration.TagNames
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.rest.dto.ClearResultDto
import org.gotson.komga.interfaces.api.rest.dto.DownloadActionDto
import org.gotson.komga.interfaces.api.rest.dto.DownloadCreateDto
import org.gotson.komga.interfaces.api.rest.dto.DownloadDto
import org.gotson.komga.interfaces.api.rest.dto.FollowBatchCreationDto
import org.gotson.komga.interfaces.api.rest.dto.FollowBatchDeleteDto
import org.gotson.komga.interfaces.api.rest.dto.FollowBatchResultDto
import org.gotson.komga.interfaces.api.rest.dto.FollowCheckResultDto
import org.gotson.komga.interfaces.api.rest.dto.FollowCreationDto
import org.gotson.komga.interfaces.api.rest.dto.FollowDto
import org.gotson.komga.interfaces.api.rest.dto.FollowScheduleDto
import org.gotson.komga.interfaces.api.rest.dto.FollowScheduleUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.FollowUpdateDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/v1/downloads", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class DownloadController(
  private val downloadExecutor: DownloadExecutor,
  private val downloadQueueRepository: DownloadQueueRepository,
  private val libraryRepository: LibraryRepository,
  private val mangaDexSubscriptionSyncer: MangaDexSubscriptionSyncer,
  private val galleryDlWrapper: GalleryDlWrapper,
  private val followService: FollowLifecycle,
  private val followScheduleLifecycle: FollowScheduleLifecycle,
) {
  @GetMapping
  @Operation(summary = "List all downloads", tags = [TagNames.DOWNLOADS])
  fun getAllDownloads(): List<DownloadDto> =
    downloadQueueRepository
      .findAll()
      .sortedWith(compareByDescending<org.gotson.komga.domain.model.DownloadQueue> { it.priority }.thenBy { it.createdDate })
      .map { it.toDto() }

  @GetMapping("{id}")
  @Operation(summary = "Get download by ID", tags = [TagNames.DOWNLOADS])
  fun getDownloadById(
    @PathVariable id: String,
  ): DownloadDto =
    downloadQueueRepository.findByIdOrNull(id)?.toDto()
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Download not found: $id")

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create new download", tags = [TagNames.DOWNLOADS])
  fun createDownload(
    @Valid @RequestBody create: DownloadCreateDto,
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): DownloadDto =
    try {
      downloadExecutor
        .createDownload(
          sourceUrl = create.sourceUrl,
          libraryId = create.libraryId,
          title = create.title,
          createdBy = principal.user.email,
          priority = create.priority,
          overrides = create.toOverrides(),
        ).toDto()
    } catch (e: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
    }

  @PostMapping("{id}/action")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Perform action on download (pause, resume, cancel, retry)", tags = [TagNames.DOWNLOADS])
  fun performAction(
    @PathVariable id: String,
    @Valid @RequestBody action: DownloadActionDto,
  ) {
    try {
      when (action.action.lowercase()) {
        "cancel" -> downloadExecutor.cancelDownload(id)
        "pause" -> downloadExecutor.pauseDownload(id)
        "retry" -> downloadExecutor.retryDownload(id)
        "resume" -> downloadExecutor.resumeDownload(id)
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action: ${action.action}")
      }
    } catch (e: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
    } catch (e: IllegalStateException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
    }
  }

  @DeleteMapping("{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete download", tags = [TagNames.DOWNLOADS])
  fun deleteDownload(
    @PathVariable id: String,
  ) {
    try {
      downloadExecutor.deleteDownload(id)
    } catch (e: Exception) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "Download not found: $id")
    }
  }

  @DeleteMapping("clear/completed")
  @Operation(summary = "Clear all completed downloads", tags = [TagNames.DOWNLOADS])
  fun clearCompletedDownloads(): ClearResultDto {
    val count = downloadExecutor.clearCompletedDownloads()
    return ClearResultDto(
      deletedCount = count,
      status = "COMPLETED",
      message = "Cleared $count completed downloads",
    )
  }

  @DeleteMapping("clear/failed")
  @Operation(summary = "Clear all failed downloads", tags = [TagNames.DOWNLOADS])
  fun clearFailedDownloads(): ClearResultDto {
    val count = downloadExecutor.clearFailedDownloads()
    return ClearResultDto(
      deletedCount = count,
      status = "FAILED",
      message = "Cleared $count failed downloads",
    )
  }

  @DeleteMapping("clear/cancelled")
  @Operation(summary = "Clear all cancelled downloads", tags = [TagNames.DOWNLOADS])
  fun clearCancelledDownloads(): ClearResultDto {
    val count = downloadExecutor.clearCancelledDownloads()
    return ClearResultDto(
      deletedCount = count,
      status = "CANCELLED",
      message = "Cleared $count cancelled downloads",
    )
  }

  @DeleteMapping("clear/pending")
  @Operation(summary = "Clear all pending downloads", tags = [TagNames.DOWNLOADS])
  fun clearPendingDownloads(): ClearResultDto {
    val count = downloadExecutor.clearPendingDownloads()
    return ClearResultDto(
      deletedCount = count,
      status = "PENDING",
      message = "Cleared $count pending downloads",
    )
  }

  @PostMapping("{libraryId}/migrate-to-uuid")
  @Operation(summary = "Migrate title-based folders to UUID folder names", tags = [TagNames.DOWNLOADS])
  fun migrateToUuidFolders(
    @PathVariable libraryId: String,
  ): ResponseEntity<Map<String, Any>> {
    val result = downloadExecutor.migrateLibraryToUuidFolders(libraryId)
    return ResponseEntity.ok(
      mapOf(
        "foldersRenamed" to result.foldersRenamed,
        "cbzRenamed" to result.cbzRenamed,
      ),
    )
  }

  @PostMapping("repair-comicinfo/{libraryId}")
  @Operation(summary = "Repair missing ComicInfo.xml and zip comments in MangaDex CBZ files (async)", tags = [TagNames.DOWNLOADS])
  fun repairComicInfo(
    @PathVariable libraryId: String,
    @RequestParam(defaultValue = "false") force: Boolean,
  ): ResponseEntity<Map<String, Any>> {
    val library =
      libraryRepository.findByIdOrNull(libraryId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found")

    val libraryDir = library.path.toFile()
    if (!libraryDir.isDirectory) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Library path is not a directory")
    }

    if (repairProgress.get().running) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "Re-inject ComicInfo already running for library ${repairProgress.get().libraryId}")
    }

    val uuidRegex = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
    val mangaDexDirs = mutableMapOf<String, MutableList<java.io.File>>()

    libraryDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
      if (uuidRegex.matches(dir.name)) {
        mangaDexDirs.getOrPut(dir.name) { mutableListOf() }.add(dir)
      } else {
        val seriesJson = java.io.File(dir, "series.json")
        if (seriesJson.exists()) {
          try {
            val content = seriesJson.readText()
            val idMatch =
              Regex(""""comicid"\s*:\s*"([a-f0-9-]{36})"""").find(content)
                ?: Regex("""mangadex\.org/title/([a-f0-9-]{36})""").find(content)
            if (idMatch != null) {
              mangaDexDirs.getOrPut(idMatch.groupValues[1]) { mutableListOf() }.add(dir)
            }
          } catch (e: Exception) {
            logger.warn(e) { "Failed to read series.json in ${dir.name}" }
          }
        }
      }
    }

    val totalMangas = mangaDexDirs.size
    repairProgress.set(
      RepairProgress(
        running = true,
        libraryId = libraryId,
        total = totalMangas,
        processed = 0,
        repaired = 0,
        skipped = 0,
        errors = emptyList(),
        startedAt = Instant.now(),
        finishedAt = null,
      ),
    )

    repairExecutor.submit {
      var repaired = 0
      var skipped = 0
      val errors = mutableListOf<String>()
      var processed = 0
      try {
        for ((mangaDexId, dirs) in mangaDexDirs) {
          val result = galleryDlWrapper.repairMissingComicInfo(mangaDexId, dirs, forceReinject = force)
          repaired += result.repaired
          skipped += result.skipped
          if (result.error != null) errors.add("$mangaDexId: ${result.error}")
          processed++
          repairProgress.updateAndGet {
            it.copy(processed = processed, repaired = repaired, skipped = skipped, errors = errors.toList())
          }
          // Belt-and-suspenders on top of MangaDexApiClient's own rate limiter —
          // 250 ms between mangas keeps the global ~5 req/sec budget honest even
          // when getMangaMetadata + getChaptersForManga both fire per manga.
          try {
            Thread.sleep(250)
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return@submit
          }
        }
      } catch (e: Exception) {
        logger.error(e) { "Re-inject ComicInfo crashed unexpectedly" }
        errors.add("Unexpected crash: ${e.message}")
      } finally {
        repairProgress.updateAndGet {
          it.copy(
            running = false,
            processed = processed,
            repaired = repaired,
            skipped = skipped,
            errors = errors.toList(),
            finishedAt = Instant.now(),
          )
        }
        logger.info { "Re-inject ComicInfo finished for library $libraryId: processed=$processed/$totalMangas repaired=$repaired skipped=$skipped errors=${errors.size}" }
      }
    }

    return ResponseEntity.accepted().body(
      mapOf(
        "status" to "started",
        "mangaProcessed" to 0,
        "mangaTotal" to totalMangas,
      ),
    )
  }

  @GetMapping("repair-comicinfo/status")
  @Operation(summary = "Progress for the currently running or last finished re-inject job", tags = [TagNames.DOWNLOADS])
  fun repairComicInfoStatus(): RepairProgress = repairProgress.get()

  @PostMapping("follows/{libraryId}/sync-to-mangadex")
  @Operation(summary = "Upload a library's MangaDex follow URLs to the MangaDex follows list", tags = [TagNames.DOWNLOADS])
  fun syncFollowsToMangaDex(
    @PathVariable libraryId: String,
  ): ResponseEntity<Map<String, Any>> {
    val result = mangaDexSubscriptionSyncer.syncFollowsToMangaDex(libraryId)
    if (result.error != null) {
      return ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(mapOf("error" to result.error, "followed" to result.followed, "total" to result.total))
    }
    return ResponseEntity.ok(
      mapOf("followed" to result.followed, "total" to result.total, "message" to "Synced ${result.followed}/${result.total} manga to MangaDex follows"),
    )
  }

  @GetMapping("mangadex/follows")
  @Operation(summary = "Get the set of manga UUIDs followed on the user's MangaDex account", tags = [TagNames.DOWNLOADS])
  fun getMangaDexFollows(): Map<String, Any> {
    val ids = mangaDexSubscriptionSyncer.getFollowedMangaIdsFromAccount()
    return mapOf("uuids" to ids)
  }

  @PostMapping("mangadex/follows/{mangaId}")
  @Operation(summary = "Follow a manga on the user's MangaDex account", tags = [TagNames.DOWNLOADS])
  fun followMangaOnMangaDex(
    @PathVariable mangaId: String,
  ): ResponseEntity<Map<String, Any>> {
    val result = mangaDexSubscriptionSyncer.followMangaOnAccount(mangaId)
    val status = if (result.success) HttpStatus.OK else HttpStatus.BAD_REQUEST
    return ResponseEntity.status(status).body(mapOf("success" to result.success, "message" to result.message))
  }

  @DeleteMapping("mangadex/follows/{mangaId}")
  @Operation(summary = "Unfollow a manga on the user's MangaDex account", tags = [TagNames.DOWNLOADS])
  fun unfollowMangaOnMangaDex(
    @PathVariable mangaId: String,
  ): ResponseEntity<Map<String, Any>> {
    val result = mangaDexSubscriptionSyncer.unfollowMangaOnAccount(mangaId)
    val status = if (result.success) HttpStatus.OK else HttpStatus.BAD_REQUEST
    return ResponseEntity.status(status).body(mapOf("success" to result.success, "message" to result.message))
  }

  @PostMapping("mangadex-subscription/force-resync")
  @Operation(summary = "Rewind MangaDex subscription last_check_time and run a feed check now", tags = [TagNames.DOWNLOADS])
  fun forceResyncMangaDexSubscription(
    @RequestParam(defaultValue = "7") lookbackDays: Int,
  ): ResponseEntity<Map<String, Any>> {
    val result = mangaDexSubscriptionSyncer.forceResyncFeed(lookbackDays)
    val status = if (result.success) HttpStatus.OK else HttpStatus.BAD_REQUEST
    return ResponseEntity
      .status(status)
      .body(mapOf("success" to result.success, "message" to result.message))
  }

  @GetMapping("follows/{libraryId}")
  @Operation(summary = "List follow entries for a library", tags = [TagNames.DOWNLOADS])
  fun getFollows(
    @PathVariable libraryId: String,
  ): List<FollowDto> {
    libraryRepository.findByIdOrNull(libraryId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")
    return followService.getAll(libraryId).map { it.toDto() }
  }

  @GetMapping("follows/by-series/{seriesId}")
  @Operation(summary = "List follow entries linked to a series", tags = [TagNames.DOWNLOADS])
  fun getFollowsBySeries(
    @PathVariable seriesId: String,
  ): List<FollowDto> = followService.getBySeries(seriesId).map { it.toDto() }

  @PostMapping("follows/{libraryId}")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add a follow entry for a library", tags = [TagNames.DOWNLOADS])
  fun addFollow(
    @PathVariable libraryId: String,
    @Valid @RequestBody creation: FollowCreationDto,
  ): FollowDto {
    libraryRepository.findByIdOrNull(libraryId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")
    return try {
      followService
        .add(
          libraryId = libraryId,
          url = creation.url,
          title = creation.title,
          seriesId = creation.seriesId,
        ).toDto()
    } catch (e: DuplicateNameException) {
      throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
    }
  }

  @PostMapping("follows/{libraryId}/batch")
  @Operation(summary = "Add multiple follow entries at once (skips duplicates)", tags = [TagNames.DOWNLOADS])
  fun addFollowsBatch(
    @PathVariable libraryId: String,
    @Valid @RequestBody creation: FollowBatchCreationDto,
  ): FollowBatchResultDto {
    libraryRepository.findByIdOrNull(libraryId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")
    val (added, skipped) = followService.addBatch(libraryId, creation.urls)
    return FollowBatchResultDto(added = added, skipped = skipped)
  }

  @PatchMapping("follows/{libraryId}/{id}")
  @Operation(summary = "Update a follow entry", tags = [TagNames.DOWNLOADS])
  fun updateFollow(
    @PathVariable libraryId: String,
    @PathVariable id: String,
    @RequestBody update: FollowUpdateDto,
  ): FollowDto =
    try {
      followService
        .update(
          id = id,
          title = update.title,
          enabled = update.enabled,
        ).toDto()
    } catch (e: EntryNotFoundException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
    }

  @DeleteMapping("follows/{libraryId}/batch")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete multiple follow entries at once", tags = [TagNames.DOWNLOADS])
  fun deleteFollowsBatch(
    @PathVariable libraryId: String,
    @Valid @RequestBody request: FollowBatchDeleteDto,
  ) {
    followService.deleteBatch(request.ids)
  }

  @DeleteMapping("follows/{libraryId}/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a follow entry", tags = [TagNames.DOWNLOADS])
  fun deleteFollow(
    @PathVariable libraryId: String,
    @PathVariable id: String,
  ) {
    followService.delete(id)
  }

  @PostMapping("follows/{libraryId}/check-now")
  @Operation(summary = "Check all enabled follows now and queue new chapters", tags = [TagNames.DOWNLOADS])
  fun checkFollowsNow(
    @PathVariable libraryId: String,
  ): FollowCheckResultDto {
    libraryRepository.findByIdOrNull(libraryId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")
    return FollowCheckResultDto(queued = followService.checkNow(libraryId))
  }

  @PostMapping("follows/{libraryId}/import-follow-txt")
  @Operation(summary = "Import a library's legacy follow.txt entries into the follow list (skips duplicates)", tags = [TagNames.DOWNLOADS])
  fun importFollowTxt(
    @PathVariable libraryId: String,
  ): Map<String, Any> {
    libraryRepository.findByIdOrNull(libraryId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")
    val (added, skipped) = followService.importFromFollowTxt(libraryId)
    return mapOf(
      "added" to added,
      "skipped" to skipped,
      "message" to "Imported $added follows from follow.txt ($skipped skipped)",
    )
  }

  @GetMapping("follows/{libraryId}/schedule")
  @Operation(summary = "Get the follow-check schedule for a library", tags = [TagNames.DOWNLOADS])
  fun getFollowSchedule(
    @PathVariable libraryId: String,
  ): FollowScheduleDto {
    libraryRepository.findByIdOrNull(libraryId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")
    return followScheduleLifecycle.getSchedule(libraryId).toDto()
  }

  @PutMapping("follows/{libraryId}/schedule")
  @Operation(summary = "Set the follow-check schedule for a library", tags = [TagNames.DOWNLOADS])
  fun updateFollowSchedule(
    @PathVariable libraryId: String,
    @RequestBody update: FollowScheduleUpdateDto,
  ): FollowScheduleDto {
    libraryRepository.findByIdOrNull(libraryId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")
    return followScheduleLifecycle
      .updateSchedule(
        FollowSchedule(
          libraryId = libraryId,
          enabled = update.enabled,
          scheduleMode = update.scheduleMode,
          intervalHours = update.intervalHours,
          checkTime = update.checkTime,
        ),
      ).toDto()
  }

  companion object {
    private val repairExecutor: ExecutorService =
      Executors.newSingleThreadExecutor { r ->
        Thread(r, "repair-comicinfo").apply { isDaemon = true }
      }
    private val repairProgress: AtomicReference<RepairProgress> = AtomicReference(RepairProgress.idle())
  }
}

fun Follow.toDto() =
  FollowDto(
    id = id,
    libraryId = libraryId,
    url = url,
    title = title,
    seriesId = seriesId,
    enabled = enabled,
    addedAt = addedAt,
    lastCheckedAt = lastCheckedAt,
  )

fun FollowSchedule.toDto() =
  FollowScheduleDto(
    libraryId = libraryId,
    enabled = enabled,
    scheduleMode = scheduleMode,
    intervalHours = intervalHours,
    checkTime = checkTime,
    lastCheckTime = lastCheckTime,
  )

data class RepairProgress(
  val running: Boolean,
  val libraryId: String?,
  val total: Int,
  val processed: Int,
  val repaired: Int,
  val skipped: Int,
  val errors: List<String>,
  val startedAt: Instant?,
  val finishedAt: Instant?,
) {
  companion object {
    fun idle() =
      RepairProgress(
        running = false,
        libraryId = null,
        total = 0,
        processed = 0,
        repaired = 0,
        skipped = 0,
        errors = emptyList(),
        startedAt = null,
        finishedAt = null,
      )
  }
}

fun org.gotson.komga.domain.model.DownloadQueue.toDto() =
  DownloadDto(
    id = id,
    sourceUrl = sourceUrl,
    title = title,
    status = status.name,
    progressPercent = progressPercent,
    currentChapter = currentChapter ?: 0,
    totalChapters = totalChapters,
    libraryId = libraryId,
    errorMessage = errorMessage,
    createdDate = createdDate,
    startedDate = startedDate,
    completedDate = completedDate,
    priority = priority,
  )
