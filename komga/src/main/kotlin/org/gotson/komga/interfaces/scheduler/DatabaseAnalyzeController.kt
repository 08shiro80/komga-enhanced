package org.gotson.komga.interfaces.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Profile("!test")
@Component
class DatabaseAnalyzeController(
  @Qualifier("dslContextRW") private val dslRW: DSLContext,
) {
  // ANALYZE refreshes SQLite's query-planner statistics (sqlite_stat1). Without it the planner chooses
  // index strategies blind (confirmed: sqlite_stat1 absent on the live DB). fixedRate runs the first pass
  // shortly after startup — post-Flyway thanks to the initialDelay and because the scheduler only starts
  // once the context is ready — then weekly to keep statistics fresh as the library grows.
  @Scheduled(fixedRate = 604_800_000, initialDelay = 120_000)
  fun analyze() {
    logger.info { "Running ANALYZE to refresh SQLite query-planner statistics" }
    dslRW.execute("ANALYZE")
  }
}
