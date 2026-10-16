package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.comicscrobbler.ComicScrobblerPlugin
import org.gotson.komga.infrastructure.scrobbler.MangaScrobblerPlugin
import org.gotson.komga.infrastructure.scrobbler.ScrobblerConfig
import org.gotson.komga.infrastructure.scrobbler.TrackerAuthorizationProvider
import org.gotson.komga.infrastructure.scrobbler.TrackerAuthorizationStateException
import org.gotson.komga.infrastructure.scrobbler.TrackerCallException
import org.gotson.komga.infrastructure.scrobbler.TrackerConnectionChecker
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimitedException
import org.gotson.komga.infrastructure.scrobbler.TrackerStatusRecorder
import org.gotson.komga.infrastructure.scrobbler.TrackerTokenProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.infrastructure.security.TrackerSecretException
import org.gotson.komga.interfaces.api.rest.dto.TrackerAuthorizationDto
import org.gotson.komga.interfaces.api.rest.dto.TrackerAuthorizationRequestDto
import org.gotson.komga.interfaces.api.rest.dto.TrackerCheckResultDto
import org.gotson.komga.interfaces.api.rest.dto.TrackerSyncDto
import org.gotson.komga.interfaces.api.rest.dto.UserTrackerDto
import org.gotson.komga.interfaces.api.rest.dto.UserTrackerUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.toDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.net.URI
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

private val contentRatings = setOf("safe", "suggestive", "erotica", "pornographic")

private val returnToPath = Regex("""/(?:[A-Za-z0-9._~!$&'()*+,;=:@/-]|%[0-9A-Fa-f]{2})*""")

private const val MANGA_SCROBBLER = "manga-scrobbler"
private const val COMIC_SCROBBLER = "comic-scrobbler"

