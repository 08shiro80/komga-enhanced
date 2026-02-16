package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.gotson.komga.domain.model.FollowConfig
import org.gotson.komga.domain.persistence.DownloadQueueRepository
import org.gotson.komga.domain.persistence.FollowConfigRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.service.DownloadExecutor
import org.gotson.komga.domain.service.DownloadScheduler
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration.TagNames
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.rest.dto.ClearResultDto
import org.gotson.komga.interfaces.api.rest.dto.DownloadActionDto
import org.gotson.komga.interfaces.api.rest.dto.DownloadCreateDto
import org.gotson.komga.interfaces.api.rest.dto.DownloadDto
import org.gotson.komga.interfaces.api.rest.dto.FollowConfigDto
import org.gotson.komga.interfaces.api.rest.dto.FollowConfigUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.FollowTxtDto
import org.gotson.komga.interfaces.api.rest.dto.FollowTxtUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.SchedulerSettingsDto
import org.gotson.komga.interfaces.api.rest.dto.SchedulerSettingsUpdateDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/v1/downloads", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class DownloadController(
  private val downloadExecutor: DownloadExecutor,
  private val downloadQueueRepository: DownloadQueueRepository,
  private val followConfigRepository: FollowConfigRepository,
  private val downloadScheduler: DownloadScheduler,
  private val libraryRepository: LibraryRepository,
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
        "retry" -> downloadExecutor.retryDownload(id)
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

  // =====================
  // Library follow.txt Endpoints
  // =====================

  @GetMapping("follow-txt/{libraryId}")
  @Operation(summary = "Get follow.txt content for a library", tags = [TagNames.DOWNLOADS])
  fun getFollowTxt(
    @PathVariable libraryId: String,
  ): FollowTxtDto {
    val library =
      libraryRepository.findByIdOrNull(libraryId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")

    val followFile = library.path.resolve("follow.txt").toFile()
    val content =
      if (followFile.exists()) {
        followFile.readText()
      } else {
        ""
      }

    return FollowTxtDto(
      libraryId = libraryId,
      libraryName = library.name,
      content = content,
    )
  }

  @PutMapping("follow-txt/{libraryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Update follow.txt content for a library", tags = [TagNames.DOWNLOADS])
  fun updateFollowTxt(
    @PathVariable libraryId: String,
    @Valid @RequestBody update: FollowTxtUpdateDto,
  ) {
    val library =
      libraryRepository.findByIdOrNull(libraryId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")

    val followFile = library.path.resolve("follow.txt").toFile()
    followFile.writeText(update.content)
  }

  @PostMapping("follow-txt/{libraryId}/check-now")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Trigger immediate check for a library's follow.txt", tags = [TagNames.DOWNLOADS])
  fun checkFollowTxtNow(
    @PathVariable libraryId: String,
  ) {
    downloadScheduler.checkFollowListNow(libraryId)
  }

  // =====================
  // Scheduler Endpoints
  // =====================

  @GetMapping("scheduler")
  @Operation(summary = "Get scheduler settings", tags = [TagNames.DOWNLOADS])
  fun getSchedulerSettings(): SchedulerSettingsDto {
    val config = followConfigRepository.findDefault() ?: FollowConfig()
    return SchedulerSettingsDto(
      enabled = config.enabled,
      intervalHours = config.checkIntervalHours,
    )
  }

  @PostMapping("scheduler")
  @Operation(summary = "Update scheduler settings", tags = [TagNames.DOWNLOADS])
  fun updateSchedulerSettings(
    @Valid @RequestBody update: SchedulerSettingsUpdateDto,
  ): SchedulerSettingsDto {
    val existingConfig = followConfigRepository.findDefault() ?: FollowConfig()

    val updatedConfig =
      existingConfig.copy(
        enabled = update.enabled,
        checkIntervalHours = update.intervalHours,
      )

    val saved = followConfigRepository.save(updatedConfig)
    downloadScheduler.updateSchedule(saved.enabled, saved.checkIntervalHours)

    return SchedulerSettingsDto(
      enabled = saved.enabled,
      intervalHours = saved.checkIntervalHours,
    )
  }

  // =====================
  // Legacy Follow Config Endpoints (kept for compatibility)
  // =====================

  @GetMapping("follow-config")
  @Operation(summary = "Get follow configuration", tags = [TagNames.DOWNLOADS])
  fun getFollowConfig(): FollowConfigDto {
    val config = followConfigRepository.findDefault() ?: FollowConfig()
    return config.toDto()
  }

  @PostMapping("follow-config")
  @Operation(summary = "Save follow configuration", tags = [TagNames.DOWNLOADS])
  fun saveFollowConfig(
    @Valid @RequestBody update: FollowConfigUpdateDto,
  ): FollowConfigDto {
    val existingConfig = followConfigRepository.findDefault() ?: FollowConfig()

    val updatedConfig =
      existingConfig.copy(
        urls = update.urls,
        enabled = update.enabled,
        checkIntervalHours = update.checkInterval,
      )

    val saved = followConfigRepository.save(updatedConfig)
    downloadScheduler.updateSchedule(saved.enabled, saved.checkIntervalHours)

    return saved.toDto()
  }

  @PostMapping("follow-config/check-now")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Trigger immediate follow list check", tags = [TagNames.DOWNLOADS])
  fun triggerFollowCheck() {
    val config = followConfigRepository.findDefault()
    if (config != null && config.urls.isNotEmpty()) {
      downloadScheduler.processFollowConfigNow(config)
    }
  }
}

// Extension function to convert FollowConfig to DTO
fun FollowConfig.toDto() =
  FollowConfigDto(
    urls = urls,
    enabled = enabled,
    checkInterval = checkIntervalHours,
    lastCheckTime = lastCheckTime?.toString(),
  )

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
