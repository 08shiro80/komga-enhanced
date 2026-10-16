package org.gotson.komga.domain.model

data class UserTracker(
  val userId: String,
  val tracker: String,
  val settings: Map<String, String>,
) {
  val enabled: Boolean
    get() = settings[KEY_ENABLED]?.toBoolean() ?: true

  val linked: Boolean
    get() = requiredKeys[tracker].orEmpty().all { !settings[it].isNullOrBlank() }

  val active: Boolean
    get() = enabled && linked

  operator fun get(key: String): String? = settings[key]?.takeIf { it.isNotBlank() }

  companion object {
    const val ANILIST = "anilist"
    const val MAL = "mal"
    const val KITSU = "kitsu"
    const val MANGADEX = "mangadex"
    const val METRON = "metron"

    const val KEY_ENABLED = "enabled"
    const val KEY_TOKEN = "token"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_CLIENT_ID = "client_id"
    const val KEY_CLIENT_SECRET = "client_secret"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD = "password"
    const val KEY_MAX_CONTENT_RATING = "max_content_rating"

    val trackers = listOf(ANILIST, MAL, KITSU, MANGADEX, METRON)

    val mangaTrackers = listOf(ANILIST, MAL, KITSU, MANGADEX)

    val allowedKeys: Map<String, Set<String>> =
      mapOf(
        ANILIST to setOf(KEY_TOKEN),
        MAL to setOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_CLIENT_ID, KEY_CLIENT_SECRET),
        KITSU to setOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_CLIENT_ID, KEY_CLIENT_SECRET),
        MANGADEX to setOf(KEY_USERNAME, KEY_PASSWORD, KEY_CLIENT_ID, KEY_CLIENT_SECRET, KEY_MAX_CONTENT_RATING),
        METRON to setOf(KEY_USERNAME, KEY_PASSWORD),
      )

    val requiredKeys: Map<String, Set<String>> =
      mapOf(
        ANILIST to setOf(KEY_TOKEN),
        MAL to setOf(KEY_ACCESS_TOKEN),
        KITSU to setOf(KEY_ACCESS_TOKEN),
        MANGADEX to setOf(KEY_USERNAME, KEY_PASSWORD, KEY_CLIENT_ID, KEY_CLIENT_SECRET),
        METRON to setOf(KEY_USERNAME, KEY_PASSWORD),
      )

    val secretKeys = setOf(KEY_TOKEN, KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_CLIENT_SECRET, KEY_PASSWORD)
  }
}
