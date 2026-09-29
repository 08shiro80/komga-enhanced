<template>
  <v-container fluid>
    <div class="d-flex align-center mb-4">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Download dashboard: page title',
            defaultMessage: 'Download dashboard',
            id: 'fork/dashboard/title',
          })
        }}
      </span>
    </div>

    <EmptyStateNetworkError v-if="downloadsError" />

    <template v-else>
      <!-- Stats -->
      <v-row class="mb-2">
        <v-col
          v-for="stat in stats"
          :key="stat.status"
          cols="6"
          md="3"
        >
          <v-card
            variant="tonal"
            :to="{ path: '/downloads/queue', query: { status: stat.status } }"
          >
            <v-card-text class="text-center">
              <div
                class="text-h4"
                :class="`text-${stat.color}`"
              >
                {{ stat.count }}
              </div>
              <div class="text-caption text-medium-emphasis">
                {{ $formatMessage(stat.label) }}
              </div>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- Quick navigation -->
      <v-row class="mb-2">
        <v-col
          v-for="link in quickLinks"
          :key="link.to"
          cols="6"
          md="3"
        >
          <v-card
            variant="outlined"
            :to="link.to"
          >
            <v-card-text class="d-flex align-center ga-3">
              <v-icon
                :icon="link.icon"
                size="28"
                color="primary"
              />
              <span class="text-body-1">{{ $formatMessage(link.label) }}</span>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- Active/pending live list -->
      <v-card variant="outlined">
        <v-card-title class="d-flex align-center text-subtitle-1">
          {{
            $formatMessage({
              description: 'Download dashboard: active downloads section',
              defaultMessage: 'Active downloads',
              id: 'fork/dashboard/active',
            })
          }}
          <v-spacer />
          <v-btn
            variant="text"
            size="small"
            prepend-icon="i-mdi:format-list-bulleted"
            :to="{ path: '/downloads/queue' }"
          >
            {{
              $formatMessage({
                description: 'Download dashboard: open full queue',
                defaultMessage: 'Full queue',
                id: 'fork/dashboard/queue',
              })
            }}
          </v-btn>
        </v-card-title>
        <v-card-text>
          <v-alert
            v-if="active.length === 0"
            type="info"
            variant="tonal"
            density="compact"
          >
            {{
              $formatMessage({
                description: 'Download dashboard: no active downloads',
                defaultMessage: 'Nothing downloading right now.',
                id: 'fork/dashboard/active/empty',
              })
            }}
          </v-alert>
          <div
            v-for="item in active"
            :key="item.id"
            class="mb-3"
          >
            <div class="d-flex align-center">
              <span class="text-body-2 text-truncate">{{ item.title || item.sourceUrl }}</span>
              <v-spacer />
              <v-chip
                size="x-small"
                :color="statusColor(item.status)"
              >
                {{ item.status }}
              </v-chip>
            </div>
            <v-progress-linear
              v-if="item.status === 'DOWNLOADING'"
              :model-value="item.progressPercent"
              height="16"
              rounded
              color="primary"
              class="mt-1"
            >
              <span class="text-caption">
                {{ item.progressPercent }}%
                <template v-if="item.totalChapters">
                  ({{ item.currentChapter }}/{{ item.totalChapters }})
                </template>
              </span>
            </v-progress-linear>
          </div>
        </v-card-text>
      </v-card>
    </template>
  </v-container>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useDownloads } from '@/colada/downloads'
import { useDownloadsStore } from '@/stores/downloads'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'

const { data: downloads, error: downloadsError } = useDownloads()
const downloadsStore = useDownloadsStore()

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

const active = computed(() =>
  merged.value.filter((d) => d.status === 'DOWNLOADING' || d.status === 'PENDING'),
)

function countByStatus(status: string): number {
  return merged.value.filter((d) => d.status === status).length
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
    default:
      return 'grey'
  }
}

const stats = computed(() => [
  { status: 'DOWNLOADING', color: 'primary', count: countByStatus('DOWNLOADING'), label: statActiveLabel },
  { status: 'PENDING', color: 'grey', count: countByStatus('PENDING'), label: statPendingLabel },
  { status: 'COMPLETED', color: 'success', count: countByStatus('COMPLETED'), label: statCompletedLabel },
  { status: 'FAILED', color: 'error', count: countByStatus('FAILED'), label: statFailedLabel },
])

const quickLinks = [
  { to: '/downloads/followed', icon: 'i-mdi:heart', label: defineMessage({ description: 'Download dashboard: link followed', defaultMessage: 'Followed series', id: 'fork/dashboard/link/followed' }) },
  { to: '/downloads/discover', icon: 'i-mdi:magnify', label: defineMessage({ description: 'Download dashboard: link discover', defaultMessage: 'Discover', id: 'fork/dashboard/link/discover' }) },
  { to: '/downloads/blacklisted', icon: 'i-mdi:cancel', label: defineMessage({ description: 'Download dashboard: link blacklisted', defaultMessage: 'Blacklisted', id: 'fork/dashboard/link/blacklisted' }) },
  { to: '/downloads/queue', icon: 'i-mdi:format-list-bulleted', label: defineMessage({ description: 'Download dashboard: link queue', defaultMessage: 'Queue', id: 'fork/dashboard/link/queue' }) },
]

const statActiveLabel = defineMessage({
  description: 'Download dashboard: stat active',
  defaultMessage: 'Downloading',
  id: 'fork/dashboard/stat/active',
})
const statPendingLabel = defineMessage({
  description: 'Download dashboard: stat pending',
  defaultMessage: 'Pending',
  id: 'fork/dashboard/stat/pending',
})
const statCompletedLabel = defineMessage({
  description: 'Download dashboard: stat completed',
  defaultMessage: 'Completed',
  id: 'fork/dashboard/stat/completed',
})
const statFailedLabel = defineMessage({
  description: 'Download dashboard: stat failed',
  defaultMessage: 'Failed',
  id: 'fork/dashboard/stat/failed',
})
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
