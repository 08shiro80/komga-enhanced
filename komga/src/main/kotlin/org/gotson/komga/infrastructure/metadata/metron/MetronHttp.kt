package org.gotson.komga.infrastructure.metadata.metron

import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Shared HTTP client defaults for Metron (metron.cloud) — used by metadata search
 * and comic scrobbler so slow/unresponsive API calls do not hang worker threads forever.
 */
object MetronHttp {
  private const val DEFAULT_BASE = "https://metron.cloud"

  fun restClient(baseUrl: String = DEFAULT_BASE): RestClient {
    val factory =
      JdkClientHttpRequestFactory().apply {
        setReadTimeout(Duration.ofSeconds(90))
      }
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build()
  }
}
