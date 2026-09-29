<template>
  <v-container fluid>
    <v-skeleton-loader
      v-if="isLoading"
      type="heading, text, paragraph@3"
    />

    <EmptyStateNetworkError v-else-if="error" />

    <template v-else>
      <div class="mb-4 d-flex ga-2">
        <v-chip
          label
          color="primary"
        >
          {{ $formatMessage(baseVersionLabel, { version: baseVersion }) }}
        </v-chip>
        <v-chip
          v-if="forkVersion"
          label
          color="secondary"
        >
          {{ $formatMessage(forkVersionLabel, { version: forkVersion }) }}
        </v-chip>
      </div>

      <v-tabs
        v-model="tab"
        class="mb-4"
      >
        <v-tab value="upstream">
          <v-badge
            dot
            color="warning"
            :model-value="isLatestVersion === false"
          >
            {{ $formatMessage(tabUpstreamLabel) }}
          </v-badge>
        </v-tab>
        <v-tab value="fork">
          <v-badge
            dot
            color="warning"
            :model-value="isForkLatestVersion === false"
          >
            {{ $formatMessage(tabForkLabel) }}
          </v-badge>
        </v-tab>
        <v-tab value="gallery-dl">
          <v-badge
            dot
            color="warning"
            :model-value="isGalleryDlUpToDate === false"
          >
            {{ $formatMessage(tabGalleryDlLabel) }}
          </v-badge>
        </v-tab>
      </v-tabs>

      <v-tabs-window v-model="tab">
        <!-- Upstream -->
        <v-tabs-window-item value="upstream">
          <v-alert
            :type="isLatestVersion === false ? 'warning' : 'success'"
            variant="tonal"
            class="mb-4"
          >
            {{
              isLatestVersion === false
                ? $formatMessage(upstreamAvailableLabel)
                : $formatMessage(upstreamLatestLabel)
            }}
          </v-alert>
          <ReleaseCard
            v-for="(release, index) in releases ?? []"
            :key="`up-${index}`"
            :release="release"
            :current="release.version.replace(/^v/, '') === baseVersion"
            :latest="release.version == latestRelease?.version"
            class="my-4"
          />
        </v-tabs-window-item>

        <!-- Fork -->
        <v-tabs-window-item value="fork">
          <v-alert
            v-if="(forkReleases?.length ?? 0) === 0"
            type="info"
            variant="tonal"
            class="mb-4"
          >
            {{ $formatMessage(forkNoneLabel) }}
          </v-alert>
          <v-alert
            v-else
            :type="isForkLatestVersion === false ? 'warning' : 'success'"
            variant="tonal"
            class="mb-4"
          >
            {{
              isForkLatestVersion === false
                ? $formatMessage(forkAvailableLabel)
                : $formatMessage(forkLatestLabel)
            }}
          </v-alert>
          <ReleaseCard
            v-for="(release, index) in forkReleases ?? []"
            :key="`fork-${index}`"
            :release="release"
            :current="release.version == buildVersion"
            :latest="release.version == latestForkRelease?.version"
            class="my-4"
          />
        </v-tabs-window-item>

        <!-- gallery-dl fork -->
        <v-tabs-window-item value="gallery-dl">
          <v-alert
            v-if="!galleryDlUpdates"
            type="info"
            variant="tonal"
          >
            {{ $formatMessage(galleryDlLoadingLabel) }}
          </v-alert>
          <template v-else>
            <div class="mb-4 d-flex flex-wrap ga-2">
              <v-chip label>
                {{
                  $formatMessage(installedShaLabel, {
                    sha: galleryDlUpdates.installedSha
                      ? galleryDlUpdates.installedSha.substring(0, 7)
                      : 'unknown',
                  })
                }}
              </v-chip>
              <v-chip
                v-if="galleryDlBehindCount === 0"
                label
                color="success"
              >
                {{ $formatMessage(galleryDlLatestLabel) }}
              </v-chip>
              <v-chip
                v-else-if="galleryDlBehindCount > 0"
                label
                color="warning"
              >
                {{ $formatMessage(galleryDlBehindLabel, { count: galleryDlBehindCount }) }}
              </v-chip>
              <v-chip
                v-else
                label
                color="grey"
              >
                {{ $formatMessage(galleryDlUnknownLabel) }}
              </v-chip>
            </div>

            <v-alert
              v-if="galleryDlBehindCount > 0"
              type="warning"
              variant="tonal"
              class="mb-4"
            >
              {{ $formatMessage(galleryDlUpdateHintLabel) }}
            </v-alert>

            <!-- In-app update from URL (installs into config dir + re-overlays fork integration) -->
            <v-card
              variant="tonal"
              class="mb-4"
            >
              <v-card-text>
                <div class="text-body-2 mb-2">
                  {{ $formatMessage(updateFromUrlHint) }}
                </div>
                <div class="d-flex align-center ga-2 flex-wrap">
                  <v-text-field
                    v-model="updateUrl"
                    :label="$formatMessage(updateUrlLabel)"
                    variant="outlined"
                    density="compact"
                    hide-details
                    class="flex-grow-1"
                    style="min-width: 260px"
                  />
                  <v-btn
                    color="primary"
                    prepend-icon="i-mdi:download"
                    :loading="gdlUpdating"
                    :disabled="!updateUrl || gdlUpdating"
                    @click="runGalleryDlUpdate()"
                  >
                    {{ $formatMessage(updateBtnLabel) }}
                  </v-btn>
                </div>
                <div class="d-flex ga-1 mt-1">
                  <v-btn
                    size="x-small"
                    variant="text"
                    @click="updateUrl = OFFICIAL_URL"
                  >
                    {{ $formatMessage(presetOfficialLabel) }}
                  </v-btn>
                  <v-btn
                    size="x-small"
                    variant="text"
                    @click="updateUrl = FORK_URL"
                  >
                    {{ $formatMessage(presetForkLabel) }}
                  </v-btn>
                </div>
                <v-alert
                  v-if="gdlUpdating"
                  type="info"
                  variant="tonal"
                  density="compact"
                  class="mt-3"
                  :icon="false"
                >
                  <div class="d-flex align-center ga-2">
                    <v-progress-circular
                      indeterminate
                      size="16"
                      width="2"
                    />
                    {{ $formatMessage(updateRunningLabel) }}
                  </div>
                </v-alert>
                <v-alert
                  v-else-if="gdlResult"
                  :type="gdlResult.success ? 'success' : 'error'"
                  variant="tonal"
                  density="compact"
                  class="mt-3"
                >
                  {{ gdlResult.message }}
                </v-alert>
              </v-card-text>
            </v-card>

            <v-list
              v-if="galleryDlUpdates.commits.length > 0"
              lines="two"
            >
              <v-list-item
                v-for="commit in galleryDlUpdates.commits"
                :key="commit.sha"
                :href="safeExternalHref(commit.url)"
                target="_blank"
              >
                <template #prepend>
                  <v-icon
                    :icon="commit.installed ? 'i-mdi:check-circle' : 'i-mdi:circle-small'"
                    :color="commit.installed ? 'success' : undefined"
                  />
                </template>
                <v-list-item-title>
                  <code>{{ commit.shortSha }}</code> — {{ commit.message }}
                </v-list-item-title>
                <v-list-item-subtitle>
                  {{ commit.author || 'unknown' }}
                  <template v-if="commit.date"> · {{ formatDate(commit.date) }}</template>
                </v-list-item-subtitle>
              </v-list-item>
            </v-list>
          </template>
        </v-tabs-window-item>
      </v-tabs-window>
    </template>
  </v-container>
