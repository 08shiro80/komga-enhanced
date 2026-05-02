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
    private val GUEST_PATHS =
      listOf(
        "/api/v1/series",
        "/api/v1/books",
        "/api/v1/libraries",
        "/api/v1/users/me",
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
      request.method == "GET" &&
      isGuestPath(request.servletPath)
    ) {
      if (isGuestModeEnabled()) {
        val guestUser = buildGuestUser()
        val principal = KomgaPrincipal(guestUser)
        val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        SecurityContextHolder.getContext().authentication = auth
        guestAuthSet = true
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

  private fun isGuestPath(uri: String): Boolean = GUEST_PATHS.any { uri.startsWith(it) }

  private fun isGuestModeEnabled(): Boolean {
    val settings = clientSettingsDtoDao.findAllGlobal(true)
    return settings[GUEST_SETTING_KEY]?.value == "true"
  }

  private fun buildGuestUser(): KomgaUser {
    val settings = clientSettingsDtoDao.findAllGlobal(true)
    val libraryIds = parseLibraryIds(settings[GUEST_LIBRARIES_KEY]?.value)

    return if (libraryIds.isEmpty()) {
      KomgaUser(
        email = "guest@komga.local",
        password = "",
        roles = setOf(UserRoles.PAGE_STREAMING),
        sharedAllLibraries = true,
        id = "guest",
      )
    } else {
      KomgaUser(
        email = "guest@komga.local",
        password = "",
        roles = setOf(UserRoles.PAGE_STREAMING),
        sharedAllLibraries = false,
        sharedLibrariesIds = libraryIds,
        id = "guest",
      )
    }
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
