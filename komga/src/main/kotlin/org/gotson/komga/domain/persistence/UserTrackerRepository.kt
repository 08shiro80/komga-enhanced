package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.UserTracker

interface UserTrackerRepository {
  fun findByUserId(userId: String): Collection<UserTracker>

  fun findByUserIdAndTracker(
    userId: String,
    tracker: String,
  ): UserTracker?

  fun exists(
    userId: String,
    tracker: String,
  ): Boolean

  fun findByTracker(tracker: String): Collection<UserTracker>

  fun save(userTracker: UserTracker)

  fun updateRefreshedTokens(
    userId: String,
    tracker: String,
    usedAccessToken: String?,
    usedRefreshToken: String,
    accessToken: String,
    refreshToken: String?,
  ): Boolean

  fun updateStatus(
    userId: String,
    tracker: String,
    values: Map<String, String?>,
  ): Boolean

  fun encryptPlaintextSecrets(): Int

  fun countEncryptedSecrets(): Int

  fun delete(
    userId: String,
    tracker: String,
  )
}