</template>

<script lang="ts" setup>
import { useQueryCache } from '@pinia/colada'
import { useIntl, defineMessage } from 'vue-intl'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'
import { useAppReleasesEnriched } from '@/composables/app-releases'
import {
  useGalleryDlUpdate,
  useGalleryDlUpdateStatus,
  type GalleryDlUpdateResult,
} from '@/colada/gallery-dl'
import { useMessagesStore } from '@/stores/messages'
import { safeExternalHref } from '@/utils/sanitize'

const intl = useIntl()
const messagesStore = useMessagesStore()
const queryCache = useQueryCache()

const {
  releases,
  error,
  buildVersion,
  isLatestVersion,
  latestRelease,
  forkReleases,
  latestForkRelease,
  isForkLatestVersion,
  galleryDlUpdates,
  galleryDlBehindCount,
  isGalleryDlUpToDate,
  isLoading,
} = useAppReleasesEnriched()

const tab = ref('upstream')

const baseVersion = computed(() => (buildVersion.value ?? '').split('-fork')[0] || '—')
const forkVersion = computed(() => {
  const parts = (buildVersion.value ?? '').split('-fork-')
  return parts.length > 1 ? parts[1] : ''
})

function formatDate(date: Date): string {
  return intl.formatDate(date, { dateStyle: 'medium', timeStyle: 'short' })
}

