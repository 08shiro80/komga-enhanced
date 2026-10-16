package org.gotson.komga.interfaces.api.rest

import com.github.benmanes.caffeine.cache.Caffeine
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.rest.dto.GalleryDlForkCommitDto
import org.gotson.komga.interfaces.api.rest.dto.GalleryDlForkUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.GithubCommitDto
import org.gotson.komga.interfaces.api.rest.dto.GithubReleaseDto
import org.gotson.komga.interfaces.api.rest.dto.ReleaseDto
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val GITHUB_API_UPSTREAM = "https://api.github.com/repos/gotson/komga/releases"
private const val GITHUB_API_FORK = "https://api.github.com/repos/08shiro80/komga-enhanced/releases"
private const val GITHUB_API_GALLERY_DL_COMMITS = "https://api.github.com/repos/08shiro80/gallery-dl-komga/commits"
private const val GALLERY_DL_FORK_SHA_FILE = "/opt/gallery-dl-fork-sha"

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("api/v1/releases", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = OpenApiConfiguration.TagNames.RELEASES)
class ReleaseController(
  webClientBuilder: WebClient.Builder,
) {
  private val webClient = webClientBuilder.build()

  private val cache =
    Caffeine
      .newBuilder()
      .expireAfterAccess(1, TimeUnit.HOURS)
      .build<String, List<GithubReleaseDto>>()

  private val commitsCache =
    Caffeine
      .newBuilder()
      .expireAfterAccess(15, TimeUnit.MINUTES)
      .build<String, List<GithubCommitDto>>()

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List upstream releases")
  fun getReleases(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): List<ReleaseDto> =
    cache
      .get("releases") { fetchGitHubReleases(GITHUB_API_UPSTREAM) }
      .let { releases ->
        releases.mapIndexed { index, ghRel ->
          ReleaseDto(
            ghRel.tagName,
            ghRel.publishedAt,
            ghRel.htmlUrl,
            index == 0,
            ghRel.prerelease,
            ghRel.body,
          )
        }
      }

  @GetMapping("fork")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List fork releases")
  fun getForkReleases(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): List<ReleaseDto> =
    cache
      .get("forkReleases") { fetchGitHubReleases(GITHUB_API_FORK) }
      .let { releases ->
        releases.mapIndexed { index, ghRel ->
          ReleaseDto(
            ghRel.tagName,
            ghRel.publishedAt,
            ghRel.htmlUrl,
            index == 0,
            ghRel.prerelease,
            ghRel.body,
          )
        }
      }

  fun fetchGitHubReleases(apiUrl: String): List<GithubReleaseDto> {
    val response =
      webClient
        .get()
        .uri(apiUrl) {
          it.queryParam("per_page", 20).build()
        }.retrieve()
        .toEntity(object : ParameterizedTypeReference<List<GithubReleaseDto>>() {})
        .block()
    return response?.body ?: emptyList()
  }

  @GetMapping("gallery-dl-fork")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List gallery-dl-fork commits with installed-SHA comparison")
  fun getGalleryDlForkUpdates(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): GalleryDlForkUpdateDto {
    val installedSha = readInstalledGalleryDlSha()
    val commits =
      commitsCache.get("gallery-dl-fork") { fetchGitHubCommits(GITHUB_API_GALLERY_DL_COMMITS) }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    val behindCount =
      if (installedSha == null) {
        -1
      } else {
        val idx = commits.indexOfFirst { shaMatchesInstalled(it.sha, installedSha) }
        if (idx < 0) commits.size else idx
      }

    val mapped =
      commits.map { c ->
        val rawAuthor = c.commit.author?.name
        GalleryDlForkCommitDto(
          sha = c.sha,
          shortSha = c.sha.take(7),
          message =
            c.commit.message
              .lineSequence()
              .first(),
          author = if (rawAuthor == "Shirogane") "Kasch_X" else rawAuthor,
          date = c.commit.author?.date ?: java.time.ZonedDateTime.now(),
          url = c.htmlUrl,
          installed = shaMatchesInstalled(c.sha, installedSha),
        )
      }

    return GalleryDlForkUpdateDto(
      installedSha = installedSha,
      behindCount = behindCount,
      commits = mapped,
    )
  }

  private fun shaMatchesInstalled(
    commitSha: String,
    installedSha: String?,
  ): Boolean = installedSha != null && commitSha.startsWith(installedSha, ignoreCase = true)

  private fun fetchGitHubCommits(apiUrl: String): List<GithubCommitDto> {
    val response =
      webClient
        .get()
        .uri(apiUrl) {
          it.queryParam("per_page", 30).build()
        }.retrieve()
        .toEntity(object : ParameterizedTypeReference<List<GithubCommitDto>>() {})
        .block()
    return response?.body ?: emptyList()
  }

  private fun readInstalledGalleryDlSha(): String? {
    val path = Path.of(GALLERY_DL_FORK_SHA_FILE)
    if (!Files.isRegularFile(path)) return null
    return runCatching {
      Files.readString(path).trim().takeIf { it.length in 7..64 }
    }.getOrNull()
  }
}
