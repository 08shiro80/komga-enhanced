<template>
  <div>
    <v-row
      dense
      class="mb-1"
    >
      <v-col
        cols="12"
        md="6"
      >
        <v-text-field
          v-model="query"
          :label="$formatMessage(queryLabel)"
          variant="outlined"
          density="compact"
          hide-details
          clearable
          prepend-inner-icon="i-mdi:magnify"
          @keyup.enter="search()"
        />
      </v-col>
      <v-col
        cols="6"
        md="3"
      >
        <v-select
          v-model="order"
          :items="orderItems"
          :label="$formatMessage(orderLabel)"
          variant="outlined"
          density="compact"
          hide-details
        />
      </v-col>
      <v-col
        cols="6"
        md="3"
        class="d-flex align-center"
      >
        <v-btn
          block
          color="primary"
          :loading="isLoading"
          @click="search()"
        >
          {{ $formatMessage(searchLabel) }}
        </v-btn>
      </v-col>
    </v-row>

    <v-row
      dense
      class="mb-2"
    >
      <v-col
        cols="12"
        md="4"
      >
        <v-select
          v-model="contentRating"
          :items="contentRatingItems"
          :label="$formatMessage(contentRatingLabel)"
          variant="outlined"
          density="compact"
          hide-details
          multiple
          chips
          closable-chips
        />
      </v-col>
      <v-col
        cols="12"
        md="4"
      >
        <v-select
          v-model="status"
          :items="statusItems"
          :label="$formatMessage(statusLabel)"
          variant="outlined"
          density="compact"
          hide-details
          multiple
          chips
          closable-chips
        />
      </v-col>
      <v-col
        cols="12"
        md="4"
      >
        <v-select
          v-model="includedTagIds"
          :items="tagItems"
          :label="$formatMessage(tagsLabel)"
          variant="outlined"
          density="compact"
          hide-details
          multiple
          chips
          closable-chips
        />
      </v-col>
    </v-row>

    <v-skeleton-loader
      v-if="isLoading && !data"
      type="image, list-item-two-line"
    />

    <v-alert
      v-else-if="request && (data?.data.length ?? 0) === 0"
      type="info"
      variant="tonal"
      density="compact"
    >
      {{
        $formatMessage({
          description: 'MangaDex search: no results',
          defaultMessage: 'No results.',
          id: 'fork/mangadex/empty',
        })
      }}
    </v-alert>

    <template v-else-if="data">
      <div class="d-flex align-center mb-2">
        <v-switch
          v-model="hideFollowed"
          density="compact"
          hide-details
          color="primary"
          :label="$formatMessage(hideFollowedLabel)"
        />
        <v-spacer />
        <PosterSizeSlider class="me-4" />
        <span class="text-caption text-medium-emphasis">
          {{ data.total }} {{ $formatMessage(resultsLabel) }}
        </span>
      </div>

      <div class="d-flex flex-wrap ga-3">
        <v-card
          v-for="r in displayResults"
          :key="r.externalId"
          :width="cardWidth"
          :style="{ fontSize: cardFontSize }"
          variant="outlined"
          class="md-card d-flex flex-column"
        >
          <div
            class="cover-wrap"
            style="cursor: pointer"
            @click="showDetails(r)"
          >
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
              <v-icon icon="i-mdi:image-off" />
            </div>
          </div>
          <div class="md-content d-flex flex-column flex-grow-1">
            <div
              class="md-title"
              style="cursor: pointer"
              @click="showDetails(r)"
            >
              {{ r.title }}
            </div>
            <div
              v-if="r.author || r.year"
              class="md-sub"
            >
              {{ [r.author, r.year].filter(Boolean).join(' · ') }}
            </div>
            <div
              v-if="isDownloadable(r) === false || isLocalFollowed(r) || isMdFollowed(r)"
              class="md-chips d-flex flex-wrap"
            >
              <v-chip
                v-if="isDownloadable(r) === false"
                size="x-small"
                color="warning"
                variant="tonal"
              >
                {{ $formatMessage(noChaptersLabel) }}
              </v-chip>
              <v-chip
                v-if="isLocalFollowed(r)"
                size="x-small"
                color="success"
                variant="tonal"
              >
                {{ $formatMessage(followedLabel) }}
              </v-chip>
              <v-chip
                v-if="isMdFollowed(r)"
                size="x-small"
                color="info"
                variant="tonal"
              >
                {{ $formatMessage(onMangaDexLabel) }}
              </v-chip>
            </div>
            <div class="md-actions d-flex align-center mt-auto">
              <v-btn
                icon="i-mdi:download"
                variant="text"
                :size="btnSize"
                color="primary"
                :loading="busyId === r.externalId"
                :disabled="!libraryId"
                :aria-label="$formatMessage(downloadLabel)"
                @click="download(r)"
              />
              <v-spacer />
              <v-btn
                :icon="isMdFollowed(r) ? 'i-mdi:heart' : 'i-mdi:heart-outline'"
                variant="text"
                :size="btnSize"
                :color="isMdFollowed(r) ? 'error' : undefined"
                :aria-label="$formatMessage(mangaDexFollowLabel)"
                @click="toggleMangaDex(r)"
              />
              <v-btn
                icon="i-mdi:heart-plus"
                variant="text"
                :size="btnSize"
                :disabled="!libraryId || isLocalFollowed(r)"
                :aria-label="$formatMessage(followLabel)"
                @click="follow(r)"
              />
            </div>
          </div>
        </v-card>
      </div>

      <v-pagination
        v-if="pageCount > 1"
        v-model="page"
        :length="pageCount"
        :total-visible="display.xs.value ? 3 : 7"
        class="mt-4"
      />
    </template>

    <v-dialog
      v-model="detailsDialog"
      max-width="700"
      scrollable
      :fullscreen="display.xs.value"
    >
      <v-card v-if="detailsResult">
        <v-card-title style="word-break: normal">
          {{ detailsResult.title }}
        </v-card-title>
        <v-card-text>
          <v-row dense>
            <v-col
              cols="12"
              sm="4"
            >
              <img
                v-if="detailsResult.coverUrl"
                :src="detailsResult.coverUrl"
                referrerpolicy="no-referrer"
                alt=""
                style="width: 100%; border-radius: 4px"
              >
            </v-col>
            <v-col
              cols="12"
              sm="8"
            >
              <div class="d-flex flex-wrap align-center ga-1 mb-2">
                <v-chip
                  v-if="detailsResult.status"
                  size="x-small"
                  :color="statusColor(detailsResult.status)"
                >
                  {{ detailsResult.status }}
                </v-chip>
                <v-chip
                  v-if="detailsResult.year"
                  size="x-small"
                >
                  {{ detailsResult.year }}
                </v-chip>
                <span
                  v-if="detailsResult.author"
                  class="text-caption"
                >
                  {{ detailsResult.author }}
                </span>
              </div>
              <div
                v-if="detailsResult.tags.length"
                class="d-flex flex-wrap ga-1 mb-2"
              >
                <v-chip
                  v-for="t in detailsResult.tags"
                  :key="t"
                  size="x-small"
                  variant="outlined"
                >
                  {{ t }}
                </v-chip>
              </div>
              <div
                class="text-body-2"
                style="white-space: pre-line"
              >
                {{ detailsResult.description || $formatMessage(noDescriptionLabel) }}
              </div>
            </v-col>
          </v-row>
        </v-card-text>
        <v-card-actions>
          <v-btn
            variant="text"
            color="primary"
            prepend-icon="i-mdi:download"
            :loading="busyId === detailsResult.externalId"
            :disabled="!libraryId"
            @click="download(detailsResult)"
          >
            {{ $formatMessage(downloadLabel) }}
          </v-btn>
          <v-spacer />
          <v-btn
            variant="text"
            @click="detailsDialog = false"
          >
            {{ $formatMessage(closeLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import {
  useMangaDexFollows,
  useMangaDexSearch,
  useMangaDexTags,
  useToggleMangaDexFollow,
} from '@/colada/mangadex'
import { useCreateDownload } from '@/colada/downloads'
import { useAddFollow, useFollows } from '@/colada/follows'
import { useMessagesStore } from '@/stores/messages'
import { useAppStore } from '@/stores/app'
import { useDisplay } from 'vuetify'
import PosterSizeSlider from '@/components/PosterSizeSlider.vue'
import {
  komgaMangaDexDownloadableCheck,
  type MangaDexAdvancedSearchRequest,
  type MetadataSearchResult,
} from '@/generated/openapi'

const props = defineProps<{ libraryId?: string }>()

const messagesStore = useMessagesStore()
const appStore = useAppStore()
const display = useDisplay()
const cardWidth = computed(() => (display.smAndUp.value ? appStore.gridCardWidth : 150))
// Scale the whole card typography with its width so height/text stay proportional (175px ≈ 14px base).
const cardFontSize = computed(() => `${((14 * cardWidth.value) / 175).toFixed(1)}px`)
const btnSize = computed(() => (cardWidth.value < 155 ? 'x-small' : 'small'))

const libraryIdRef = toRef(props, 'libraryId')
const { data: localFollows } = useFollows(libraryIdRef)
const { data: mdFollows } = useMangaDexFollows()
const { mutateAsync: toggleMdFollow } = useToggleMangaDexFollow()

const hideFollowed = ref(false)
const availability = ref<Record<string, boolean>>({})

const localFollowedUuids = computed(() => {
  const set = new Set<string>()
  for (const f of localFollows.value ?? []) {
    const m = /mangadex\.org\/(?:title|manga)\/([0-9a-f-]{36})/i.exec(f.url)
    if (m) set.add(m[1]!.toLowerCase())
  }
  return set
})

const mdFollowedUuids = computed(() => {
  const uuids = (mdFollows.value as { uuids?: string[] } | undefined)?.uuids ?? []
  return new Set(uuids.map((u) => u.toLowerCase()))
})

function isLocalFollowed(r: MetadataSearchResult): boolean {
  return localFollowedUuids.value.has(r.externalId.toLowerCase())
}

function isMdFollowed(r: MetadataSearchResult): boolean {
  return mdFollowedUuids.value.has(r.externalId.toLowerCase())
}

function isDownloadable(r: MetadataSearchResult): boolean | undefined {
  return availability.value[r.externalId]
}

const LIMIT = 24
const page = ref(1)
const query = ref('')
const contentRating = ref<string[]>(['safe', 'suggestive', 'erotica'])
const status = ref<string[]>([])
const order = ref('relevance')
const includedTagIds = ref<string[]>([])
const request = ref<MangaDexAdvancedSearchRequest | undefined>(undefined)

const { data, isLoading } = useMangaDexSearch(request)
const { data: tags } = useMangaDexTags()

const { mutateAsync: createDownload } = useCreateDownload()
const { mutateAsync: addFollow } = useAddFollow()

const busyId = ref<string | null>(null)

const detailsDialog = ref(false)
const detailsResult = ref<MetadataSearchResult | null>(null)

function showDetails(r: MetadataSearchResult) {
  detailsResult.value = r
  detailsDialog.value = true
}

function statusColor(value: string): string {
  return (
    {
      ongoing: 'primary',
      releasing: 'primary',
      completed: 'success',
      ended: 'success',
      finished: 'success',
      hiatus: 'warning',
      cancelled: 'error',
      canceled: 'error',
    }[value.toLowerCase()] ?? 'grey'
  )
}

function buildRequest(): MangaDexAdvancedSearchRequest {
  return {
    query: query.value || undefined,
    contentRating: contentRating.value.length ? contentRating.value : undefined,
    status: status.value.length ? status.value : undefined,
    includedTagIds: includedTagIds.value.length ? includedTagIds.value : undefined,
    order: order.value !== 'relevance' ? order.value : undefined,
    orderDir: 'desc',
    hasAvailableChapters: true,
    limit: LIMIT,
    offset: (page.value - 1) * LIMIT,
  }
}

function search() {
  page.value = 1
  request.value = buildRequest()
}

watch(page, () => {
  if (request.value) request.value = buildRequest()
})

const displayResults = computed(() => {
  const items = data.value?.data ?? []
  if (!hideFollowed.value) return items
  return items.filter((r) => !isLocalFollowed(r) && !isMdFollowed(r))
})

let availabilityToken = 0

watch(
  () => data.value?.data,
  async (items) => {
    if (!items || items.length === 0) return
    // Sequence guard: only the most recent request may write availability, so a late
    // response from a previous page can't overwrite the current one.
    const token = ++availabilityToken
    try {
      const result = await komgaMangaDexDownloadableCheck({
        body: { ids: items.map((r) => r.externalId), language: 'en' },
      })
      if (token === availabilityToken) availability.value = result
    } catch {
      if (token === availabilityToken) availability.value = {}
    }
  },
)

const pageCount = computed(() => {
  const total = data.value?.total ?? 0
  if (total <= LIMIT) return 1
  return Math.min(Math.ceil(total / LIMIT), 417)
})

const tagItems = computed(() =>
  (tags.value ?? []).map((t) => ({ title: `${t.group}: ${t.name}`, value: t.id })),
)

function mangadexUrl(r: MetadataSearchResult): string {
  return `https://mangadex.org/title/${r.externalId}`
}

async function download(r: MetadataSearchResult) {
  if (!props.libraryId) return
  busyId.value = r.externalId
  try {
    await createDownload({
      sourceUrl: mangadexUrl(r),
      libraryId: props.libraryId,
      title: r.title,
      priority: 0,
      skipIfChapterExists: true,
    })
    messagesStore.messages.push({ message: r.title })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    busyId.value = null
  }
}

async function follow(r: MetadataSearchResult) {
  if (!props.libraryId) return
  try {
    await addFollow({
      libraryId: props.libraryId,
      body: { url: mangadexUrl(r), title: r.title },
    })
    messagesStore.messages.push({ message: r.title })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function toggleMangaDex(r: MetadataSearchResult) {
  try {
    await toggleMdFollow({ mangaId: r.externalId, unfollow: isMdFollowed(r) })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const contentRatingItems = ['safe', 'suggestive', 'erotica', 'pornographic']
const statusItems = ['ongoing', 'completed', 'hiatus', 'cancelled']
const orderItems = [
  { title: 'Relevance', value: 'relevance' },
  { title: 'Latest chapter', value: 'latestUploadedChapter' },
  { title: 'Most followed', value: 'followedCount' },
  { title: 'Rating', value: 'rating' },
  { title: 'Title', value: 'title' },
  { title: 'Year', value: 'year' },
]

const queryLabel = defineMessage({
  description: 'MangaDex search: query field',
  defaultMessage: 'Search MangaDex',
  id: 'fork/mangadex/query',
})
const orderLabel = defineMessage({
  description: 'MangaDex search: order field',
  defaultMessage: 'Order by',
  id: 'fork/mangadex/order',
})
const searchLabel = defineMessage({
  description: 'MangaDex search: search button',
  defaultMessage: 'Search',
  id: 'fork/mangadex/search',
})
const contentRatingLabel = defineMessage({
  description: 'MangaDex search: content rating filter',
  defaultMessage: 'Content rating',
  id: 'fork/mangadex/contentRating',
})
const statusLabel = defineMessage({
  description: 'MangaDex search: status filter',
  defaultMessage: 'Status',
  id: 'fork/mangadex/status',
})
const tagsLabel = defineMessage({
  description: 'MangaDex search: tags filter',
  defaultMessage: 'Tags',
  id: 'fork/mangadex/tags',
})
const downloadLabel = defineMessage({
  description: 'MangaDex search: download result',
  defaultMessage: 'Download',
  id: 'fork/mangadex/download',
})
const followLabel = defineMessage({
  description: 'MangaDex search: follow result',
  defaultMessage: 'Follow',
  id: 'fork/mangadex/follow',
})
const mangaDexFollowLabel = defineMessage({
  description: 'MangaDex search: toggle follow on MangaDex account',
  defaultMessage: 'Follow on MangaDex',
  id: 'fork/mangadex/mdfollow',
})
const hideFollowedLabel = defineMessage({
  description: 'MangaDex search: hide already followed toggle',
  defaultMessage: 'Hide already followed',
  id: 'fork/mangadex/hideFollowed',
})
const resultsLabel = defineMessage({
  description: 'MangaDex search: results count suffix',
  defaultMessage: 'results',
  id: 'fork/mangadex/results',
})
const noChaptersLabel = defineMessage({
  description: 'MangaDex search: no downloadable chapters badge',
  defaultMessage: 'No EN chapters',
  id: 'fork/mangadex/noChapters',
})
const followedLabel = defineMessage({
  description: 'MangaDex search: already followed badge',
  defaultMessage: 'Followed',
  id: 'fork/mangadex/followed',
})
const onMangaDexLabel = defineMessage({
  description: 'MangaDex search: on mangadex account badge',
  defaultMessage: 'On MangaDex',
  id: 'fork/mangadex/onMd',
})
const noDescriptionLabel = defineMessage({
  description: 'MangaDex search: no description available in details dialog',
  defaultMessage: 'No description available.',
  id: 'fork/mangadex/noDescription',
})
const closeLabel = defineMessage({
  description: 'MangaDex search: close details dialog button',
  defaultMessage: 'Close',
  id: 'fork/mangadex/close',
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
.md-content {
  padding: 0.4em 0.55em 0.3em;
  gap: 0.2em;
}
.md-title {
  font-size: 0.95em;
  font-weight: 500;
  line-height: 1.2;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.md-sub {
  font-size: 0.78em;
  line-height: 1.2;
  opacity: 0.7;
}
.md-chips {
  gap: 0.25em;
  margin-top: 0.15em;
}
.md-actions {
  margin-top: auto;
  padding-top: 0.2em;
}
</style>
