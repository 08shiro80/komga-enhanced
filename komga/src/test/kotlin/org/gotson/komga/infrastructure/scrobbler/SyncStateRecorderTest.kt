package org.gotson.komga.infrastructure.scrobbler

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SyncStateRecorderTest {
  private val syncStateRepository = mockk<SyncStateRepository>(relaxed = true)
  private val userTrackerRepository = mockk<UserTrackerRepository>()
  private val recorder = SyncStateRecorder(syncStateRepository, userTrackerRepository)

  private val existing = SyncState("state", "user", null, "series", UserTracker.ANILIST, progress = 10, status = "CURRENT")

  @BeforeEach
  fun setup() {
    every { userTrackerRepository.exists("user", UserTracker.ANILIST) } returns true
  }

  @Test
  fun `given a deleted tracker account when recording a progress then nothing is written and the account is not loaded`() {
    every { userTrackerRepository.exists("user", UserTracker.ANILIST) } returns false
    every { syncStateRepository.findByUserIdAndSeriesIdAndTracker("user", "series", UserTracker.ANILIST) } returns null

    recorder.record("user", "series", UserTracker.ANILIST, 4)
    recorder.recordRemote("user", "series", UserTracker.ANILIST, 4, "CURRENT")

    verify(exactly = 0) { syncStateRepository.insert(any()) }
    verify(exactly = 0) { syncStateRepository.update(any()) }
    verify(exactly = 0) { userTrackerRepository.findByUserIdAndTracker(any(), any()) }
  }

  @Test
  fun `given a stored progress when recording a lower progress then the higher one is kept`() {
    every { syncStateRepository.findByUserIdAndSeriesIdAndTracker("user", "series", UserTracker.ANILIST) } returns existing
    val updated = slot<SyncState>()
    every { syncStateRepository.update(capture(updated)) } returns Unit

    recorder.record("user", "series", UserTracker.ANILIST, 4)

    assertThat(updated.captured.progress).isEqualTo(10)
  }

  @Test
  fun `given a stored progress when recording a lower remote progress then the remote progress is stored`() {
    every { syncStateRepository.findByUserIdAndSeriesIdAndTracker("user", "series", UserTracker.ANILIST) } returns existing
    val updated = slot<SyncState>()
    every { syncStateRepository.update(capture(updated)) } returns Unit

    recorder.recordRemote("user", "series", UserTracker.ANILIST, 4, "PAUSED")

    assertThat(updated.captured.progress).isEqualTo(4)
    assertThat(updated.captured.status).isEqualTo("PAUSED")
    assertThat(updated.captured.lastUpdateTimestamp).isNotNull()
  }

  @Test
  fun `given no stored progress when recording a remote progress then it is inserted`() {
    every { syncStateRepository.findByUserIdAndSeriesIdAndTracker("user", "series", UserTracker.ANILIST) } returns null
    val inserted = slot<SyncState>()
    every { syncStateRepository.insert(capture(inserted)) } returns Unit

    recorder.recordRemote("user", "series", UserTracker.ANILIST, 4, null)

    assertThat(inserted.captured.progress).isEqualTo(4)
    verify(exactly = 0) { syncStateRepository.update(any()) }
  }
}
