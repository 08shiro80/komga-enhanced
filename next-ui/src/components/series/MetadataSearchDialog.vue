<template>
  <div>
    <div class="d-flex align-center ga-2 mb-3">
      <v-select
        v-model="selectedPlugin"
        :items="pluginItems"
        :label="$formatMessage(providerLabel)"
        variant="outlined"
        density="compact"
        hide-details
        style="max-width: 200px"
      />
      <v-text-field
        v-model="query"
        :label="$formatMessage(queryLabel)"
        variant="outlined"
        density="compact"
        hide-details
        class="flex-grow-1"
        @keyup.enter="search()"
      />
      <v-btn
        color="primary"
        :loading="isLoading"
        :disabled="!selectedPlugin || !query"
        @click="search()"
      >
        {{ $formatMessage(searchLabel) }}
      </v-btn>
    </div>

    <v-alert
      v-if="pluginItems.length === 0"
      type="warning"
      variant="tonal"
      density="compact"
    >
      {{
        $formatMessage({
          description: 'Metadata search: no metadata plugins',
          defaultMessage: 'No metadata plugins enabled. Enable MangaDex or AniList in the plugin manager.',
          id: 'fork/metasearch/noPlugins',
        })
      }}
    </v-alert>

    <v-alert
      v-else-if="searchParams && (data?.length ?? 0) === 0 && !isLoading"
      type="info"
      variant="tonal"
      density="compact"
    >
      {{
        $formatMessage({
          description: 'Metadata search: no results',
          defaultMessage: 'No results.',
          id: 'fork/metasearch/empty',
        })
      }}
    </v-alert>

    <v-row v-else-if="data">
      <v-col
        v-for="r in data"
        :key="r.externalId"
        cols="6"
        sm="4"
        md="3"
      >
        <v-card
          variant="outlined"
          :loading="applyingId === r.externalId"
          @click="apply(r)"
        >
          <div class="cover-wrap">
            <img
              v-if="r.coverUrl"
              :src="r.coverUrl"
              referrerpolicy="no-referrer"
              loading="lazy"
              alt=""
              class="cover-img"
            />
            <div
              v-else
              class="d-flex align-center justify-center fill-height bg-surface-variant"
            >
              <v-icon icon="i-mdi:book" />
            </div>
          </div>
          <v-card-title class="text-body-2 text-wrap">
            {{ r.title }}
          </v-card-title>
          <v-card-subtitle v-if="r.author || r.year">
            {{ [r.author, r.year].filter(Boolean).join(' · ') }}
          </v-card-subtitle>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useMetadataSearch, useApplyMetadataFromResult } from '@/colada/metadata-search'
import { usePlugins } from '@/colada/plugins'
import { useMessagesStore } from '@/stores/messages'
import type { MetadataSearchResult } from '@/generated/openapi'

const props = defineProps<{ seriesId: string; initialQuery?: string }>()

const messagesStore = useMessagesStore()

const { data: plugins } = usePlugins()
const pluginItems = computed(() =>
  (plugins.value ?? [])
    .filter((p) => p.pluginType === 'METADATA' && p.enabled && p.id !== 'auto-metadata')
    .map((p) => ({ title: p.name, value: p.id })),
)

const selectedPlugin = ref<string | undefined>(undefined)
const query = ref(props.initialQuery ?? '')

watchEffect(() => {
  if (!selectedPlugin.value && pluginItems.value.length > 0) {
    selectedPlugin.value = pluginItems.value[0]?.value
  }
})

const searchParams = ref<{ pluginId: string; query: string } | undefined>(undefined)
const { data, isLoading } = useMetadataSearch(searchParams)
const { mutateAsync: applyMetadata } = useApplyMetadataFromResult()

const applyingId = ref<string | null>(null)

function search() {
  if (!selectedPlugin.value || !query.value) return
  searchParams.value = { pluginId: selectedPlugin.value, query: query.value }
}

async function apply(result: MetadataSearchResult) {
  if (!selectedPlugin.value) return
  applyingId.value = result.externalId
  try {
    await applyMetadata({ seriesId: props.seriesId, pluginId: selectedPlugin.value, result })
    messagesStore.messages.push({ message: appliedMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    applyingId.value = null
  }
}

const providerLabel = defineMessage({
  description: 'Metadata search: provider select',
  defaultMessage: 'Provider',
  id: 'fork/metasearch/provider',
})
const queryLabel = defineMessage({
  description: 'Metadata search: query field',
  defaultMessage: 'Search title',
  id: 'fork/metasearch/query',
})
const searchLabel = defineMessage({
  description: 'Metadata search: search button',
  defaultMessage: 'Search',
  id: 'fork/metasearch/search',
})
const appliedMessage = defineMessage({
  description: 'Metadata search: metadata applied',
  defaultMessage: 'Metadata applied',
  id: 'fork/metasearch/applied',
})
</script>

<style scoped>
.cover-wrap {
  position: relative;
  width: 100%;
  aspect-ratio: 2 / 3;
  overflow: hidden;
}
.cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
</style>
