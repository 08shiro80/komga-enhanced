package org.gotson.komga.domain.model

data class UserTracker(
  val userId: String,
  val tracker: String,
  val settings: Map<String, String>,
  val unreadableKeys: Set<String> = emptySet(),
) {
  val enabled: Boolean
    get() = settings[KEY_ENABLED]?.toBoolean() ?: true

  val linked: Boolean
    get() = requiredKeyAlternatives[tracker].orEmpty().any { keys -> keys.all { !settings[it].isNullOrBlank() } }

  val reconnectRequired: Boolean
    get() = settings[KEY_STATUS] == STATUS_RECONNECT_REQUIRED

  val active: Boolean
    get() = enabled && linked && !reconnectRequired

  val identityCheckRequired: Boolean
    get() = settings[KEY_IDENTITY_CHECK] == IDENTITY_CHECK_REQUIRED

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

    const val KEY_STATUS = "status"
    const val KEY_STATUS_MESSAGE = "status_message"
    const val KEY_LAST_SUCCESS_AT = "last_success_at"
    const val KEY_LAST_ERROR_AT = "last_error_at"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_ACCOUNT_NAME = "account_name"
    const val KEY_REMOTE_USER_ID = "remote_user_id"
    const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
    const val KEY_IDENTITY_CHECK = "identity_check"

    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "error"
    const val STATUS_RECONNECT_REQUIRED = "reconnect_required"

    const val IDENTITY_CHECK_REQUIRED = "required"

    val trackers = listOf(ANILIST, MAL, KITSU, MANGADEX, METRON)

    val mangaTrackers = listOf(ANILIST, MAL, KITSU, MANGADEX)

    val authorizationCodeTrackers = listOf(ANILIST, MAL)

    val allowedKeys: Map<String, Set<String>> =
      mapOf(
        ANILIST to setOf(KEY_CLIENT_ID, KEY_CLIENT_SECRET),
        MAL to setOf(KEY_CLIENT_ID, KEY_CLIENT_SECRET),
        KITSU to setOf(KEY_USERNAME, KEY_PASSWORD),
        MANGADEX to setOf(KEY_USERNAME, KEY_PASSWORD, KEY_CLIENT_ID, KEY_CLIENT_SECRET, KEY_MAX_CONTENT_RATING),
        METRON to setOf(KEY_TOKEN, KEY_USERNAME, KEY_PASSWORD),
      )

    val requiredKeyAlternatives: Map<String, List<Set<String>>> =
      mapOf(
        ANILIST to listOf(setOf(KEY_TOKEN)),
        MAL to listOf(setOf(KEY_ACCESS_TOKEN)),
        KITSU to listOf(setOf(KEY_USERNAME, KEY_PASSWORD)),
        MANGADEX to listOf(setOf(KEY_USERNAME, KEY_PASSWORD, KEY_CLIENT_ID, KEY_CLIENT_SECRET)),
        METRON to listOf(setOf(KEY_TOKEN), setOf(KEY_USERNAME, KEY_PASSWORD)),
      )

    val secretKeys = setOf(KEY_TOKEN, KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_CLIENT_SECRET, KEY_PASSWORD)

    val credentialKeys = setOf(KEY_TOKEN, KEY_CLIENT_ID, KEY_CLIENT_SECRET, KEY_USERNAME, KEY_PASSWORD)

    val statusKeys =
      setOf(
        KEY_STATUS,
        KEY_STATUS_MESSAGE,
        KEY_LAST_SUCCESS_AT,
        KEY_LAST_ERROR_AT,
        KEY_LAST_ERROR,
        KEY_ACCOUNT_NAME,
        KEY_REMOTE_USER_ID,
        KEY_TOKEN_EXPIRES_AT,
        KEY_IDENTITY_CHECK,
      )
  }
}
