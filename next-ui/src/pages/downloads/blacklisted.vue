<template>
  <v-container fluid>
    <div class="d-flex align-center mb-3">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Blacklisted view: title',
            defaultMessage: 'Blacklisted chapters',
            id: 'fork/blacklisted/title',
          })
        }}
      </span>
      <v-spacer />
      <v-btn
        icon="i-mdi:refresh"
        variant="text"
        :loading="isPending"
        :aria-label="$formatMessage(refreshLabel)"
        @click="refetch()"
      />
    </div>

    <p class="text-body-2 text-medium-emphasis mb-4">
      {{
        $formatMessage({
          description: 'Blacklisted view: subtitle',
          defaultMessage: 'Chapters that were skipped or auto-blacklisted, grouped by series. Remove an entry to allow it to be downloaded again.',
          id: 'fork/blacklisted/subtitle',
        })
      }}
    </p>

    <v-text-field
      v-model="search"
      :label="$formatMessage(filterLabel)"
      variant="outlined"
      density="compact"
      hide-details
      clearable
      prepend-inner-icon="i-mdi:magnify"
      class="mb-3"
    />

    <EmptyStateNetworkError v-if="error" />
    <v-skeleton-loader
      v-else-if="isPending && !data"
      type="list-item@4"
    />
    <v-alert
      v-else-if="filtered.length === 0"
      type="success"
      variant="tonal"
    >
      {{
        $formatMessage({
          description: 'Blacklisted view: empty',
          defaultMessage: 'No blacklisted chapters.',
          id: 'fork/blacklisted/empty',
        })
      }}
    </v-alert>

    <v-expansion-panels
      v-else
      multiple
    >
      <v-expansion-panel
        v-for="group in filtered"
        :key="group.seriesId"
      >
        <v-expansion-panel-title>
          <div class="d-flex align-center ga-2 flex-grow-1">
            <RouterLink
              :to="{ name: '/series/[id]', params: { id: group.seriesId } }"
              class="text-decoration-none"
              @click.stop
            >
              {{ group.seriesTitle }}
            </RouterLink>
            <v-chip size="x-small">
              {{ group.chapters.length }}
            </v-chip>
          </div>
        </v-expansion-panel-title>
        <v-expansion-panel-text>
          <v-table density="compact">
            <thead>
              <tr>
                <th>{{ $formatMessage(colChapter) }}</th>
                <th>{{ $formatMessage(colUrl) }}</th>
                <th>{{ $formatMessage(colDate) }}</th>
                <th class="text-end">
                  {{ $formatMessage(colActions) }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="ch in group.chapters"
                :key="ch.id"
              >
                <td>{{ chapterLabel(ch) }}</td>
                <td class="text-caption">
                  <a
                    :href="safeExternalHref(ch.chapterUrl)"
                    target="_blank"
                    rel="noreferrer noopener"
                    class="text-decoration-none"
                  >
                    {{ ch.chapterUrl }}
                  </a>
                </td>
                <td class="text-caption text-no-wrap">
                  {{ formatDate(ch.createdDate) }}
                </td>
                <td class="text-end">
                  <v-btn
                    icon="i-mdi:delete"
                    variant="text"
                    size="small"
                    color="error"
                    :loading="removingId === ch.id"
                    :aria-label="$formatMessage(removeLabel)"
                    @click="remove(ch.id)"
                  />
                </td>
              </tr>
            </tbody>
          </v-table>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
  </v-container>
</template>

<script lang="ts" setup>
import { useIntl, defineMessage } from 'vue-intl'
import {
  useAllBlacklisted,
  useRemoveBlacklistedGlobal,
  type BlacklistedChapterDto,
} from '@/colada/blacklist'
import { useMessagesStore } from '@/stores/messages'
import { safeExternalHref } from '@/utils/sanitize'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'

const intl = useIntl()
const messagesStore = useMessagesStore()

const { data, error, isPending, refetch } = useAllBlacklisted()
const { mutateAsync: removeBlacklisted } = useRemoveBlacklistedGlobal()

const search = ref('')
const removingId = ref<string | null>(null)

const filtered = computed(() => {
  const q = search.value?.trim().toLowerCase()
  // Newest blacklisted chapter first within each series.
  let groups = (data.value ?? []).map((g) => ({
    ...g,
    chapters: [...g.chapters].sort((a, b) =>
      (b.createdDate ?? '').localeCompare(a.createdDate ?? ''),
    ),
  }))
  if (q) {
    groups = groups
      .map((g) => ({
        ...g,
        chapters: g.chapters.filter(
          (c) =>
            g.seriesTitle.toLowerCase().includes(q) ||
            (c.chapterTitle || '').toLowerCase().includes(q) ||
            (c.chapterNumber || '').toLowerCase().includes(q) ||
            c.chapterUrl.toLowerCase().includes(q),
        ),
      }))
      .filter((g) => g.chapters.length > 0 || g.seriesTitle.toLowerCase().includes(q))
  }
  // Series with the most recently blacklisted chapter first.
  return groups.sort((a, b) =>
    (b.chapters[0]?.createdDate ?? '').localeCompare(a.chapters[0]?.createdDate ?? ''),
  )
})

function chapterLabel(ch: BlacklistedChapterDto): string {
  if (ch.chapterNumber && ch.chapterTitle) return `${ch.chapterNumber} — ${ch.chapterTitle}`
  return ch.chapterNumber || ch.chapterTitle || '—'
}

function formatDate(date: string): string {
  return intl.formatDate(date, { dateStyle: 'medium', timeStyle: 'short' })
}

async function remove(id: string) {
  removingId.value = id
  try {
    await removeBlacklisted(id)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    removingId.value = null
  }
}

const refreshLabel = defineMessage({ description: 'Blacklisted: refresh', defaultMessage: 'Refresh', id: 'fork/blacklisted/refresh' })
const filterLabel = defineMessage({ description: 'Blacklisted: filter', defaultMessage: 'Filter by series, chapter or URL', id: 'fork/blacklisted/filter' })
const colChapter = defineMessage({ description: 'Blacklisted: col chapter', defaultMessage: 'Chapter', id: 'fork/blacklisted/col/chapter' })
const colUrl = defineMessage({ description: 'Blacklisted: col url', defaultMessage: 'URL', id: 'fork/blacklisted/col/url' })
const colDate = defineMessage({ description: 'Blacklisted: col date', defaultMessage: 'Blacklisted at', id: 'fork/blacklisted/col/date' })
const colActions = defineMessage({ description: 'Blacklisted: col actions', defaultMessage: 'Actions', id: 'fork/blacklisted/col/actions' })
const removeLabel = defineMessage({ description: 'Blacklisted: remove', defaultMessage: 'Remove from blacklist', id: 'fork/blacklisted/remove' })
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
