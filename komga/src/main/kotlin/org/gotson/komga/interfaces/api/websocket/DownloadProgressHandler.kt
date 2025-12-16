package org.gotson.komga.interfaces.api.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * WebSocket handler for real-time download progress updates.
 *
 * Clients connect to `/api/v1/downloads/progress` to receive live updates
 * about ongoing downloads.
 */
@Component
class DownloadProgressHandler : TextWebSocketHandler() {
  private val sessions = ConcurrentHashMap<String, WebSocketSession>()
  private val objectMapper =
    ObjectMapper().apply {
      registerModule(JavaTimeModule())
    }

  override fun afterConnectionEstablished(session: WebSocketSession) {
    sessions[session.id] = session
    logger.debug { "WebSocket client connected: ${session.id}" }

    // Send initial connection confirmation
    sendToSession(
      session,
      DownloadProgressDto(
        type = "connected",
        downloadId = null,
        mangaTitle = null,
        url = null,
        status = "CONNECTED",
        currentChapter = null,
        totalChapters = null,
        completedChapters = null,
        filesDownloaded = 0,
        percentage = null,
        error = null,
      ),
    )
  }

  override fun afterConnectionClosed(
    session: WebSocketSession,
    status: CloseStatus,
  ) {
    sessions.remove(session.id)
    logger.debug { "WebSocket client disconnected: ${session.id} (status: ${status.code})" }
  }

  override fun handleTextMessage(
    session: WebSocketSession,
    message: TextMessage,
  ) {
    // Handle client messages (e.g., subscription requests)
    logger.debug { "Received message from ${session.id}: ${message.payload}" }

    try {
      val request = objectMapper.readValue(message.payload, Map::class.java)
      when (request["action"]) {
        "subscribe" -> {
          val downloadId = request["downloadId"] as? String
          if (downloadId != null) {
            // Store subscription preference
            session.attributes["subscribedDownloadId"] = downloadId
            logger.debug { "Client ${session.id} subscribed to download: $downloadId" }
          }
        }
        "ping" -> {
          sendToSession(
            session,
            DownloadProgressDto(
              type = "pong",
              downloadId = null,
              mangaTitle = null,
              url = null,
              status = "PONG",
              currentChapter = null,
              totalChapters = null,
              completedChapters = null,
              filesDownloaded = 0,
              percentage = null,
              error = null,
            ),
          )
        }
      }
    } catch (e: Exception) {
      logger.warn { "Failed to parse client message: ${e.message}" }
    }
  }

  override fun handleTransportError(
    session: WebSocketSession,
    exception: Throwable,
  ) {
    logger.error(exception) { "WebSocket transport error for session ${session.id}" }
    sessions.remove(session.id)
  }

  /**
   * Broadcast progress update to all connected clients.
   */
  fun broadcastProgress(progress: DownloadProgressDto) {
    val message = objectMapper.writeValueAsString(progress)
    sessions.values.forEach { session ->
      try {
        if (session.isOpen) {
          session.sendMessage(TextMessage(message))
        }
      } catch (e: Exception) {
        logger.warn { "Failed to send progress to ${session.id}: ${e.message}" }
        sessions.remove(session.id)
      }
    }
    logger.debug { "Broadcast progress: ${progress.downloadId} - ${progress.percentage}%" }
  }

  /**
   * Send progress update to a specific session.
   */
  fun sendProgress(
    sessionId: String,
    progress: DownloadProgressDto,
  ) {
    sessions[sessionId]?.let { session ->
      sendToSession(session, progress)
    }
  }

  /**
   * Send progress update to all clients subscribed to a specific download.
   */
  fun sendProgressToSubscribers(
    downloadId: String,
    progress: DownloadProgressDto,
  ) {
    sessions.values.forEach { session ->
      val subscribedId = session.attributes["subscribedDownloadId"] as? String
      if (subscribedId == null || subscribedId == downloadId) {
        sendToSession(session, progress)
      }
    }
  }

  private fun sendToSession(
    session: WebSocketSession,
    progress: DownloadProgressDto,
  ) {
    try {
      if (session.isOpen) {
        val message = objectMapper.writeValueAsString(progress)
        session.sendMessage(TextMessage(message))
      }
    } catch (e: Exception) {
      logger.warn { "Failed to send to session ${session.id}: ${e.message}" }
      sessions.remove(session.id)
    }
  }

  /**
   * Get count of connected clients.
   */
  fun getConnectedClientCount(): Int = sessions.size
}

/**
 * DTO for download progress updates sent via WebSocket.
 */
data class DownloadProgressDto(
  val type: String = "progress",
  val downloadId: String?,
  val mangaTitle: String?,
  val url: String?,
  val status: String,
  val currentChapter: String?,
  val totalChapters: Int?,
  val completedChapters: Int?,
  val filesDownloaded: Int,
  val percentage: Int?,
  val error: String?,
  val timestamp: LocalDateTime = LocalDateTime.now(),
)
