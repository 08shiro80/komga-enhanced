package org.gotson.komga.interfaces.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.persistence.HistoricalEventRepository
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@Profile("!test")
@Component
class HistoricalEventCleanupController(
  private val historicalEventRepository: HistoricalEventRepository,
) {
  @Scheduled(fixedRate = 86_400_000)
  fun cleanup() {
    // HistoricalEvent timestamps are stored in local wall-clock (LocalDateTime.now()), so the cutoff must
    // be local too — a UTC cutoff would keep events ~offset hours longer than the intended 30-day window.
    val olderThan = LocalDateTime.now().minusDays(30)
    logger.info { "Remove historical events older than $olderThan" }
    historicalEventRepository.deleteOlderThan(olderThan)
  }
}
