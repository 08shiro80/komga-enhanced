package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.UserTracker
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.time.LocalDate

private const val LIST_CHUNK_SIZE = 500

@Component
class AnilistTrackerClient(
  private val trackerClients: TrackerClients,
  private val objectMapper: ObjectMapper,
) : MangaTrackerClient {
  override val tracker = UserTracker.ANILIST

  override fun viewer(
    account: UserTracker,
    token: String,
  ): RemoteAccount {
    val viewer = query(token, "{ Viewer { id name } }", emptyMap()).at("/data/Viewer")
    val id = viewer.get("id")?.asText(null) ?: throw TrackerRejectedException("AniList did not return the current user")
    return RemoteAccount(id, viewer.get("name")?.asText(null) ?: id)
  }

  override fun entry(
    account: UserTracker,
    token: String,
    mediaId: String,
  ): RemoteEntryState {
    val media =
      query(
        token,
        """
        query (${'$'}id: Int) {
          Media(id: ${'$'}id) {
            chapters
            mediaListEntry { id progress status startedAt { year month day } completedAt { year month day } }
          }
        }
        """.trimIndent(),
        mapOf("id" to mediaId.toInt()),
      ).at("/data/Media")
    if (media.isMissingNode || media.isNull) throw TrackerNotFoundException("AniList has no manga $mediaId")
    val listEntry = media.get("mediaListEntry")
    return RemoteEntryState(
      entry =
        if (listEntry == null || listEntry.isNull) {
          null
        } else {
          RemoteEntry(
            mediaId = mediaId,
            progress = listEntry.get("progress")?.asInt(0) ?: 0,
            status = listEntry.get("status")?.asText(null)?.let { runCatching { RemoteStatus.valueOf(it) }.getOrNull() },
            startedAt = fuzzyDate(listEntry.get("startedAt")),
            completedAt = fuzzyDate(listEntry.get("completedAt")),
            entryId = listEntry.get("id")?.asText(null),
          )
        },
      totalChapters = media.get("chapters")?.takeIf { it.isInt && it.asInt() > 0 }?.asInt(),
    )
  }

  override fun save(
    account: UserTracker,
    token: String,
    mediaId: String,
    current: RemoteEntryState,
    update: EntryUpdate,
  ) {
    val variables =
      buildMap<String, Any> {
        put("mediaId", mediaId.toInt())
        put("progress", update.progress)
        update.status?.let { put("status", it.name) }
        update.startedAt?.let { put("startedAt", fuzzyDateInput(it)) }
        update.completedAt?.let { put("completedAt", fuzzyDateInput(it)) }
      }
    val types =
      mapOf(
        "mediaId" to "Int",
        "progress" to "Int",
        "status" to "MediaListStatus",
        "startedAt" to "FuzzyDateInput",
        "completedAt" to "FuzzyDateInput",
      )
    val declarations = variables.keys.joinToString(", ") { "\$$it: ${types.getValue(it)}" }
    val arguments = variables.keys.joinToString(", ") { "$it: \$$it" }
    query(token, "mutation ($declarations) { SaveMediaListEntry($arguments) { id progress } }", variables)
  }

  override fun library(
    account: UserTracker,
    token: String,
  ): List<RemoteEntry> {
    val userId = account[UserTracker.KEY_REMOTE_USER_ID]?.toIntOrNull() ?: viewer(account, token).id.toInt()
    val entries = mutableListOf<RemoteEntry>()
    var chunk = 1
    while (true) {
      val collection =
        query(
          token,
          """
          query (${'$'}userId: Int, ${'$'}chunk: Int, ${'$'}perChunk: Int) {
            MediaListCollection(userId: ${'$'}userId, type: MANGA, chunk: ${'$'}chunk, perChunk: ${'$'}perChunk) {
              hasNextChunk
              lists { entries { mediaId progress status startedAt { year month day } completedAt { year month day } } }
            }
          }
          """.trimIndent(),
          mapOf("userId" to userId, "chunk" to chunk, "perChunk" to LIST_CHUNK_SIZE),
        ).at("/data/MediaListCollection")
      collection.get("lists")?.forEach { list ->
        list.get("entries")?.forEach { e ->
          val mediaId = e.get("mediaId")?.asText(null) ?: return@forEach
          entries.add(
            RemoteEntry(
              mediaId = mediaId,
              progress = e.get("progress")?.asInt(0) ?: 0,
              status = e.get("status")?.asText(null)?.let { runCatching { RemoteStatus.valueOf(it) }.getOrNull() },
              startedAt = fuzzyDate(e.get("startedAt")),
              completedAt = fuzzyDate(e.get("completedAt")),
            ),
          )
        }
      }
      if (collection.get("hasNextChunk")?.asBoolean(false) != true) break
      chunk++
    }
    return entries.distinctBy { it.mediaId }
  }

  private fun query(
    token: String,
    query: String,
    variables: Map<String, Any>,
  ): JsonNode {
    val response =
      trackerClients
        .client(UserTracker.ANILIST)
        .post()
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .body(mapOf("query" to query, "variables" to variables))
        .retrieve()
        .body(String::class.java)
        ?: throw TrackerUnavailableException("AniList returned an empty response")
    val json = objectMapper.readTree(response)
    val errors = json.get("errors")
    if (errors != null && errors.isArray && errors.size() > 0) {
      val message = errors.joinToString("; ") { it.get("message")?.asText("") ?: "" }
      val statuses = errors.mapNotNull { it.get("status")?.asInt() }
      throw when {
        message.contains("Invalid token") || 401 in statuses -> TrackerUnauthorizedException("AniList rejected the token: $message")
        429 in statuses -> TrackerRateLimitedException("AniList rate limit reached: $message", null)
        404 in statuses -> TrackerNotFoundException("AniList: $message")
        statuses.any { it >= 500 } -> TrackerUnavailableException("AniList error: $message")
        else -> TrackerRejectedException("AniList error: $message")
      }
    }
    return json
  }

  private fun fuzzyDate(node: JsonNode?): LocalDate? {
    val year = node?.get("year")?.takeIf { it.isInt }?.asInt() ?: return null
    val month = node.get("month")?.takeIf { it.isInt }?.asInt() ?: 1
    val day = node.get("day")?.takeIf { it.isInt }?.asInt() ?: 1
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
  }

  private fun fuzzyDateInput(date: LocalDate) = mapOf("year" to date.year, "month" to date.monthValue, "day" to date.dayOfMonth)
}
