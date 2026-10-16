package org.gotson.komga.infrastructure.scrobbler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.ComicScrobbleRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.comicscrobbler.MetronTrackerClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerConnectionCheckerTest {
  private val userTrackerRepository = mockk<UserTrackerRepository>(relaxed = true)
  private val syncStateRepository = mockk<SyncStateRepository>(relaxed = true)
  private val trackerTokenProvider = mockk<TrackerTokenProvider>()
  private val anilistClient =
    mockk<MangaTrackerClient> {
      every { tracker } returns UserTracker.ANILIST
    }
  private val checker =
    TrackerConnectionChecker(
      trackerTokenProvider,
      TrackerStatusRecorder(userTrackerRepository, syncStateRepository, mockk<ComicScrobbleRepository>(relaxed = true)),
      mockk<MangaDexTrackerClient>(),
      mockk<MetronTrackerClient>(),
      mockk<TrackerRateLimiter>(),
      listOf(anilistClient),
    )

  private val account =
    UserTracker(
      "user",
      UserTracker.ANILIST,
      mapOf(
        UserTracker.KEY_TOKEN to "t",
        UserTracker.KEY_REMOTE_USER_ID to "1",
        UserTracker.KEY_ACCOUNT_NAME to "reader",
        UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED,
      ),
    )

  @BeforeEach
  fun setup() {
    every { trackerTokenProvider.withToken<Any?>(any(), any()) } answers { secondArg<(String) -> Any?>().invoke("t") }
  }

  @Test
  fun `given the same remote account when confirming the identity then nothing is reset and the marker is removed`() {
    every { anilistClient.viewer(account, "t") } returns RemoteAccount("1", "reader")

    val confirmed = checker.confirmIdentity(account)

    assertThat(confirmed.identityCheckRequired).isFalse()
    assertThat(confirmed[UserTracker.KEY_REMOTE_USER_ID]).isEqualTo("1")
    verify(exactly = 0) { syncStateRepository.deleteByUserIdAndTracker(any(), any()) }
    verify {
      userTrackerRepository.updateStatus(
        "user",
        UserTracker.ANILIST,
        match { it[UserTracker.KEY_REMOTE_USER_ID] == "1" && it.containsKey(UserTracker.KEY_IDENTITY_CHECK) && it[UserTracker.KEY_IDENTITY_CHECK] == null },
      )
    }
  }

  @Test
  fun `given another remote account when confirming the identity then the sync state is reset and the new identity is stored`() {
    every { anilistClient.viewer(account, "t") } returns RemoteAccount("2", "other")

    val confirmed = checker.confirmIdentity(account)

    assertThat(confirmed.identityCheckRequired).isFalse()
    assertThat(confirmed[UserTracker.KEY_REMOTE_USER_ID]).isEqualTo("2")
    assertThat(confirmed[UserTracker.KEY_ACCOUNT_NAME]).isEqualTo("other")
    verify { syncStateRepository.deleteByUserIdAndTracker("user", UserTracker.ANILIST) }
    verify {
      userTrackerRepository.updateStatus(
        "user",
        UserTracker.ANILIST,
        match {
          it[UserTracker.KEY_REMOTE_USER_ID] == "2" &&
            it[UserTracker.KEY_ACCOUNT_NAME] == "other" &&
            it.containsKey(UserTracker.KEY_IDENTITY_CHECK) &&
            it[UserTracker.KEY_IDENTITY_CHECK] == null
        },
      )
    }
  }

  @Test
  fun `given a failing identity request when confirming the identity then the marker and the sync state are kept`() {
    val failure = TrackerRateLimitedException("anilist rate limit reached", null)
    every { trackerTokenProvider.withToken<Any?>(any(), any()) } throws failure

    assertThatThrownBy { checker.confirmIdentity(account) }.isSameAs(failure)

    verify(exactly = 0) { syncStateRepository.deleteByUserIdAndTracker(any(), any()) }
    verify(exactly = 0) { userTrackerRepository.updateStatus(any(), any(), any()) }
  }
}
