<template>
  <div>
    <div class="d-flex align-center mb-3">
      <span class="text-body-2 text-medium-emphasis">
        {{ $formatMessage(hintLabel) }}
      </span>
      <v-spacer />
      <v-btn
        v-if="(sources?.length ?? 0) > 0"
        color="error"
        variant="text"
        size="small"
        prepend-icon="i-mdi:delete-sweep"
        :loading="clearing"
        @click="clearConfirm = true"
      >
        {{ $formatMessage(clearAllLabel) }}
      </v-btn>
    </div>

    <v-skeleton-loader
      v-if="isPending"
      type="list-item@3"
    />
    <v-alert
      v-else-if="(sources?.length ?? 0) === 0"
      type="info"
      variant="tonal"
      density="compact"
    >
      {{ $formatMessage(emptyLabel) }}
    </v-alert>
    <v-list v-else>
      <v-list-item
        v-for="s in sortedSources"
        :key="s.id"
      >
        <v-list-item-title>
          <span class="font-weight-medium">{{ chapterLabel(s) }}</span>
          <v-chip
            size="x-small"
            class="ms-2"
          >
            {{ s.source }}
          </v-chip>
          <v-chip
            v-if="s.lang"
            size="x-small"
            class="ms-1"
          >
            {{ s.lang }}
          </v-chip>
        </v-list-item-title>
        <v-list-item-subtitle style="white-space: normal; word-break: break-all">
          <a
            :href="safeExternalHref(s.url)"
            target="_blank"
            rel="noreferrer noopener"
            class="text-decoration-none"
          >{{ s.url }}</a>
        </v-list-item-subtitle>
        <template #append>
          <v-btn
            icon="i-mdi:delete"
            variant="text"
            size="small"
            color="error"
            :loading="deletingId === s.id"
            :aria-label="$formatMessage(deleteLabel)"
            @click="remove(s.id)"
          />
        </template>
      </v-list-item>
    </v-list>

    <v-dialog
      v-model="clearConfirm"
      max-width="440"
    >
      <v-card>
        <v-card-title class="text-error">
          {{ $formatMessage(clearConfirmTitle) }}
        </v-card-title>
        <v-card-text>
          {{ $formatMessage(clearConfirmBody) }}
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="clearConfirm = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="error"
            :loading="clearing"
            @click="clearAll()"
          >
            {{ $formatMessage(clearAllLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import {
  useDeleteAllChapterUrlsForSeries,
  useDeleteChapterUrl,
  useSeriesChapterUrls,
} from '@/colada/chapter-urls'
import { useMessagesStore } from '@/stores/messages'
import { safeExternalHref } from '@/utils/sanitize'
import type { ChapterUrlDto } from '@/generated/openapi'

const props = defineProps<{ seriesId: string }>()

const messagesStore = useMessagesStore()

const seriesIdRef = toRef(props, 'seriesId')
const { data: sources, isPending } = useSeriesChapterUrls(seriesIdRef)
const { mutateAsync: deleteChapterUrl } = useDeleteChapterUrl()
const { mutateAsync: deleteAll } = useDeleteAllChapterUrlsForSeries()

const deletingId = ref<string | null>(null)
const clearing = ref(false)
const clearConfirm = ref(false)

const sortedSources = computed(() =>
  [...(sources.value ?? [])].sort((a, b) => (a.chapter ?? 0) - (b.chapter ?? 0)),
)

function chapterLabel(s: ChapterUrlDto): string {
  const parts: string[] = []
  if (s.volume != null) parts.push(`Vol. ${s.volume}`)
  parts.push(`Ch. ${s.chapter}`)
  if (s.title) parts.push(s.title)
  return parts.join(' · ')
}

async function remove(id: string) {
  deletingId.value = id
  try {
    await deleteChapterUrl({ id, seriesId: props.seriesId })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    deletingId.value = null
  }
}

async function clearAll() {
  clearing.value = true
  try {
    await deleteAll(props.seriesId)
    clearConfirm.value = false
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    clearing.value = false
  }
}

const hintLabel = defineMessage({ description: 'Download sources: hint', defaultMessage: 'Tracked chapter source URLs for this series (used for re-download & blacklist).', id: 'fork/sources/hint' })
const clearAllLabel = defineMessage({ description: 'Download sources: clear all', defaultMessage: 'Clear all', id: 'fork/sources/clearAll' })
const emptyLabel = defineMessage({ description: 'Download sources: empty', defaultMessage: 'No tracked chapter URLs for this series.', id: 'fork/sources/empty' })
const deleteLabel = defineMessage({ description: 'Download sources: delete', defaultMessage: 'Remove source URL', id: 'fork/sources/delete' })
const cancelLabel = defineMessage({ description: 'Download sources: cancel', defaultMessage: 'Cancel', id: 'fork/sources/cancel' })
const clearConfirmTitle = defineMessage({ description: 'Download sources: clear confirm title', defaultMessage: 'Clear all tracked URLs?', id: 'fork/sources/clear/title' })
const clearConfirmBody = defineMessage({ description: 'Download sources: clear confirm body', defaultMessage: 'Removes all tracked chapter source URLs for this series. They will be re-tracked on the next download. This cannot be undone.', id: 'fork/sources/clear/body' })
</script>
