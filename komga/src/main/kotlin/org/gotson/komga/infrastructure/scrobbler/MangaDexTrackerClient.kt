package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.UserTracker
import org.springframework.http.MediaType
import org.springframework.stereotype.Component

@Component
class MangaDexTrackerClient(
  private val trackerClients: TrackerClients,
  private val objectMapper: ObjectMapper,
) {
  fun viewer(token: String): RemoteAccount {
    val user =
      objectMapper
        .readTree(
          trackerClients
            .client(UserTracker.MANGADEX)
            .get()
            .uri("/user/me")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(String::class.java)
            ?: throw TrackerUnavailableException("MangaDex returned an empty response"),
        ).get("data")
    val id = user?.get("id")?.asText(null) ?: throw TrackerRejectedException("MangaDex did not return the current user")
    return RemoteAccount(id, user.at("/attributes/username").asText(null) ?: id)
  }

  fun readChapterIds(
    token: String,
    mangaId: String,
  ): Set<String> {
    val response =
      trackerClients
        .client(UserTracker.MANGADEX)
        .get()
        .uri("/manga/{id}/read", mangaId)
        .header("Authorization", "Bearer $token")
        .retrieve()
        .body(String::class.java)
        ?: return emptySet()
    return objectMapper
      .readTree(response)
      .get("data")
      ?.mapNotNull { it.asText(null) }
      ?.toSet()
      .orEmpty()
  }

  fun markRead(
    token: String,
    mangaId: String,
    chapterIds: Collection<String>,
  ) {
    trackerClients
      .client(UserTracker.MANGADEX)
      .post()
      .uri("/manga/{id}/read", mangaId)
      .header("Authorization", "Bearer $token")
      .contentType(MediaType.APPLICATION_JSON)
      .body(mapOf("chapterIdsRead" to chapterIds.toList()))
      .retrieve()
      .toBodilessEntity()
  }
}