// gallery-dl update from URL (installs upstream + re-overlays fork integration, runs in background)
const OFFICIAL_URL = 'https://codeberg.org/mikf/gallery-dl/archive/master.tar.gz'
const FORK_URL = 'https://github.com/08shiro80/gallery-dl-komga/archive/refs/heads/master.tar.gz'
const updateUrl = ref('')
const gdlResult = ref<GalleryDlUpdateResult | null>(null)

const { mutateAsync: startGalleryDlUpdate } = useGalleryDlUpdate()
const { data: gdlStatus, refetch: refetchGdlStatus } = useGalleryDlUpdateStatus()
const gdlUpdating = computed(() => gdlStatus.value?.running ?? false)

let gdlPoll: ReturnType<typeof setInterval> | null = null

function startGdlPolling() {
  if (gdlPoll) return
  gdlPoll = setInterval(() => void refetchGdlStatus(), 3000)
}

// Apply the finished update's result and, on success, refresh the fork-update status
// (SHA/behind-count/commit list + tab badge) which lives in a separate query.
function applyGdlResult() {
  const result = gdlStatus.value?.result
  if (!result) return
  gdlResult.value = result
  if (result.success) void queryCache.invalidateQueries({ key: ['gallery-dl-fork-updates'] })
}

watch(
  () => gdlStatus.value?.running,
  (running) => {
    if (running) {
      startGdlPolling()
    } else {
      if (gdlPoll) {
        clearInterval(gdlPoll)
        gdlPoll = null
      }
      applyGdlResult()
    }
  },
)

onMounted(async () => {
  await refetchGdlStatus()
  // show the last result if an update finished while this page was not open
  if (!gdlStatus.value?.running && gdlStatus.value?.result) {
    gdlResult.value = gdlStatus.value.result
  }
})
onBeforeUnmount(() => {
  if (gdlPoll) clearInterval(gdlPoll)
})

