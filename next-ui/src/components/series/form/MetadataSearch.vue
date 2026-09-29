<template>
  <v-sheet class="pa-4">
    <p class="text-body-2 text-medium-emphasis mb-3">
      {{
        $formatMessage({
          description: 'Series form metadata search: hint',
          defaultMessage:
            'Search a metadata provider and pick a result to auto-fill the fields on the other tabs. Review them, then Save to apply.',
          id: 'fork/seriesform/metasearch/hint',
        })
      }}
    </p>

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
          description: 'Series form metadata search: no plugins',
          defaultMessage: 'No metadata plugins enabled. Enable MangaDex or AniList in the plugin manager.',
          id: 'fork/seriesform/metasearch/noPlugins',
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
          description: 'Series form metadata search: no results',
          defaultMessage: 'No results.',
          id: 'fork/seriesform/metasearch/empty',
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
          :loading="fillingId === r.externalId"
          @click="fill(r)"
        >
          <div class="cover-wrap">
            <img
              v-if="r.coverUrl"
              :src="r.coverUrl"
              referrerpolicy="no-referrer"
              loading="lazy"
              alt=""
              class="cover-img"
            >
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
  </v-sheet>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useMetadataSearch } from '@/colada/metadata-search'
import { usePlugins } from '@/colada/plugins'
import { useMessagesStore } from '@/stores/messages'
import { komgaGetMetadata, type MetadataSearchResult, type SeriesMetadataDto } from '@/generated/openapi'

const model = defineModel<SeriesMetadataDto>({ required: true })

const messagesStore = useMessagesStore()

const { data: plugins } = usePlugins()
const pluginItems = computed(() =>
  (plugins.value ?? [])
    .filter((p) => p.pluginType === 'METADATA' && p.enabled && p.id !== 'auto-metadata')
    .map((p) => ({ title: p.name, value: p.id })),
)

const selectedPlugin = ref<string | undefined>(undefined)
const query = ref(model.value.title ?? '')

watchEffect(() => {
  if (!selectedPlugin.value && pluginItems.value.length > 0) {
    selectedPlugin.value = pluginItems.value[0]?.value
  }
})

const searchParams = ref<{ pluginId: string; query: string } | undefined>(undefined)
const { data, isLoading } = useMetadataSearch(searchParams)

const fillingId = ref<string | null>(null)

function search() {
  if (!selectedPlugin.value || !query.value) return
  searchParams.value = { pluginId: selectedPlugin.value, query: query.value }
}

async function fill(result: MetadataSearchResult) {
  if (!selectedPlugin.value) return
  fillingId.value = result.externalId
  try {
    const md = await komgaGetMetadata({
      path: { id: selectedPlugin.value, externalId: result.externalId },
    })
    // Only fill fields the user has not locked, and don't force new locks — let the user decide
    // (matches the webui behaviour). alternativeTitles maps title -> language code.
    const m = model.value
    if (md.title && !m.titleLock) m.title = md.title
    if (md.titleSort && !m.titleSortLock) m.titleSort = md.titleSort
    if (md.summary && !m.summaryLock) m.summary = md.summary
    if (md.publisher && !m.publisherLock) m.publisher = md.publisher
    if (md.ageRating != null && !m.ageRatingLock) m.ageRating = md.ageRating
    if (md.status && !m.statusLock) m.status = md.status
    if (md.language && !m.languageLock) m.language = md.language
    if (md.genres?.length && !m.genresLock) m.genres = [...md.genres]
    if (md.tags?.length && !m.tagsLock) m.tags = [...md.tags]
    if (!m.alternateTitlesLock) {
      const alts = Object.entries(md.alternativeTitles ?? {}).map(([title, label]) => ({
        label,
        title,
      }))
      if (alts.length) m.alternateTitles = alts
    }
    messagesStore.messages.push({ message: filledMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    fillingId.value = null
  }
}

const providerLabel = defineMessage({ description: 'Series form metadata search: provider', defaultMessage: 'Provider', id: 'fork/seriesform/metasearch/provider' })
const queryLabel = defineMessage({ description: 'Series form metadata search: query', defaultMessage: 'Search title', id: 'fork/seriesform/metasearch/query' })
const searchLabel = defineMessage({ description: 'Series form metadata search: search', defaultMessage: 'Search', id: 'fork/seriesform/metasearch/searchBtn' })
const filledMessage = defineMessage({ description: 'Series form metadata search: filled', defaultMessage: 'Fields filled from provider — review the tabs and Save to apply', id: 'fork/seriesform/metasearch/filled' })
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
