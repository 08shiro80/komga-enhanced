package org.gotson.komga.infrastructure.httpexchange

import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(
  name = ["management.httpexchanges.recording.enabled"],
  havingValue = "true",
  matchIfMissing = true,
)
class HttpExchangeConfiguration {
  private val httpExchangeRepository = InMemoryHttpExchangeRepository()

  @Bean
  fun httpExchangeRepository() = httpExchangeRepository
}
