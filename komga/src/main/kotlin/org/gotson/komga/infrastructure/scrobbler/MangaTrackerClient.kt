package org.gotson.komga.infrastructure.scrobbler

import org.gotson.komga.domain.model.UserTracker
import java.time.LocalDate

enum class RemoteStatus {
  CURRENT,
  PLANNING,
  COMPLETED,
  PAUSED,
  DROPPED,
  REPEATING,
}

data class RemoteAccount(
  val id: String,
  val name: String,
)

data class RemoteEntry(
  val mediaId: String,
  val progress: Int,
  val status: RemoteStatus?,
  val startedAt: LocalDate?,
  val completedAt: LocalDate?,
  val entryId: String? = null,
)

data class RemoteEntryState(
  val entry: RemoteEntry?,
  val totalChapters: Int?,
)

data class EntryUpdate(
  val progress: Int,
  val status: RemoteStatus?,
  val startedAt: LocalDate?,
  val completedAt: LocalDate?,
)

interface MangaTrackerClient {
  val tracker: String

  fun viewer(
    account: UserTracker,
    token: String,
  ): RemoteAccount

  fun entry(
    account: UserTracker,
    token: String,
    mediaId: String,
  ): RemoteEntryState

  fun save(
    account: UserTracker,
    token: String,
    mediaId: String,
    current: RemoteEntryState,
    update: EntryUpdate,
  )

  fun library(
    account: UserTracker,
    token: String,
  ): List<RemoteEntry>
}
