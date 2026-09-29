<template>
  <v-container fluid>
    <div class="d-flex align-center mb-3">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Media analysis view: title',
            defaultMessage: 'Media integrity',
            id: 'fork/media/title',
          })
        }}
      </span>
    </div>

    <!-- Corrupt / unsupported books -->
    <v-card
      variant="outlined"
      class="mb-4"
    >
      <v-card-title class="text-subtitle-1 d-flex align-center">
        {{ $formatMessage(corruptTitle) }} ({{ corruptData?.totalElements ?? 0 }})
        <v-spacer />
        <v-btn
          icon="i-mdi:refresh"
          variant="text"
          size="small"
          :loading="corruptPending"
          :aria-label="$formatMessage(refreshLabel)"
          @click="refetchCorrupt()"
        />
      </v-card-title>
      <v-card-text>
        <div class="d-flex flex-wrap align-center ga-4 mb-3">
          <v-chip-group
            v-model="filterStatuses"
            multiple
            mandatory
            color="primary"
          >
            <v-chip
              filter
              value="ERROR"
              size="small"
            >
              {{ $formatMessage(statusErrorLabel) }}
            </v-chip>
            <v-chip
              filter
              value="UNSUPPORTED"
              size="small"
            >
              {{ $formatMessage(statusUnsupportedLabel) }}
            </v-chip>
          </v-chip-group>
          <v-select
            v-model="filterLibraries"
            :items="libraryItems"
            :label="$formatMessage(librariesLabel)"
            variant="outlined"
            density="compact"
            hide-details
            multiple
            chips
            closable-chips
            clearable
            style="min-width: 260px; max-width: 420px"
          />
        </div>

        <EmptyStateNetworkError v-if="corruptError" />
        <v-alert
          v-else-if="(corruptData?.content?.length ?? 0) === 0 && !corruptPending"
          type="success"
          variant="tonal"
          density="compact"
        >
          {{ $formatMessage(noCorruptLabel) }}
        </v-alert>
        <template v-else>
          <v-table density="compact">
            <thead>
              <tr>
                <th>{{ $formatMessage(colLibrary) }}</th>
                <th>{{ $formatMessage(colBook) }}</th>
                <th>{{ $formatMessage(colStatus) }}</th>
                <th>{{ $formatMessage(colComment) }}</th>
                <th>{{ $formatMessage(colType) }}</th>
                <th class="text-end">
                  {{ $formatMessage(colSize) }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="b in corruptData?.content ?? []"
                :key="b.id"
              >
                <td>{{ libraryName(b.libraryId) }}</td>
                <td>
                  <RouterLink
                    :to="{ name: '/book/[id]', params: { id: b.id } }"
                    class="text-decoration-none"
                  >
                    {{ b.name }}
                  </RouterLink>
                  <v-chip
                    v-if="b.deleted"
                    color="error"
                    size="x-small"
                    label
                    class="ms-2"
                  >
                    {{ $formatMessage(unavailableLabel) }}
                  </v-chip>
                </td>
                <td>
                  <v-chip
                    :color="b.media.status === 'ERROR' ? 'error' : 'warning'"
                    size="x-small"
                    label
                  >
                    {{ b.media.status }}
                  </v-chip>
                </td>
                <td class="text-caption">
                  {{ convertErrorCodes(b.media.comment) }}
                </td>
                <td class="text-caption">
                  {{ b.media.mediaType }}
                </td>
                <td class="text-end text-no-wrap">
                  {{ b.size }}
                </td>
              </tr>
            </tbody>
          </v-table>
          <v-pagination
            v-if="(corruptData?.totalPages ?? 1) > 1"
            v-model="corruptPage"
            :length="corruptData?.totalPages ?? 1"
            :total-visible="7"
            class="mt-4"
          />
        </template>
      </v-card-text>
    </v-card>

    <!-- ZIP integrity -->
    <v-card
      variant="outlined"
      class="mb-4"
    >
      <v-card-title class="text-subtitle-1">
        {{
          $formatMessage({
            description: 'Media analysis: integrity section',
            defaultMessage: 'ZIP integrity',
            id: 'fork/media/integrity',
          })
        }}
      </v-card-title>
      <v-card-text>
        <div
          v-if="status?.inProgress"
          class="mb-3"
        >
          <div class="text-caption mb-1">
            {{ $formatMessage(verifyingLabel) }} {{ status.processed }}/{{ status.total }}
            <span v-if="status.flagged"> · {{ status.flagged }} {{ $formatMessage(flaggedLabel) }}</span>
          </div>
          <v-progress-linear
            :model-value="pct(status.processed, status.total)"
            color="primary"
            height="8"
            rounded
          />
        </div>
        <div
          v-if="status?.repairInProgress"
          class="mb-3"
        >
          <div class="text-caption mb-1">
            {{ $formatMessage(repairingLabel) }} {{ status.repairProcessed }}/{{ status.repairTotal }}
            <span v-if="status.repairFixed"> · {{ status.repairFixed }} {{ $formatMessage(fixedLabel) }}</span>
          </div>
          <v-progress-linear
            :model-value="pct(status.repairProcessed, status.repairTotal)"
            color="success"
            height="8"
            rounded
          />
        </div>
        <div
          v-if="!status?.inProgress && !status?.repairInProgress && (status?.flagged ?? 0) > 0"
          class="text-body-2 mb-3"
        >
          {{ status?.flagged }} {{ $formatMessage(flaggedFilesLabel) }}
        </div>

        <div class="d-flex flex-wrap ga-2">
          <v-btn
            color="primary"
            prepend-icon="i-mdi:shield-check"
            :loading="verifying"
            :disabled="status?.inProgress || status?.repairInProgress"
            @click="verify()"
          >
            {{ $formatMessage(verifyLabel) }}
          </v-btn>
          <v-btn
            color="warning"
            variant="tonal"
            prepend-icon="i-mdi:wrench"
            :loading="repairing"
            :disabled="status?.inProgress || status?.repairInProgress || (status?.flagged ?? 0) === 0"
            @click="repair()"
          >
            {{ $formatMessage(repairLabel) }}
          </v-btn>
          <v-btn
            variant="text"
            prepend-icon="i-mdi:refresh"
            :loading="rescanning"
            @click="rescan()"
          >
            {{ $formatMessage(rescanLabel) }}
          </v-btn>
        </div>
      </v-card-text>
    </v-card>

    <!-- Single-page chapters -->
    <v-card variant="outlined">
      <v-card-title class="d-flex align-center flex-wrap ga-2 text-subtitle-1">
        {{ $formatMessage(singlePageLabel) }} ({{ singlePageBooks?.length ?? 0 }})
        <v-spacer />
        <template v-if="selectedCount > 0">
          <v-btn
            size="small"
            color="primary"
            prepend-icon="i-mdi:autorenew"
            :loading="batchBusy"
            :disabled="repairableSelectedCount === 0"
            @click="repairSelected()"
          >
            {{ $formatMessage(repairSelectedLabel) }} ({{ repairableSelectedCount }})
          </v-btn>
          <v-btn
            size="small"
            color="error"
            variant="tonal"
            prepend-icon="i-mdi:delete-outline"
            :loading="batchBusy"
            @click="showDeleteConfirm = true"
          >
            {{ $formatMessage(deleteSelectedLabel) }} ({{ selectedCount }})
          </v-btn>
          <v-btn
            size="small"
            variant="text"
            prepend-icon="i-mdi:eye-off-outline"
            :loading="batchBusy"
            @click="ignoreSelected()"
          >
            {{ $formatMessage(ignoreSelectedLabel) }} ({{ selectedCount }})
          </v-btn>
        </template>
        <v-switch
          v-model="includeIgnored"
          :label="$formatMessage(showIgnoredLabel)"
          density="compact"
          hide-details
          color="primary"
          class="ms-2"
        />
        <v-btn
          icon="i-mdi:refresh"
          variant="text"
          size="small"
          :loading="singlePagePending"
          :aria-label="$formatMessage(refreshLabel)"
          @click="refetchSinglePage()"
        />
      </v-card-title>
      <v-card-text>
        <p class="text-caption text-medium-emphasis mb-2">
          {{ $formatMessage(singlePageHint) }}
        </p>
        <EmptyStateNetworkError v-if="singlePageError" />
        <v-alert
          v-else-if="(singlePageBooks?.length ?? 0) === 0"
          type="success"
          variant="tonal"
          density="compact"
        >
          {{ $formatMessage(noSinglePageLabel) }}
        </v-alert>
        <v-table
          v-else
          density="compact"
        >
          <thead>
            <tr>
              <th style="width: 40px">
                <v-checkbox
                  :model-value="allSelected"
                  :indeterminate="selectedCount > 0 && !allSelected"
                  hide-details
                  density="compact"
                  @update:model-value="toggleSelectAll()"
                />
              </th>
              <th>{{ $formatMessage(colBook) }}</th>
              <th>{{ $formatMessage(colType) }}</th>
              <th class="text-end">
                {{ $formatMessage(colSize) }}
              </th>
              <th class="text-center">
                {{ $formatMessage(colSource) }}
              </th>
              <th class="text-center">
                {{ $formatMessage(colIgnored) }}
              </th>
              <th class="text-end">
                {{ $formatMessage(colActions) }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="b in singlePageBooks ?? []"
              :key="b.bookId"
            >
              <td>
                <v-checkbox
                  :model-value="selected.has(b.bookId)"
                  hide-details
                  density="compact"
                  @update:model-value="toggleSelect(b.bookId)"
                />
              </td>
              <td>
                <RouterLink
                  :to="{ name: '/book/[id]', params: { id: b.bookId } }"
                  class="text-decoration-none"
                >
                  {{ b.bookName }}
                </RouterLink>
                <div class="text-caption text-medium-emphasis text-truncate" style="max-width: 320px">
                  {{ b.seriesTitle }}
                </div>
              </td>
              <td class="text-caption">
                {{ b.mediaType }}
              </td>
              <td class="text-end">
                {{ formatBytes(b.fileSize) }}
              </td>
              <td class="text-center">
                <v-icon
                  v-if="b.sourceUrl"
                  icon="i-mdi:link-variant"
                  color="success"
                  size="small"
                  :title="b.sourceUrl"
                />
                <v-icon
                  v-else
                  icon="i-mdi:link-variant-off"
                  color="grey"
                  size="small"
                  :title="$formatMessage(noSourceLabel)"
                />
              </td>
              <td class="text-center">
                <v-switch
                  :model-value="b.ignored"
                  density="compact"
                  hide-details
                  color="primary"
                  class="d-inline-flex"
                  @update:model-value="toggleIgnore(b.bookId, b.ignored)"
                />
              </td>
              <td class="text-end">
                <v-btn
                  icon="i-mdi:download"
                  variant="text"
                  size="small"
                  color="primary"
                  :disabled="!b.sourceUrl"
                  :aria-label="$formatMessage(repairSinglePageLabel)"
                  @click="repairSingle(b.bookId)"
                />
              </td>
            </tr>
          </tbody>
        </v-table>
      </v-card-text>
    </v-card>

    <v-dialog
      v-model="showDeleteConfirm"
      max-width="480"
    >
      <v-card>
        <v-card-title class="text-error">
          {{ $formatMessage(deleteConfirmTitleLabel, { count: selectedCount }) }}
        </v-card-title>
        <v-card-text>
          {{
            $formatMessage({
              description: 'Media analysis: delete confirm body',
              defaultMessage: 'This removes the book files from disk and cannot be undone.',
              id: 'fork/media/delete/body',
            })
          }}
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="showDeleteConfirm = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="error"
            :loading="batchBusy"
            @click="deleteSelected()"
          >
            {{ $formatMessage(deleteLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>

<script lang="ts" setup>
import { useIntl, defineMessage } from 'vue-intl'
import {
  useCorruptBooks,
  useDeleteSinglePageBook,
  useIntegrityStatus,
  useRepairIntegrity,
  useRepairSinglePage,
  useRescanIntegrity,
  useSinglePageBooks,
  useToggleSinglePageIgnore,
  useVerifyIntegrity,
  type CorruptBooksQuery,
} from '@/colada/media-analysis'
import { useLibraries } from '@/colada/libraries'
import { useMessagesStore } from '@/stores/messages'
import { useErrorCodeFormatter } from '@/composables/errorCodeFormatter'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'

const intl = useIntl()
const messagesStore = useMessagesStore()
const { convertErrorCodes } = useErrorCodeFormatter()

const { data: libraries } = useLibraries()
const libraryItems = computed(() =>
  (libraries.value ?? []).map((l) => ({ title: l.name, value: l.id })),
)
function libraryName(id: string): string {
  return libraries.value?.find((l) => l.id === id)?.name ?? id
}

// --- Corrupt / unsupported books ---
const filterStatuses = ref<Array<string>>(['ERROR', 'UNSUPPORTED'])
const filterLibraries = ref<Array<string>>([])
const corruptPage = ref(1)
const corruptSize = ref(20)

function buildCorruptParams(): CorruptBooksQuery {
  return {
    statuses: filterStatuses.value,
    libraryIds: filterLibraries.value,
    page: corruptPage.value - 1,
    size: corruptSize.value,
  }
}

const corruptParams = ref<CorruptBooksQuery>(buildCorruptParams())

watch([filterStatuses, filterLibraries], () => {
  // Reset to page 1 on filter change; if already on page 1, rebuild directly
  // (the page watch won't fire without a page change).
  if (corruptPage.value !== 1) corruptPage.value = 1
  else corruptParams.value = buildCorruptParams()
})

watch(corruptPage, () => {
  corruptParams.value = buildCorruptParams()
})

const {
  data: corruptData,
  error: corruptError,
  isPending: corruptPending,
  refetch: refetchCorrupt,
} = useCorruptBooks(corruptParams)

// --- ZIP integrity ---
const { data: status, refetch: refetchStatus } = useIntegrityStatus()
const { mutateAsync: verifyAll, isLoading: verifying } = useVerifyIntegrity()
const { mutateAsync: repairAll, isLoading: repairing } = useRepairIntegrity()
const { mutateAsync: rescanAll, isLoading: rescanning } = useRescanIntegrity()

let pollHandle: ReturnType<typeof setInterval> | null = null

watch(
  () => status.value?.inProgress || status.value?.repairInProgress,
  (active) => {
    if (active && !pollHandle) {
      pollHandle = setInterval(() => void refetchStatus(), 3000)
    } else if (!active && pollHandle) {
      clearInterval(pollHandle)
      pollHandle = null
    }
  },
)

onBeforeUnmount(() => {
  if (pollHandle) clearInterval(pollHandle)
})

function pct(processed?: number, total?: number): number {
  if (!total) return 0
  return Math.round(((processed ?? 0) / total) * 100)
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toFixed(1)} ${units[unit]}`
}

async function verify() {
  try {
    await verifyAll()
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function repair() {
  try {
    await repairAll()
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function rescan() {
  try {
    await rescanAll()
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

// --- Single-page chapters ---
const includeIgnored = ref(false)
const {
  data: singlePageBooks,
  error: singlePageError,
  isPending: singlePagePending,
  refetch: refetchSinglePage,
} = useSinglePageBooks(includeIgnored)

const { mutateAsync: repairSinglePage } = useRepairSinglePage()
const { mutateAsync: toggleIgnoreMutation } = useToggleSinglePageIgnore()
const { mutateAsync: deleteSinglePageBook } = useDeleteSinglePageBook()

const selected = ref<Set<string>>(new Set())
const batchBusy = ref(false)
const showDeleteConfirm = ref(false)

const selectedCount = computed(() => selected.value.size)
const allSelected = computed(
  () =>
    (singlePageBooks.value?.length ?? 0) > 0 &&
    selected.value.size === (singlePageBooks.value?.length ?? 0),
)
const repairableSelectedCount = computed(
  () =>
    (singlePageBooks.value ?? []).filter((b) => selected.value.has(b.bookId) && b.sourceUrl).length,
)

watch(singlePageBooks, () => {
  selected.value = new Set()
})

function toggleSelect(bookId: string) {
  const next = new Set(selected.value)
  if (next.has(bookId)) next.delete(bookId)
  else next.add(bookId)
  selected.value = next
}

function toggleSelectAll() {
  if (allSelected.value) selected.value = new Set()
  else selected.value = new Set((singlePageBooks.value ?? []).map((b) => b.bookId))
}

async function repairSingle(bookId: string) {
  try {
    await repairSinglePage(bookId)
    messagesStore.messages.push({ message: repairQueuedMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function toggleIgnore(bookId: string, ignored: boolean) {
  try {
    await toggleIgnoreMutation({ bookId, ignored })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function repairSelected() {
  const ids = (singlePageBooks.value ?? [])
    .filter((b) => selected.value.has(b.bookId) && b.sourceUrl)
    .map((b) => b.bookId)
  if (ids.length === 0) return
  batchBusy.value = true
  const counts = { repaired: 0, stillSingle: 0, sourceGone: 0, failed: 0 }
  for (const id of ids) {
    try {
      const r = await repairSinglePage(id)
      switch (r.outcome) {
        case 'REPAIRED':
          counts.repaired++
          break
        case 'STILL_SINGLE_PAGE':
          counts.stillSingle++
          break
        case 'SOURCE_GONE':
        case 'NO_SOURCE_URL':
          counts.sourceGone++
          break
        default:
          counts.failed++
      }
    } catch {
      counts.failed++
    }
  }
  batchBusy.value = false
  selected.value = new Set()
  messagesStore.messages.push({
    message: intl.formatMessage(repairResultLabel, counts),
  })
}

async function deleteSelected() {
  const ids = [...selected.value]
  if (ids.length === 0) return
  batchBusy.value = true
  let failed = 0
  for (const id of ids) {
    try {
      await deleteSinglePageBook(id)
    } catch {
      failed++
    }
  }
  batchBusy.value = false
  showDeleteConfirm.value = false
  selected.value = new Set()
  messagesStore.messages.push({
    message: intl.formatMessage(deleteResultLabel, { count: ids.length - failed, failed }),
    color: failed ? 'warning' : undefined,
  })
}

async function ignoreSelected() {
  const ids = (singlePageBooks.value ?? [])
    .filter((b) => selected.value.has(b.bookId) && !b.ignored)
    .map((b) => b.bookId)
  if (ids.length === 0) return
  batchBusy.value = true
  for (const id of ids) {
    try {
      await toggleIgnoreMutation({ bookId: id, ignored: false })
    } catch {
      // skip individual failures
    }
  }
  batchBusy.value = false
  selected.value = new Set()
}

const refreshLabel = defineMessage({ description: 'Media analysis: refresh', defaultMessage: 'Refresh', id: 'fork/media/refresh' })
const corruptTitle = defineMessage({ description: 'Media analysis: corrupt section', defaultMessage: 'Books with errors', id: 'fork/media/corrupt' })
const statusErrorLabel = defineMessage({ description: 'Media analysis: status error', defaultMessage: 'Error', id: 'fork/media/status/error' })
const statusUnsupportedLabel = defineMessage({ description: 'Media analysis: status unsupported', defaultMessage: 'Unsupported', id: 'fork/media/status/unsupported' })
const librariesLabel = defineMessage({ description: 'Media analysis: libraries filter', defaultMessage: 'Libraries', id: 'fork/media/libraries' })
const noCorruptLabel = defineMessage({ description: 'Media analysis: no corrupt books', defaultMessage: 'No books with the selected status.', id: 'fork/media/noCorrupt' })
const colLibrary = defineMessage({ description: 'Media analysis: col library', defaultMessage: 'Library', id: 'fork/media/col/library' })
const colBook = defineMessage({ description: 'Media analysis: col book', defaultMessage: 'Book', id: 'fork/media/col/book' })
const colStatus = defineMessage({ description: 'Media analysis: col status', defaultMessage: 'Status', id: 'fork/media/col/status' })
const colComment = defineMessage({ description: 'Media analysis: col comment', defaultMessage: 'Comment', id: 'fork/media/col/comment' })
const colType = defineMessage({ description: 'Media analysis: col type', defaultMessage: 'Type', id: 'fork/media/col/type' })
const colSize = defineMessage({ description: 'Media analysis: col size', defaultMessage: 'Size', id: 'fork/media/col/size' })
const colSource = defineMessage({ description: 'Media analysis: col source', defaultMessage: 'Source', id: 'fork/media/col/source' })
const colIgnored = defineMessage({ description: 'Media analysis: col ignored', defaultMessage: 'Ignored', id: 'fork/media/col/ignored' })
const colActions = defineMessage({ description: 'Media analysis: col actions', defaultMessage: 'Actions', id: 'fork/media/col/actions' })
const unavailableLabel = defineMessage({ description: 'Media analysis: unavailable', defaultMessage: 'Unavailable', id: 'fork/media/unavailable' })
const verifyLabel = defineMessage({ description: 'Media analysis: verify button', defaultMessage: 'Verify integrity', id: 'fork/media/verify' })
const repairLabel = defineMessage({ description: 'Media analysis: repair button', defaultMessage: 'Repair flagged', id: 'fork/media/repair' })
const rescanLabel = defineMessage({ description: 'Media analysis: rescan button', defaultMessage: 'Rescan', id: 'fork/media/rescan' })
const verifyingLabel = defineMessage({ description: 'Media analysis: verifying progress', defaultMessage: 'Verifying', id: 'fork/media/verifying' })
const repairingLabel = defineMessage({ description: 'Media analysis: repairing progress', defaultMessage: 'Repairing', id: 'fork/media/repairing' })
const flaggedLabel = defineMessage({ description: 'Media analysis: flagged count suffix', defaultMessage: 'flagged', id: 'fork/media/flagged' })
const fixedLabel = defineMessage({ description: 'Media analysis: fixed count suffix', defaultMessage: 'fixed', id: 'fork/media/fixed' })
const flaggedFilesLabel = defineMessage({ description: 'Media analysis: flagged files idle', defaultMessage: 'files flagged with integrity issues', id: 'fork/media/flaggedFiles' })
const singlePageLabel = defineMessage({ description: 'Media analysis: single-page section', defaultMessage: 'Single-page chapters', id: 'fork/media/singlePage' })
const singlePageHint = defineMessage({ description: 'Media analysis: single-page hint', defaultMessage: 'Chapters whose archive contains only a single image. Repair re-downloads from the tracked chapter link and replaces the file only if the fresh download succeeds. Flip Ignore for the ones that are genuinely single-image.', id: 'fork/media/singlePage/hint' })
const noSinglePageLabel = defineMessage({ description: 'Media analysis: no single-page books', defaultMessage: 'No single-page chapters detected.', id: 'fork/media/noSinglePage' })
const showIgnoredLabel = defineMessage({ description: 'Media analysis: show ignored', defaultMessage: 'Show ignored', id: 'fork/media/showIgnored' })
const noSourceLabel = defineMessage({ description: 'Media analysis: no source url', defaultMessage: 'No tracked chapter URL — repair unavailable', id: 'fork/media/noSource' })
const repairSelectedLabel = defineMessage({ description: 'Media analysis: repair selected', defaultMessage: 'Repair', id: 'fork/media/repairSelected' })
const deleteSelectedLabel = defineMessage({ description: 'Media analysis: delete selected', defaultMessage: 'Delete', id: 'fork/media/deleteSelected' })
const ignoreSelectedLabel = defineMessage({ description: 'Media analysis: ignore selected', defaultMessage: 'Ignore', id: 'fork/media/ignoreSelected' })
const repairSinglePageLabel = defineMessage({ description: 'Media analysis: repair single-page (re-download)', defaultMessage: 'Re-download chapter', id: 'fork/media/repairSingle' })
const repairQueuedMessage = defineMessage({ description: 'Media analysis: repair queued', defaultMessage: 'Re-download queued', id: 'fork/media/repairQueued' })
const repairResultLabel = defineMessage({ description: 'Media analysis: repair batch result', defaultMessage: 'Repair: {repaired} fixed · {stillSingle} still single-page · {sourceGone} source gone · {failed} failed', id: 'fork/media/repairResult' })
const deleteResultLabel = defineMessage({ description: 'Media analysis: delete batch result', defaultMessage: 'Queued {count} book(s) for deletion{failed, plural, =0 {} other { ({failed} failed)}}', id: 'fork/media/deleteResult' })
const deleteConfirmTitleLabel = defineMessage({ description: 'Media analysis: delete confirm title', defaultMessage: 'Delete {count, plural, one {# file} other {# files}} from disk?', id: 'fork/media/delete/title' })
const cancelLabel = defineMessage({ description: 'Media analysis: cancel', defaultMessage: 'Cancel', id: 'fork/media/cancel' })
const deleteLabel = defineMessage({ description: 'Media analysis: delete', defaultMessage: 'Delete', id: 'fork/media/delete' })
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
