package org.gotson.komga.interfaces.api.websocket

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * WebSocket configuration for download progress updates.
 *
 * Registers the download progress handler at `/api/v1/downloads/progress`.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
  private val downloadProgressHandler: DownloadProgressHandler,
) : WebSocketConfigurer {
  override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
    registry
      .addHandler(downloadProgressHandler, "/api/v1/downloads/progress")
      .setAllowedOrigins("*")
  }
}
