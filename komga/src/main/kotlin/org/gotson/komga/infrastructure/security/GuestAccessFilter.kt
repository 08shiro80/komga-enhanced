package org.gotson.komga.infrastructure.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.UserRoles
import org.gotson.komga.infrastructure.jooq.main.ClientSettingsDtoDao
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

private const val GUEST_SETTING_KEY = "webui.guest_access"
private const val GUEST_LIBRARIES_KEY = "webui.guest_libraries"

class GuestAccessFilter(
  private val clientSettingsDtoDao: ClientSettingsDtoDao,
) : OncePerRequestFilter() {
  companion object {
    // Read-only GET paths a guest may reach (prefix match). Covers both the Vue2 (v1) UI and the
    // next-ui Vue3 UI (v2 users/me + referential for filter panels, user client-settings).
    private val GUEST_GET_PATHS =
      listOf(
        "/api/v1/series",
        "/api/v1/books",
        "/api/v1/libraries",
        "/api/v1/users/me",
        "/api/v2/users/me",
        "/api/v2/genres",
        "/api/v2/tags",
        "/api/v2/authors",
        "/api/v2/publishers",
        "/api/v2/languages",
        "/api/v2/age-ratings",
        "/api/v2/sharing-labels",
        "/api/v2/series/release-years",
        "/api/v1/client-settings/user/list",
      )

    // Read-only search POSTs (next-ui series/book listing). Exact match only — a prefix would also
    // let mutating POSTs under /api/v1/series/{id}/... through.
    private val GUEST_POST_PATHS =
      listOf(
        "/api/v1/series/list",
        "/api/v1/books/list",
      )

    private val mapper = jacksonObjectMapper()
  }

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    filterChain: FilterChain,
  ) {
    var guestAuthSet = false
    if (SecurityContextHolder.getContext().authentication == null &&
      isGuestPath(request)
    ) {
      // Load global settings once and reuse for both the enabled-check and the library scope.
      val settings = clientSettingsDtoDao.findAllGlobal(true)
      if (settings[GUEST_SETTING_KEY]?.value == "true") {
        val guestUser = buildGuestUser(settings[GUEST_LIBRARIES_KEY]?.value)
        if (guestUser != null) {
          val principal = KomgaPrincipal(guestUser)
          val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
          SecurityContextHolder.getContext().authentication = auth
          guestAuthSet = true
        }
      }
    }
    try {
      filterChain.doFilter(request, response)
    } finally {
      if (guestAuthSet) {
        SecurityContextHolder.clearContext()
      }
    }
  }

  private fun isGuestPath(request: HttpServletRequest): Boolean =
    when (request.method) {
      "GET" -> GUEST_GET_PATHS.any { request.servletPath.startsWith(it) }
      "POST" -> GUEST_POST_PATHS.any { request.servletPath == it }
      else -> false
    }

  private fun buildGuestUser(librariesValue: String?): KomgaUser? {
    val libraryIds = parseLibraryIds(librariesValue)

    // Fail-closed: with no libraries explicitly configured, guest access grants nothing. To allow
    // everything the admin must explicitly select all libraries. sharedAllLibraries is never set
    // here so guests are always bounded to the configured set.
    if (libraryIds.isEmpty()) return null

    return KomgaUser(
      email = "guest@komga.local",
      password = "",
      roles = setOf(UserRoles.PAGE_STREAMING),
      sharedAllLibraries = false,
      sharedLibrariesIds = libraryIds,
      id = "guest",
    )
  }

  private fun parseLibraryIds(value: String?): Set<String> {
    if (value.isNullOrBlank()) return emptySet()
    return try {
      mapper.readValue<List<String>>(value).toSet()
    } catch (_: Exception) {
      emptySet()
    }
  }
}
