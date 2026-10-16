package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.UserTracker
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

private const val BASE_URL = "https://kitsu.app/api/edge"
private const val LIST_PAGE_SIZE = 500

private val jsonApi = MediaType.parseMediaType("application/vnd.api+json")

@Component
class KitsuTrackerClient(
  private val trackerClients: TrackerClients,
  private val objectMapper: ObjectMapper,
) : MangaTrackerClient {
  override val tracker = UserTracker.KITSU

  private val userIds = ConcurrentHashMap<String, String>()

  override fun viewer(
    account: UserTracker,
    token: String,
  ): RemoteAccount {
    val user = get(token, "/users?filter[self]=true&fields[users]=name").at("/data/0")
    val id = user.get("id")?.asText(null) ?: throw TrackerRejectedException("Kitsu did not return the current user")
    userIds[cacheKey(account)] = id
    return RemoteAccount(id, user.at("/attributes/name").asText(null) ?: id)
  }

  override fun entry(
    account: UserTracker,
    token: String,
    mediaId: String,
  ): RemoteEntryState {
    val userId = kitsuUserId(account, token)
    val response =
      get(
        token,
        "/library-entries?filter[userId]=$userId&filter[mangaId]=$mediaId&page[limit]=1&include=manga" +
          "&fields[libraryEntries]=progress,status,startedAt,finishedAt,reconsuming,manga&fields[manga]=chapterCount",
      )
    val item = response.at("/data/0")
    val manga =
      response
        .get("included")
        ?.firstOrNull { it.get("type")?.asText() == "manga" }
        ?: get(token, "/manga/$mediaId?fields[manga]=chapterCount").get("data")
    return RemoteEntryState(
      entry = if (item.isMissingNode) null else toEntry(mediaId, item),
      totalChapters = manga?.at("/attributes/chapterCount")?.asInt(0)?.takeIf { it > 0 },
    )
  }

  override fun save(
    account: UserTracker,
    token: String,
    mediaId: String,
    current: RemoteEntryState,
    update: EntryUpdate,
  ) {
    val attributes =
      buildMap<String, Any> {
        put("progress", update.progress)
        update.status?.let { kitsuStatus(it)?.let { status -> put("status", status) } }
        update.startedAt?.let { put("startedAt", it.atStartOfDay().atOffset(ZoneOffset.UTC).toString()) }
        update.completedAt?.let { put("finishedAt", it.atStartOfDay().atOffset(ZoneOffset.UTC).toString()) }
      }
    val entryId = current.entry?.entryId
    val client = trackerClients.client(UserTracker.KITSU)
    if (entryId != null) {
      client
        .patch()
        .uri(URI.create("$BASE_URL/library-entries/$entryId"))
        .header("Authorization", "Bearer $token")
        .contentType(jsonApi)
        .accept(jsonApi)
        .body(mapOf("data" to mapOf("id" to entryId, "type" to "libraryEntries", "attributes" to attributes)))
        .retrieve()
        .toBodilessEntity()
    } else {
      val userId = kitsuUserId(account, token)
      client
        .post()
        .uri(URI.create("$BASE_URL/library-entries"))
        .header("Authorization", "Bearer $token")
        .contentType(jsonApi)
        .accept(jsonApi)
        .body(
          mapOf(
            "data" to
              mapOf(
                "type" to "libraryEntries",
                "attributes" to attributes,
                "relationships" to
                  mapOf(
                    "manga" to mapOf("data" to mapOf("id" to mediaId, "type" to "manga")),
                    "user" to mapOf("data" to mapOf("id" to userId, "type" to "users")),
                  ),
              ),
          ),
        ).retrieve()
        .toBodilessEntity()
    }
  }

  override fun library(
    account: UserTracker,
    token: String,
  ): List<RemoteEntry> {
    val userId = kitsuUserId(account, token)
    val entries = mutableListOf<RemoteEntry>()
    var path: String? =
      "/library-entries?filter[userId]=$userId&filter[kind]=manga&page[limit]=$LIST_PAGE_SIZE" +
        "&fields[libraryEntries]=progress,status,startedAt,finishedAt,reconsuming,manga"
    while (path != null) {
      val page = get(token, path)
      page.get("data")?.forEach { item ->
        val mediaId = item.at("/relationships/manga/data/id").asText(null) ?: return@forEach
        entries.add(toEntry(mediaId, item))
      }
      path =
        page
          .at("/links/next")
          .asText(null)
          ?.takeIf { it.startsWith(BASE_URL) }
          ?.removePrefix(BASE_URL)
    }
    return entries
  }

  private fun kitsuUserId(
    account: UserTracker,
    token: String,
  ): String = userIds[cacheKey(account)] ?: viewer(account, token).id

  private fun cacheKey(account: UserTracker) = "${account.userId}:${account[UserTracker.KEY_USERNAME]}"

  private fun get(
    token: String,
    path: String,
  ): JsonNode {
    val response =
      trackerClients
        .client(UserTracker.KITSU)
        .get()
        .uri(URI.create(BASE_URL + path.replace("[", "%5B").replace("]", "%5D")))
        .header("Authorization", "Bearer $token")
        .accept(jsonApi)
        .retrieve()
        .body(String::class.java)
        ?: throw TrackerUnavailableException("Kitsu returned an empty response")
    return objectMapper.readTree(response)
  }

  private fun toEntry(
    mediaId: String,
    item: JsonNode,
  ): RemoteEntry {
    val attributes = item.get("attributes")
    return RemoteEntry(
      mediaId = mediaId,
      progress = attributes?.get("progress")?.asInt(0) ?: 0,
      status =
        if (attributes?.get("reconsuming")?.asBoolean(false) == true) {
          RemoteStatus.REPEATING
        } else {
          when (attributes?.get("status")?.asText(null)) {
            "current" -> RemoteStatus.CURRENT
            "completed" -> RemoteStatus.COMPLETED
            "on_hold" -> RemoteStatus.PAUSED
            "dropped" -> RemoteStatus.DROPPED
            "planned" -> RemoteStatus.PLANNING
            else -> null
          }
        },
      startedAt = date(attributes?.get("startedAt")),
      completedAt = date(attributes?.get("finishedAt")),
      entryId = item.get("id")?.asText(null),
    )
  }

  private fun date(node: JsonNode?): LocalDate? {
    val text = node?.asText(null) ?: return null
    return try {
      OffsetDateTime.parse(text).toLocalDate()
    } catch (_: DateTimeParseException) {
      null
    }
  }

  private fun kitsuStatus(status: RemoteStatus): String? =
    when (status) {
      RemoteStatus.CURRENT -> "current"
      RemoteStatus.COMPLETED -> "completed"
      RemoteStatus.PAUSED -> "on_hold"
      RemoteStatus.DROPPED -> "dropped"
      RemoteStatus.PLANNING -> "planned"
      RemoteStatus.REPEATING -> null
    }
}
