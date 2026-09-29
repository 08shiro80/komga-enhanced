<template>
  <div>
    <div class="d-flex align-center ga-2 mb-3">
      <v-text-field
        v-model="newUrl"
        :label="$formatMessage(urlLabel)"
        variant="outlined"
        density="compact"
        hide-details
        class="flex-grow-1"
        @keyup.enter="add()"
      />
      <v-btn
        color="primary"
        :loading="adding"
        :disabled="!newUrl"
        @click="add()"
      >
        {{ $formatMessage(addLabel) }}
      </v-btn>
    </div>

    <v-skeleton-loader
      v-if="isPending"
      type="list-item@3"
    />
    <v-alert
      v-else-if="(blacklist?.length ?? 0) === 0"
      type="info"
      variant="tonal"
      density="compact"
    >
      {{
        $formatMessage({
          description: 'Blacklist manager: empty',
          defaultMessage: 'No blacklisted chapters. Blacklisted chapters are skipped during follow checks.',
          id: 'fork/blacklist/empty',
        })
      }}
    </v-alert>
    <v-list v-else>
      <v-list-item
        v-for="entry in blacklist ?? []"
        :key="entry.id"
        :title="chapterLabel(entry)"
        :subtitle="entry.chapterUrl"
      >
        <template #append>
          <v-btn
            icon="i-mdi:delete"
            variant="text"
            size="small"
            color="error"
            :aria-label="$formatMessage(removeLabel)"
            @click="remove(entry.id)"
          />
        </template>
      </v-list-item>
    </v-list>
  </div>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useAddBlacklist, useRemoveBlacklist, useSeriesBlacklist } from '@/colada/blacklist'
import { useMessagesStore } from '@/stores/messages'
import type { BlacklistedChapter } from '@/generated/openapi'

const props = defineProps<{ seriesId: string }>()

const messagesStore = useMessagesStore()

const seriesIdRef = toRef(props, 'seriesId')
const { data: blacklist, isPending } = useSeriesBlacklist(seriesIdRef)
const { mutateAsync: addBlacklist, isLoading: adding } = useAddBlacklist()
const { mutateAsync: removeBlacklist } = useRemoveBlacklist()

const newUrl = ref('')

function chapterLabel(entry: BlacklistedChapter): string {
  const parts = [entry.chapterNumber, entry.chapterTitle].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : entry.chapterUrl
}

async function add() {
  if (!newUrl.value) return
  try {
    await addBlacklist({ seriesId: props.seriesId, chapterUrl: newUrl.value })
    newUrl.value = ''
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function remove(blacklistId: string) {
  try {
    await removeBlacklist({ seriesId: props.seriesId, blacklistId })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const urlLabel = defineMessage({
  description: 'Blacklist manager: chapter URL field',
  defaultMessage: 'MangaDex chapter URL to blacklist',
  id: 'fork/blacklist/url',
})
const addLabel = defineMessage({
  description: 'Blacklist manager: add button',
  defaultMessage: 'Blacklist',
  id: 'fork/blacklist/add',
})
const removeLabel = defineMessage({
  description: 'Blacklist manager: remove entry',
  defaultMessage: 'Remove',
  id: 'fork/blacklist/remove',
})
</script>
