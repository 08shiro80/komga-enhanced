package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.UserTracker
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.net.URI
import java.time.LocalDate

private const val LIST_PAGE_SIZE = 1000
private const val BASE_URL = "https://api.myanimelist.net"

@Component
class MalTrackerClient(
  private val trackerClients: TrackerClients,
  private val objectMapper: ObjectMapper,
) : MangaTrackerClient {
  override val tracker = UserTracker.MAL

  override fun viewer(
    account: UserTracker,
    token: String,
  ): RemoteAccount {
    val user = get(token, "/v2/users/@me")
    val id = user.get("id")?.asText(null) ?: throw TrackerRejectedException("MyAnimeList did not return the current user")
    return RemoteAccount(id, user.get("name")?.asText(null) ?: id)
  }

  override fun entry(
    account: UserTracker,
    token: String,
    mediaId: String,
  ): RemoteEntryState {
    val manga = get(token, "/v2/manga/$mediaId?fields=num_chapters,my_list_status")
    val listStatus = manga.get("my_list_status")
    return RemoteEntryState(
      entry = listStatus?.takeIf { it.isObject }?.let { toEntry(mediaId, it) },
      totalChapters = manga.get("num_chapters")?.asInt(0)?.takeIf { it > 0 },
    )
  }

  override fun save(
    account: UserTracker,
    token: String,
    mediaId: String,
    current: RemoteEntryState,
    update: EntryUpdate,
  ) {
    val body =
      formEncode(
        "num_chapters_read" to update.progress.toString(),
        "status" to update.status?.let { malStatus(it) },
      )
    trackerClients
      .client(UserTracker.MAL)
      .patch()
      .uri("/v2/manga/$mediaId/my_list_status")
      .header("Authorization", "Bearer $token")
      .contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .body(body)
      .retrieve()
      .toBodilessEntity()
  }

  override fun library(
    account: UserTracker,
    token: String,
  ): List<RemoteEntry> {
    val entries = mutableListOf<RemoteEntry>()
    var path: String? = "/v2/users/@me/mangalist?fields=list_status&limit=$LIST_PAGE_SIZE&nsfw=true"
    while (path != null) {
      val page = get(token, path)
      page.get("data")?.forEach { item ->
        val mediaId = item.at("/node/id").asText(null) ?: return@forEach
        val status = item.get("list_status") ?: return@forEach
        entries.add(toEntry(mediaId, status))
      }
      path =
        page
          .at("/paging/next")
          .asText(null)
          ?.takeIf { it.startsWith(BASE_URL) }
          ?.removePrefix(BASE_URL)
    }
    return entries
  }

  private fun get(
    token: String,
    path: String,
  ): JsonNode {
    val response =
      trackerClients
        .client(UserTracker.MAL)
        .get()
        .uri(URI.create(BASE_URL + path))
        .header("Authorization", "Bearer $token")
        .retrieve()
        .body(String::class.java)
        ?: throw TrackerUnavailableException("MyAnimeList returned an empty response")
    return objectMapper.readTree(response)
  }

  private fun toEntry(
    mediaId: String,
    node: JsonNode,
  ) = RemoteEntry(
    mediaId = mediaId,
    progress = node.get("num_chapters_read")?.asInt(0) ?: 0,
    status =
      if (node.get("is_rereading")?.asBoolean(false) == true) {
        RemoteStatus.REPEATING
      } else {
        when (node.get("status")?.asText(null)) {
          "reading" -> RemoteStatus.CURRENT
          "completed" -> RemoteStatus.COMPLETED
          "on_hold" -> RemoteStatus.PAUSED
          "dropped" -> RemoteStatus.DROPPED
          "plan_to_read" -> RemoteStatus.PLANNING
          else -> null
        }
      },
    startedAt = node.get("start_date")?.asText(null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    completedAt = node.get("finish_date")?.asText(null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
  )

  private fun malStatus(status: RemoteStatus): String? =
    when (status) {
      RemoteStatus.CURRENT -> "reading"
      RemoteStatus.COMPLETED -> "completed"
      RemoteStatus.PAUSED -> "on_hold"
      RemoteStatus.DROPPED -> "dropped"
      RemoteStatus.PLANNING -> "plan_to_read"
      RemoteStatus.REPEATING -> null
    }
}
