package org.gotson.komga.infrastructure.scrobbler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.ComicScrobbleRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerStatusRecorderTest {
  private val userTrackerRepository = mockk<UserTrackerRepository>(relaxed = true)
  private val syncStateRepository = mockk<SyncStateRepository>(relaxed = true)
  private val comicScrobbleRepository = mockk<ComicScrobbleRepository>(relaxed = true)
  private val now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } returns now }
  private val recorder = TrackerStatusRecorder(userTrackerRepository, syncStateRepository, comicScrobbleRepository, clock)

  private val account = UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t", UserTracker.KEY_REMOTE_USER_ID to "1"))

  @Test
  fun `given another remote account when recording a success then the sync state of that tracker is reset`() {
    recorder.success(account, "other-name", "2")

    verify { syncStateRepository.deleteByUserIdAndTracker("user", UserTracker.ANILIST) }
    verify {
      userTrackerRepository.updateStatus(
        "user",
        UserTracker.ANILIST,
        mapOf(
          UserTracker.KEY_STATUS to UserTracker.STATUS_OK,
          UserTracker.KEY_STATUS_MESSAGE to null,
          UserTracker.KEY_LAST_SUCCESS_AT to now.toString(),
          UserTracker.KEY_ACCOUNT_NAME to "other-name",
          UserTracker.KEY_REMOTE_USER_ID to "2",
          UserTracker.KEY_IDENTITY_CHECK to null,
        ),
      )
    }
  }

  @Test
  fun `given the same remote account when recording a success then the sync state is kept`() {
    recorder.success(account, "name", "1")

    verify(exactly = 0) { syncStateRepository.deleteByUserIdAndTracker(any(), any()) }
    verify { userTrackerRepository.updateStatus("user", UserTracker.ANILIST, match { it.containsKey(UserTracker.KEY_IDENTITY_CHECK) && it[UserTracker.KEY_IDENTITY_CHECK] == null }) }
  }

  @Test
  fun `given a rejected login when recording a failure then the account must be connected again, a transient failure only records the error`() {
    recorder.failure(account, TrackerAuthException("anilist rejected the token"))
    recorder.failure(account, TrackerUnavailableException("anilist is unavailable"))

    verify {
      userTrackerRepository.updateStatus(
        "user",
        UserTracker.ANILIST,
        mapOf(
          UserTracker.KEY_LAST_ERROR_AT to now.toString(),
          UserTracker.KEY_LAST_ERROR to "anilist rejected the token",
          UserTracker.KEY_STATUS to UserTracker.STATUS_RECONNECT_REQUIRED,
          UserTracker.KEY_STATUS_MESSAGE to "anilist rejected the token",
        ),
      )
    }
    verify {
      userTrackerRepository.updateStatus(
        "user",
        UserTracker.ANILIST,
        mapOf(UserTracker.KEY_LAST_ERROR_AT to now.toString(), UserTracker.KEY_LAST_ERROR to "anilist is unavailable"),
      )
    }
  }

  @Test
  fun `given Metron when resetting the sync state then the scrobbled issues are removed too`() {
    recorder.resetSyncState("user", UserTracker.METRON)

    verify { syncStateRepository.deleteByUserIdAndTracker("user", UserTracker.METRON) }
    verify { comicScrobbleRepository.deleteByUserId("user") }
  }
}