async function runGalleryDlUpdate() {
  if (!updateUrl.value) return
  gdlResult.value = null
  try {
    await startGalleryDlUpdate(updateUrl.value)
    await refetchGdlStatus()
    // only poll if it is actually running; a very fast update may already be done
    if (gdlStatus.value?.running) startGdlPolling()
    else applyGdlResult()
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const baseVersionLabel = defineMessage({ description: 'Updates: base version chip', defaultMessage: 'Base: {version}', id: 'fork/updates/baseVersion' })
const forkVersionLabel = defineMessage({ description: 'Updates: fork version chip', defaultMessage: 'Fork: {version}', id: 'fork/updates/forkVersion' })
const tabUpstreamLabel = defineMessage({ description: 'Updates: upstream tab', defaultMessage: 'Upstream (Komga)', id: 'fork/updates/tab/upstream' })
const tabForkLabel = defineMessage({ description: 'Updates: fork tab', defaultMessage: 'Fork', id: 'fork/updates/tab/fork' })
const tabGalleryDlLabel = defineMessage({ description: 'Updates: gallery-dl tab', defaultMessage: 'gallery-dl-fork', id: 'fork/updates/tab/galleryDl' })
const upstreamAvailableLabel = defineMessage({ description: 'Updates: upstream available', defaultMessage: 'Updates are available', id: 'fork/updates/upstream/available' })
const upstreamLatestLabel = defineMessage({ description: 'Updates: upstream latest', defaultMessage: 'The latest version of Komga is already installed', id: 'fork/updates/upstream/latest' })
const forkNoneLabel = defineMessage({ description: 'Updates: no fork releases', defaultMessage: 'No fork releases found', id: 'fork/updates/fork/none' })
const forkAvailableLabel = defineMessage({ description: 'Updates: fork available', defaultMessage: 'A fork update is available', id: 'fork/updates/fork/available' })
const forkLatestLabel = defineMessage({ description: 'Updates: fork latest', defaultMessage: 'The latest fork version is installed', id: 'fork/updates/fork/latest' })
const galleryDlLoadingLabel = defineMessage({ description: 'Updates: gallery-dl loading', defaultMessage: 'Loading gallery-dl-fork update info…', id: 'fork/updates/galleryDl/loading' })
const galleryDlLatestLabel = defineMessage({ description: 'Updates: gallery-dl up to date', defaultMessage: 'Up to date', id: 'fork/updates/galleryDl/latest' })
const galleryDlBehindLabel = defineMessage({ description: 'Updates: gallery-dl behind', defaultMessage: '{count, plural, one {# commit behind} other {# commits behind}}', id: 'fork/updates/galleryDl/behind' })
const galleryDlUnknownLabel = defineMessage({ description: 'Updates: gallery-dl unknown', defaultMessage: 'Installed SHA unknown', id: 'fork/updates/galleryDl/unknown' })
const installedShaLabel = defineMessage({ description: 'Updates: installed sha', defaultMessage: 'Installed SHA: {sha}', id: 'fork/updates/galleryDl/sha' })
const galleryDlUpdateHintLabel = defineMessage({ description: 'Updates: gallery-dl update hint', defaultMessage: 'New commits are available on the fork.', id: 'fork/updates/galleryDl/hint' })
const updateFromUrlHint = defineMessage({ description: 'Updates: gallery-dl update from url hint', defaultMessage: 'Install a gallery-dl distribution from a URL — the bundled Komga integration (komga/komgazip postprocessors, custom extractors, core patches) is automatically re-applied on top. Installs into the config dir; new downloads use it immediately, no restart needed.', id: 'fork/updates/galleryDl/urlHint' })
const updateUrlLabel = defineMessage({ description: 'Updates: gallery-dl update url field', defaultMessage: 'gallery-dl URL (pip target)', id: 'fork/updates/galleryDl/url' })
const updateBtnLabel = defineMessage({ description: 'Updates: gallery-dl update button', defaultMessage: 'Update', id: 'fork/updates/galleryDl/updateBtn' })
const presetOfficialLabel = defineMessage({ description: 'Updates: gallery-dl preset official', defaultMessage: 'Official (codeberg)', id: 'fork/updates/galleryDl/presetOfficial' })
const presetForkLabel = defineMessage({ description: 'Updates: gallery-dl preset fork', defaultMessage: 'Fork (github)', id: 'fork/updates/galleryDl/presetFork' })
const updateRunningLabel = defineMessage({ description: 'Updates: gallery-dl update running', defaultMessage: 'Installing and re-applying fork integration…', id: 'fork/updates/galleryDl/running' })
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
