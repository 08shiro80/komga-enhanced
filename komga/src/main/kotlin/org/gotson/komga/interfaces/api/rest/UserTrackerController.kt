package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.scrobbler.TrackerConnectionChecker
import org.gotson.komga.infrastructure.scrobbler.TrackerTokenProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.rest.dto.TrackerCheckResultDto
import org.gotson.komga.interfaces.api.rest.dto.UserTrackerDto
import org.gotson.komga.interfaces.api.rest.dto.UserTrackerUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.toDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

private val contentRatings = setOf("safe", "suggestive", "erotica", "pornographic")

@RestController
@RequestMapping("api/v1/trackers", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserTrackerController(
  private val userTrackerRepository: UserTrackerRepository,
  private val trackerTokenProvider: TrackerTokenProvider,
  private val trackerConnectionChecker: TrackerConnectionChecker,
) {
  @Operation(summary = "List the tracker accounts of the current user", description = "Secrets are never returned, only whether they are set.")
  @GetMapping
  fun getTrackers(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): List<UserTrackerDto> {
    val existing = userTrackerRepository.findByUserId(principal.user.id).associateBy { it.tracker }
    return UserTracker.trackers.map { (existing[it] ?: UserTracker(principal.user.id, it, emptyMap())).toDto() }
  }

  @Operation(summary = "Link or update a tracker account of the current user", description = "Omitted settings keep their value, blank or null settings are removed.")
  @PutMapping("{tracker}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun updateTracker(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable tracker: String,
    @RequestBody update: UserTrackerUpdateDto,
  ) {
    val allowed = UserTracker.allowedKeys[tracker] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val unknown = update.settings.keys - allowed
    if (unknown.isNotEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown settings for $tracker: $unknown")
    val rating = update.settings[UserTracker.KEY_MAX_CONTENT_RATING]
    if (!rating.isNullOrBlank() && rating !in contentRatings) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid max_content_rating: $rating")

    val userId = principal.user.id
    val merged = userTrackerRepository.findByUserIdAndTracker(userId, tracker)?.settings.orEmpty().toMutableMap()
    update.settings.forEach { (key, value) ->
      if (value.isNullOrBlank()) merged.remove(key) else merged[key] = if (key in UserTracker.secretKeys) value else value.trim()
    }
    update.enabled?.let { merged[UserTracker.KEY_ENABLED] = it.toString() }

    if (merged.keys.all { it == UserTracker.KEY_ENABLED }) {
      userTrackerRepository.delete(userId, tracker)
    } else {
      userTrackerRepository.save(UserTracker(userId, tracker, merged))
    }
    trackerTokenProvider.invalidate(UserTracker(userId, tracker, merged))
  }

  @Operation(summary = "Unlink a tracker account of the current user")
  @DeleteMapping("{tracker}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun deleteTracker(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable tracker: String,
  ) {
    if (tracker !in UserTracker.trackers) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    userTrackerRepository.delete(principal.user.id, tracker)
    trackerTokenProvider.invalidate(UserTracker(principal.user.id, tracker, emptyMap()))
  }

  @Operation(summary = "Test the connection of a tracker account of the current user")
  @PostMapping("{tracker}/test")
  fun testTracker(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable tracker: String,
  ): TrackerCheckResultDto {
    if (tracker !in UserTracker.trackers) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val account =
      userTrackerRepository.findByUserIdAndTracker(principal.user.id, tracker)
        ?: return TrackerCheckResultDto(false, "Account is not linked")
    val result = trackerConnectionChecker.check(account)
    return TrackerCheckResultDto(result.success, result.message)
  }
}