@RestController
@RequestMapping("api/v1/trackers", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserTrackerController(
  private val userTrackerRepository: UserTrackerRepository,
  private val pluginRepository: PluginRepository,
  private val trackerTokenProvider: TrackerTokenProvider,
  private val trackerConnectionChecker: TrackerConnectionChecker,
  private val trackerAuthorizationProvider: TrackerAuthorizationProvider,
  private val trackerStatusRecorder: TrackerStatusRecorder,
  private val mangaScrobblerPlugin: MangaScrobblerPlugin,
  private val comicScrobblerPlugin: ComicScrobblerPlugin,
) {
  @Operation(summary = "List the tracker accounts of the current user", description = "Secrets are never returned, only whether they are set.")
  @GetMapping
  fun getTrackers(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): List<UserTrackerDto> {
    val existing = userTrackerRepository.findByUserId(principal.user.id).associateBy { it.tracker }
    val mangaEnabled = ScrobblerConfig.enabled(pluginRepository, MANGA_SCROBBLER)
    val comicEnabled = ScrobblerConfig.enabled(pluginRepository, COMIC_SCROBBLER)
    return UserTracker.trackers.map {
      val account = existing[it] ?: UserTracker(principal.user.id, it, emptyMap())
      account.toDto(
        authorizationRedirectUri = authorizationRedirectUri(it),
        scrobblerEnabled = if (it == UserTracker.METRON) comicEnabled else mangaEnabled,
        tokenExpiresAt = trackerTokenProvider.tokenExpiry(account),
      )
    }
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
    val existing = userTrackerRepository.findByUserIdAndTracker(userId, tracker)?.settings.orEmpty()
    val merged = existing.toMutableMap()
    update.settings.forEach { (key, value) ->
      if (value.isNullOrBlank()) merged.remove(key) else merged[key] = if (key in UserTracker.secretKeys) value else value.trim()
    }
    update.enabled?.let { merged[UserTracker.KEY_ENABLED] = it.toString() }
    val credentialsSent = update.settings.keys.any { it in UserTracker.credentialKeys }
    val credentialsChanged = UserTracker.credentialKeys.any { existing[it] != merged[it] }
    val hasCredentials = merged.keys.any { it != UserTracker.KEY_ENABLED && it !in UserTracker.statusKeys }
    if (!hasCredentials) {
      UserTracker.statusKeys.forEach { merged.remove(it) }
    } else {
      if (credentialsSent || credentialsChanged) {
        merged.remove(UserTracker.KEY_STATUS)
        merged.remove(UserTracker.KEY_STATUS_MESSAGE)
      }
      if (credentialsSent && tracker != UserTracker.METRON) merged[UserTracker.KEY_IDENTITY_CHECK] = UserTracker.IDENTITY_CHECK_REQUIRED
    }
    val previousMetronUser = existing[UserTracker.KEY_USERNAME]
    val metronAccountChanged =
      tracker == UserTracker.METRON &&
        previousMetronUser != null &&
        merged[UserTracker.KEY_USERNAME] != null &&
        merged[UserTracker.KEY_USERNAME] != previousMetronUser

    val credentialsRemoved = existing.keys.any { it in UserTracker.credentialKeys } && merged.keys.none { it in UserTracker.credentialKeys }

    if (credentialsRemoved || merged.isEmpty() || merged == mapOf(UserTracker.KEY_ENABLED to "true")) {
      userTrackerRepository.delete(userId, tracker)
      trackerStatusRecorder.resetSyncState(userId, tracker)
    } else {
      try {
        userTrackerRepository.save(UserTracker(userId, tracker, merged))
      } catch (e: TrackerSecretException) {
        logger.warn { "Tracker secrets of user $userId could not be stored: ${e.message}" }
        throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Tracker secrets cannot be stored, see the server log")
      }
      if (credentialsSent) mangaScrobblerPlugin.clearIdentityBackoff(userId, tracker)
      if (metronAccountChanged) {
        logger.info { "Metron account of user $userId changed to another username, resetting the sync state" }
        trackerStatusRecorder.resetSyncState(userId, tracker)
      }
    }
    trackerTokenProvider.invalidate(UserTracker(userId, tracker, merged))
  }

  @Operation(summary = "Unlink a tracker account of the current user", description = "Removes the stored credentials and the sync state of this tracker.")
  @DeleteMapping("{tracker}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun deleteTracker(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable tracker: String,
  ) {
    if (tracker !in UserTracker.trackers) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    userTrackerRepository.delete(principal.user.id, tracker)
    trackerStatusRecorder.resetSyncState(principal.user.id, tracker)
    trackerTokenProvider.invalidate(UserTracker(principal.user.id, tracker, emptyMap()))
  }

  @Operation(summary = "Test the connection of a tracker account of the current user", description = "The result is also stored as the account status.")
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

  @Operation(
    summary = "Sync the read progress of the current user to a tracker now",
    description = "Queues every read series of the current user for this tracker. Only progress that is ahead of the tracker is sent.",
  )
  @PostMapping("{tracker}/sync")
  fun syncTracker(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable tracker: String,
  ): TrackerSyncDto {
    if (tracker !in UserTracker.trackers) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val account = userTrackerRepository.findByUserIdAndTracker(principal.user.id, tracker)
    if (account == null || !account.active) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The $tracker account is not linked, paused or must be connected again")
    val pluginId = if (tracker == UserTracker.METRON) COMIC_SCROBBLER else MANGA_SCROBBLER
    if (!ScrobblerConfig.enabled(pluginRepository, pluginId)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The $pluginId plugin is disabled by the administrator")
    val queued =
      if (tracker == UserTracker.METRON) {
        comicScrobblerPlugin.reconcileNow(principal.user.id)
      } else {
        mangaScrobblerPlugin.reconcileNow(principal.user.id, tracker)
      }
    return TrackerSyncDto(queued)
  }

  @Operation(
    summary = "Start the authorization of a tracker account of the current user",
    description = "Only for AniList and MyAnimeList. Returns the provider URL the browser must open. The provider redirects back to the callback endpoint, which redirects to returnTo.",
  )
  @PostMapping("{tracker}/authorize")
  fun authorizeTracker(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable tracker: String,
    @RequestBody request: TrackerAuthorizationRequestDto,
    servletRequest: HttpServletRequest,
  ): TrackerAuthorizationDto {
    if (tracker !in UserTracker.trackers) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (tracker !in UserTracker.authorizationCodeTrackers) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$tracker does not use the authorization flow")
    if (!isValidReturnTo(request.returnTo, servletRequest.contextPath)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "returnTo must be a path within the application")

    val account = userTrackerRepository.findByUserIdAndTracker(principal.user.id, tracker) ?: UserTracker(principal.user.id, tracker, emptyMap())
    val url =
      try {
        trackerAuthorizationProvider.start(account, authorizationRedirectUri(tracker)!!, request.returnTo)
      } catch (e: TrackerCallException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
      }
    return TrackerAuthorizationDto(url)
  }

  @Operation(
    summary = "Authorization callback of a tracker provider",
    description = "Opened by the browser after the provider login. Always redirects into the application (the returnTo path of the authorization, or the trackers page) with the query parameters trackerAuth, trackerAuthResult (success or error) and trackerAuthError (denied, missing_code, exchange_failed, rate_limited, not_configured, storage_failed, session_expired, invalid_state, state_mismatch or unsupported_tracker).",
  )
  @GetMapping("{tracker}/callback")
  fun authorizationCallback(
    @AuthenticationPrincipal principal: KomgaPrincipal?,
    @PathVariable tracker: String,
    @RequestParam(required = false) code: String?,
    @RequestParam(required = false) state: String?,
    @RequestParam(required = false) error: String?,
    servletRequest: HttpServletRequest,
  ): ResponseEntity<Unit> {
    val fallback = "${servletRequest.contextPath}/account/trackers"
    if (tracker !in UserTracker.authorizationCodeTrackers) return redirect(fallback, tracker, "unsupported_tracker")
    if (principal == null) return redirect(fallback, tracker, "session_expired")
    if (state.isNullOrBlank()) return redirect(fallback, tracker, "invalid_state")
    val userId = principal.user.id
    val pending =
      try {
        trackerAuthorizationProvider.consume(state, userId, tracker)
      } catch (e: TrackerAuthorizationStateException) {
        logger.warn { "$tracker authorization callback of user $userId rejected: ${e.message}" }
        return redirect(fallback, tracker, if (e.userMismatch) "state_mismatch" else "invalid_state")
      }

    val errorCode =
      when {
        error != null -> "denied"
        code.isNullOrBlank() -> "missing_code"
        else -> exchangeCode(userId, tracker, code, pending.redirectUri, pending.codeVerifier)
      }
    return redirect(pending.returnTo, tracker, errorCode)
  }

  private fun redirect(
    returnTo: String,
    tracker: String,
    errorCode: String?,
  ): ResponseEntity<Unit> {
    val location =
      UriComponentsBuilder
        .fromPath(returnTo)
        .queryParam("trackerAuth", UriUtils.encodeQueryParam(tracker, StandardCharsets.UTF_8))
        .queryParam("trackerAuthResult", if (errorCode == null) "success" else "error")
        .apply { if (errorCode != null) queryParam("trackerAuthError", errorCode) }
        .build()
        .toUriString()
    return ResponseEntity
      .status(HttpStatus.FOUND)
      .location(URI.create(location))
      .build()
  }

  private fun exchangeCode(
    userId: String,
    tracker: String,
    code: String,
    redirectUri: String,
    codeVerifier: String?,
  ): String? {
    return try {
      val account = userTrackerRepository.findByUserIdAndTracker(userId, tracker) ?: return "not_configured"
      val connected = trackerTokenProvider.completeAuthorization(account, code, redirectUri, codeVerifier)
      mangaScrobblerPlugin.clearIdentityBackoff(userId, tracker)
      trackerConnectionChecker.check(userTrackerRepository.findByUserIdAndTracker(userId, tracker) ?: connected)
      null
    } catch (e: TrackerRateLimitedException) {
      logger.warn { "$tracker authorization of user $userId was rate limited: ${e.message}" }
      "rate_limited"
    } catch (e: TrackerCallException) {
      logger.warn { "$tracker authorization of user $userId failed: ${e.message}" }
      "exchange_failed"
    } catch (e: TrackerSecretException) {
      logger.warn { "$tracker tokens of user $userId could not be stored: ${e.message}" }
      "storage_failed"
    } catch (e: Exception) {
      logger.warn { "$tracker authorization of user $userId failed unexpectedly: ${e.javaClass.name}" }
      "exchange_failed"
    }
  }

  private fun authorizationRedirectUri(tracker: String): String? =
    if (tracker in UserTracker.authorizationCodeTrackers) {
      ServletUriComponentsBuilder
        .fromCurrentContextPath()
        .path("/api/v1/trackers/$tracker/callback")
        .toUriString()
    } else {
      null
    }

  private fun isValidReturnTo(
    returnTo: String,
    contextPath: String,
  ): Boolean =
    returnToPath.matches(returnTo) &&
      !returnTo.startsWith("//") &&
      (returnTo == contextPath || returnTo.startsWith("$contextPath/"))
}
