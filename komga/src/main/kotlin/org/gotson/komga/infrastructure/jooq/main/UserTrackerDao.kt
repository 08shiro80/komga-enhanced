package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.UserTrackerRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class UserTrackerDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  UserTrackerRepository {
  private val ut = Tables.USER_TRACKER

  override fun findByUserId(userId: String): Collection<UserTracker> =
    dslRO
      .selectFrom(ut)
      .where(ut.USER_ID.eq(userId))
      .fetch()
      .toDomain()

  override fun findByUserIdAndTracker(
    userId: String,
    tracker: String,
  ): UserTracker? =
    dslRO
      .selectFrom(ut)
      .where(ut.USER_ID.eq(userId))
      .and(ut.TRACKER.eq(tracker))
      .fetch()
      .toDomain()
      .firstOrNull()

  override fun findByTracker(tracker: String): Collection<UserTracker> =
    dslRO
      .selectFrom(ut)
      .where(ut.TRACKER.eq(tracker))
      .fetch()
      .toDomain()

  @Transactional
  override fun save(userTracker: UserTracker) {
    dslRW
      .deleteFrom(ut)
      .where(ut.USER_ID.eq(userTracker.userId))
      .and(ut.TRACKER.eq(userTracker.tracker))
      .execute()
    userTracker.settings.forEach { (key, value) ->
      dslRW
        .insertInto(ut, ut.USER_ID, ut.TRACKER, ut.CONFIG_KEY, ut.CONFIG_VALUE)
        .values(userTracker.userId, userTracker.tracker, key, value)
        .execute()
    }
  }

  @Transactional
  override fun updateRefreshedTokens(
    userId: String,
    tracker: String,
    usedAccessToken: String?,
    usedRefreshToken: String,
    accessToken: String,
    refreshToken: String?,
  ): Boolean {
    val stored =
      dslRW
        .select(ut.CONFIG_KEY, ut.CONFIG_VALUE)
        .from(ut)
        .where(ut.USER_ID.eq(userId))
        .and(ut.TRACKER.eq(tracker))
        .and(ut.CONFIG_KEY.`in`(UserTracker.KEY_ACCESS_TOKEN, UserTracker.KEY_REFRESH_TOKEN))
        .fetchMap(ut.CONFIG_KEY, ut.CONFIG_VALUE)
    if (stored[UserTracker.KEY_REFRESH_TOKEN] != usedRefreshToken || stored[UserTracker.KEY_ACCESS_TOKEN] != usedAccessToken) return false
    updateValue(userId, tracker, UserTracker.KEY_ACCESS_TOKEN, accessToken)
    if (refreshToken != null) updateValue(userId, tracker, UserTracker.KEY_REFRESH_TOKEN, refreshToken)
    return true
  }

  private fun updateValue(
    userId: String,
    tracker: String,
    key: String,
    value: String,
  ) {
    dslRW
      .update(ut)
      .set(ut.CONFIG_VALUE, value)
      .set(ut.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(ut.USER_ID.eq(userId))
      .and(ut.TRACKER.eq(tracker))
      .and(ut.CONFIG_KEY.eq(key))
      .execute()
  }

  override fun delete(
    userId: String,
    tracker: String,
  ) {
    dslRW
      .deleteFrom(ut)
      .where(ut.USER_ID.eq(userId))
      .and(ut.TRACKER.eq(tracker))
      .execute()
  }

  private fun Collection<UserTrackerRecord>.toDomain(): List<UserTracker> =
    groupBy { it.userId to it.tracker }
      .map { (key, records) ->
        UserTracker(
          userId = key.first,
          tracker = key.second,
          settings = records.mapNotNull { r -> r.configValue?.let { r.configKey to it } }.toMap(),
        )
      }
}
