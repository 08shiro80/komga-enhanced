<template>
  <v-container fluid>
    <div class="d-flex align-center mb-4">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Downloads view: page title',
            defaultMessage: 'Downloads',
            id: 'fork/downloads/title',
          })
        }}
      </span>
      <v-spacer />

      <v-btn
        color="primary"
        prepend-icon="i-mdi:plus"
        class="me-2"
        @click="addDialog = true"
      >
        {{
          $formatMessage({
            description: 'Downloads view: add download button',
            defaultMessage: 'Add download',
            id: 'fork/downloads/add',
          })
        }}
      </v-btn>

      <v-menu>
        <template #activator="{ props }">
          <v-btn
            v-bind="props"
            variant="text"
            prepend-icon="i-mdi:broom"
            append-icon="i-mdi:menu-down"
            class="me-2"
          >
            {{
              $formatMessage({
                description: 'Downloads view: clear menu button',
                defaultMessage: 'Clear',
                id: 'fork/downloads/clear',
              })
            }}
          </v-btn>
        </template>
        <v-list density="compact">
          <v-list-item
            v-for="s in clearableStatuses"
            :key="s.value"
            :disabled="countByStatus(s.status) === 0 || clearing"
            :title="`${$formatMessage(s.label)} (${countByStatus(s.status)})`"
            @click="clear(s.value)"
          />
        </v-list>
      </v-menu>

      <v-btn
        icon="i-mdi:refresh"
        variant="text"
        :loading="isPending"
        @click="refetch()"
      />
    </div>

    <v-tabs
      v-model="activeStatus"
      density="compact"
      show-arrows
      class="mb-2"
    >
      <v-tab
        v-for="t in statusTabs"
        :key="t.value"
        :value="t.value"
      >
        {{ $formatMessage(t.label) }}
        <v-chip
          size="x-small"
          class="ms-2"
        >
          {{ t.value === 'ALL' ? merged.length : countByStatus(t.value) }}
        </v-chip>
      </v-tab>
    </v-tabs>

    <v-text-field
      v-model="searchText"
      :label="$formatMessage(filterLabel)"
      variant="outlined"
      density="compact"
      hide-details
      clearable
      prepend-inner-icon="i-mdi:magnify"
      class="mb-3"
    />

    <v-skeleton-loader
      v-if="isPending && !downloads"
      type="table"
    />

    <EmptyStateNetworkError v-else-if="error" />

    <v-alert
      v-else-if="merged.length === 0"
      type="info"
      variant="tonal"
    >
      {{
        $formatMessage({
          description: 'Downloads view: empty state',
          defaultMessage: 'No downloads in the queue.',
          id: 'fork/downloads/empty',
        })
      }}
    </v-alert>

    <template v-else>
      <v-alert
        v-if="filtered.length === 0"
        type="info"
        variant="tonal"
      >
        {{
          $formatMessage({
            description: 'Downloads view: no filter match',
            defaultMessage: 'No downloads match the current filter.',
            id: 'fork/downloads/noMatch',
          })
        }}
      </v-alert>
      <v-card
        v-for="item in filtered"
        :key="item.id"
        variant="outlined"
        class="mb-2"
      >
        <v-card-text class="py-3">
          <div class="d-flex align-center">
            <div
              class="flex-grow-1"
              style="min-width: 0"
            >
              <div class="font-weight-medium text-truncate">
                {{ item.title || item.sourceUrl }}
              </div>
              <div class="text-caption text-medium-emphasis text-truncate">
                {{ item.sourceUrl }}
              </div>
            </div>
            <v-chip
              size="small"
              :color="statusColor(item.status)"
              class="ms-2"
            >
              {{ item.status }}
            </v-chip>
          </div>

          <v-progress-linear
            v-if="item.status === 'DOWNLOADING'"
            :model-value="item.progressPercent"
            height="18"
            rounded
            :color="statusColor(item.status)"
            class="mt-2"
          >
            <span class="text-caption">
              {{ item.progressPercent }}%
              <template v-if="item.totalChapters">
                ({{ item.currentChapter }}/{{ item.totalChapters }})
              </template>
            </span>
          </v-progress-linear>

          <div
            v-if="item.status === 'FAILED' && item.errorMessage"
            class="text-caption text-error mt-1"
          >
            {{ item.errorMessage }}
          </div>

          <div class="d-flex align-center text-caption text-medium-emphasis mt-2">
            <v-icon
              icon="i-mdi:folder"
              size="x-small"
              class="me-1"
            />
            <span>{{ item.libraryId ? libraryName(item.libraryId) : downloadsFolderLabel }}</span>
            <v-spacer />
            <span>{{ formatDate(item.createdDate) }}</span>
          </div>
        </v-card-text>

        <v-card-actions class="pt-0">
          <v-spacer />
          <v-btn
            v-if="item.status === 'DOWNLOADING'"
            icon="i-mdi:pause"
            size="small"
            :aria-label="$formatMessage(pauseLabel)"
            @click="action(item.id, 'pause')"
          />
          <v-btn
            v-if="item.status === 'PAUSED'"
            icon="i-mdi:play"
            size="small"
            color="primary"
            :aria-label="$formatMessage(resumeLabel)"
            @click="action(item.id, 'resume')"
          />
          <v-btn
            v-if="item.status === 'DOWNLOADING' || item.status === 'PENDING' || item.status === 'PAUSED'"
            icon="i-mdi:stop"
            size="small"
            color="warning"
            :aria-label="$formatMessage(cancelLabel)"
            @click="action(item.id, 'cancel')"
          />
          <v-btn
            v-if="item.status === 'FAILED'"
            icon="i-mdi:refresh"
            size="small"
            color="primary"
            :aria-label="$formatMessage(retryLabel)"
            @click="action(item.id, 'retry')"
          />
          <v-btn
            icon="i-mdi:delete"
            size="small"
            :aria-label="$formatMessage(deleteLabel)"
            @click="remove(item.id)"
          />
        </v-card-actions>
      </v-card>
    </template>

    <v-dialog
      v-model="addDialog"
      max-width="560"
    >
      <v-card>
        <v-card-title>
          {{
            $formatMessage({
              description: 'Downloads view: add dialog title',
              defaultMessage: 'Add download',
              id: 'fork/downloads/dialog/title',
            })
          }}
        </v-card-title>
        <v-card-text>
          <v-text-field
            v-model="newDownload.sourceUrl"
            :label="$formatMessage(urlLabel)"
            variant="outlined"
            density="comfortable"
            autofocus
          />
          <v-select
            v-model="newDownload.libraryId"
            :items="libraryItems"
            :label="$formatMessage(libraryLabel)"
            variant="outlined"
            density="comfortable"
            clearable
          />
          <v-text-field
            v-model.number="newDownload.priority"
            type="number"
            :label="$formatMessage(priorityLabel)"
            variant="outlined"
            density="comfortable"
          />
          <v-checkbox
            v-model="newDownload.skipIfChapterExists"
            :label="$formatMessage(skipLabel)"
            density="comfortable"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="addDialog = false">
            {{
              $formatMessage({
                description: 'Downloads view: dialog cancel',
                defaultMessage: 'Cancel',
                id: 'fork/downloads/dialog/cancel',
              })
            }}
          </v-btn>
          <v-btn
            color="primary"
            :loading="creating"
            :disabled="!newDownload.sourceUrl"
            @click="add()"
          >
            {{
              $formatMessage({
                description: 'Downloads view: dialog confirm',
                defaultMessage: 'Add',
                id: 'fork/downloads/dialog/confirm',
              })
            }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>

<script lang="ts" setup>
import { useIntl, defineMessage, type MessageDescriptor } from 'vue-intl'
import {
  useClearDownloads,
  useCreateDownload,
  useDeleteDownload,
  useDownloadAction,
  useDownloads,
  type ClearableStatus,
} from '@/colada/downloads'
import { useDownloadsStore } from '@/stores/downloads'
import { useLibraries } from '@/colada/libraries'
import { useMessagesStore } from '@/stores/messages'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'
import type { DownloadCreateDto } from '@/generated/openapi'

const intl = useIntl()
const messagesStore = useMessagesStore()

const { data: downloads, error, isPending, refetch } = useDownloads()
const downloadsStore = useDownloadsStore()
const { data: libraries } = useLibraries()

const { mutateAsync: createDownload, isLoading: creating } = useCreateDownload()
const { mutateAsync: performAction } = useDownloadAction()
const { mutateAsync: deleteDownload } = useDeleteDownload()
const { mutateAsync: clearDownloads, isLoading: clearing } = useClearDownloads()

const merged = computed(() =>
  (downloads.value ?? []).map((d) => {
    const live = downloadsStore.progress[d.id]
    if (live && d.status === 'DOWNLOADING') {
      return {
        ...d,
        status: live.status,
        progressPercent: live.progressPercent,
        currentChapter: live.currentChapter ?? d.currentChapter,
        totalChapters: live.totalChapters ?? d.totalChapters,
      }
    }
    return d
  }),
)

const route = useRoute()
const activeStatus = ref<string>((route.query.status as string) || 'ALL')
const searchText = ref('')

const filtered = computed(() => {
  let list = merged.value
  if (activeStatus.value !== 'ALL') list = list.filter((d) => d.status === activeStatus.value)
  const q = searchText.value?.trim().toLowerCase()
  if (q)
    list = list.filter(
      (d) =>
        (d.title || '').toLowerCase().includes(q) ||
        (d.sourceUrl || '').toLowerCase().includes(q),
    )
  return list
})

const statusTabs: { value: string; label: MessageDescriptor }[] = [
  { value: 'ALL', label: defineMessage({ description: 'Downloads tab: all', defaultMessage: 'All', id: 'fork/downloads/tab/all' }) },
  { value: 'DOWNLOADING', label: defineMessage({ description: 'Downloads tab: downloading', defaultMessage: 'Downloading', id: 'fork/downloads/tab/downloading' }) },
  { value: 'PENDING', label: defineMessage({ description: 'Downloads tab: pending', defaultMessage: 'Pending', id: 'fork/downloads/tab/pending' }) },
  { value: 'PAUSED', label: defineMessage({ description: 'Downloads tab: paused', defaultMessage: 'Paused', id: 'fork/downloads/tab/paused' }) },
  { value: 'COMPLETED', label: defineMessage({ description: 'Downloads tab: completed', defaultMessage: 'Completed', id: 'fork/downloads/tab/completed' }) },
  { value: 'FAILED', label: defineMessage({ description: 'Downloads tab: failed', defaultMessage: 'Failed', id: 'fork/downloads/tab/failed' }) },
  { value: 'CANCELLED', label: defineMessage({ description: 'Downloads tab: cancelled', defaultMessage: 'Cancelled', id: 'fork/downloads/tab/cancelled' }) },
]

const filterLabel = defineMessage({
  description: 'Downloads view: text filter',
  defaultMessage: 'Filter by title or URL',
  id: 'fork/downloads/filter',
})

const addDialog = ref(false)
const newDownload = ref<DownloadCreateDto>({
  sourceUrl: '',
  libraryId: undefined,
  priority: 0,
  skipIfChapterExists: true,
})

const libraryItems = computed(() =>
  (libraries.value ?? []).map((l) => ({ title: l.name, value: l.id })),
)

function libraryName(id: string): string {
  return libraries.value?.find((l) => l.id === id)?.name ?? id
}

function statusColor(status: string): string {
  switch (status) {
    case 'DOWNLOADING':
      return 'primary'
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'error'
    case 'CANCELLED':
      return 'warning'
    case 'PAUSED':
      return 'info'
    default:
      return 'grey'
  }
}

function countByStatus(status: string): number {
  return merged.value.filter((d) => d.status === status).length
}

function formatDate(date: Date): string {
  return intl.formatDate(date, { dateStyle: 'medium', timeStyle: 'short' })
}

async function action(id: string, act: string) {
  try {
    await performAction({ id, action: act })
    // cancel/pause stop the download without a terminal SSE event, so drop any stale live-progress entry.
    if (act === 'cancel' || act === 'pause') downloadsStore.remove(id)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function remove(id: string) {
  try {
    await deleteDownload(id)
    downloadsStore.remove(id)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function clear(status: ClearableStatus) {
  try {
    await clearDownloads(status)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function add() {
  try {
    await createDownload(newDownload.value)
    addDialog.value = false
    newDownload.value = {
      sourceUrl: '',
      libraryId: undefined,
      priority: 0,
      skipIfChapterExists: true,
    }
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const downloadsFolderLabel = intl.formatMessage({
  description: 'Downloads view: default downloads folder label',
  defaultMessage: 'Downloads folder',
  id: 'fork/downloads/folder',
})

const cancelLabel = defineMessage({
  description: 'Downloads view: cancel action',
  defaultMessage: 'Cancel',
  id: 'fork/downloads/action/cancel',
})
const pauseLabel = defineMessage({
  description: 'Downloads view: pause action',
  defaultMessage: 'Pause',
  id: 'fork/downloads/action/pause',
})
const resumeLabel = defineMessage({
  description: 'Downloads view: resume action',
  defaultMessage: 'Resume',
  id: 'fork/downloads/action/resume',
})
const retryLabel = defineMessage({
  description: 'Downloads view: retry action',
  defaultMessage: 'Retry',
  id: 'fork/downloads/action/retry',
})
const deleteLabel = defineMessage({
  description: 'Downloads view: delete action',
  defaultMessage: 'Delete',
  id: 'fork/downloads/action/delete',
})
const urlLabel = defineMessage({
  description: 'Downloads view: source URL field',
  defaultMessage: 'Source URL',
  id: 'fork/downloads/field/url',
})
const libraryLabel = defineMessage({
  description: 'Downloads view: library field',
  defaultMessage: 'Library (optional)',
  id: 'fork/downloads/field/library',
})
const priorityLabel = defineMessage({
  description: 'Downloads view: priority field',
  defaultMessage: 'Priority',
  id: 'fork/downloads/field/priority',
})
const skipLabel = defineMessage({
  description: 'Downloads view: skip if chapter exists field',
  defaultMessage: 'Skip if chapter already exists',
  id: 'fork/downloads/field/skip',
})

const clearableStatuses: { value: ClearableStatus; status: string; label: MessageDescriptor }[] = [
  {
    value: 'completed',
    status: 'COMPLETED',
    label: defineMessage({
      description: 'Downloads view: clear completed',
      defaultMessage: 'Clear completed',
      id: 'fork/downloads/clear/completed',
    }),
  },
  {
    value: 'failed',
    status: 'FAILED',
    label: defineMessage({
      description: 'Downloads view: clear failed',
      defaultMessage: 'Clear failed',
      id: 'fork/downloads/clear/failed',
    }),
  },
  {
    value: 'cancelled',
    status: 'CANCELLED',
    label: defineMessage({
      description: 'Downloads view: clear cancelled',
      defaultMessage: 'Clear cancelled',
      id: 'fork/downloads/clear/cancelled',
    }),
  },
  {
    value: 'pending',
    status: 'PENDING',
    label: defineMessage({
      description: 'Downloads view: clear pending',
      defaultMessage: 'Clear pending',
      id: 'fork/downloads/clear/pending',
    }),
  },
]
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
