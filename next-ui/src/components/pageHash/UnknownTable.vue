<template>
  <div>
    <v-toolbar
      flat
      density="comfortable"
    >
      <v-icon
        color="medium-emphasis"
        icon="i-mdi:content-copy"
        size="x-small"
        start
        class="ms-2"
      />
      <v-toolbar-title>
        {{ $formatMessage(titleMsg) }}
        <span class="text-caption text-medium-emphasis">({{ data?.totalElements ?? 0 }})</span>
      </v-toolbar-title>

      <v-spacer />

      <template v-if="selectedHashes.length > 0">
        <span class="text-caption text-medium-emphasis me-2">
          {{ $formatMessage(selectedCountMsg, { count: selectedHashes.length }) }}
        </span>
        <v-btn
          variant="elevated"
          class="me-1"
          append-icon="i-mdi:menu-down"
        >
          {{ $formatMessage(markAsMsg) }}
          <v-menu activator="parent">
            <v-list
              density="compact"
              slim
            >
              <v-list-item
                v-for="(item, index) in actionOptions"
                :key="index"
                :title="item.title"
                :prepend-icon="item.icon"
                @click="updateHashActions(selectedHashes, item.value)"
              />
            </v-list>
          </v-menu>
        </v-btn>
        <v-btn
          v-ktooltip:bottom="$formatMessage(clearSelectionMsg)"
          variant="text"
          icon="i-mdi:close"
          @click="clearSelection"
        />
      </template>

      <template v-else>
        <v-btn
          variant="text"
          append-icon="i-mdi:menu-down"
          prepend-icon="i-mdi:sort"
        >
          {{ $formatMessage(currentSortOption.title) }}
          <v-menu activator="parent">
            <v-list
              density="compact"
              slim
            >
              <v-list-item
                v-for="opt in sortOptions"
                :key="opt.key"
                :title="$formatMessage(opt.title)"
                :active="sortKey === opt.key"
                @click="setSort(opt.key)"
              />
            </v-list>
          </v-menu>
        </v-btn>
        <PosterSizeSlider class="mx-2" />
      </template>
    </v-toolbar>

    <EmptyStateNetworkError
      v-if="error"
      class="mt-8"
    />

    <div
      v-else-if="!isLoading && (data?.content?.length ?? 0) === 0"
      class="text-center text-medium-emphasis pa-8"
    >
      {{ $formatMessage(noDataMsg) }}
    </div>

    <template v-else>
      <div
        class="page-hash-grid pa-3"
        :style="{ gridTemplateColumns: `repeat(auto-fill, minmax(min(100%, ${cardWidth}px), 1fr))` }"
      >
        <v-card
          v-for="h in data?.content ?? []"
          :key="h.hash"
          variant="outlined"
          class="d-flex flex-column"
        >
          <div
            class="cover-wrap"
            style="cursor: zoom-in"
            @mouseenter="(event: Event) => (dialogSimple.activator = event.currentTarget as Element)"
            @click="showDialogImage(h.hash)"
          >
            <v-img
              contain
              :src="pageHashUnknownThumbnailUrl(h.hash)"
              lazy-src="@/assets/cover.svg"
              class="cover-img"
              :alt="$formatMessage(altMsg)"
            >
              <template #placeholder>
                <div class="d-flex align-center justify-center fill-height">
                  <v-progress-circular
                    color="grey"
                    indeterminate
                  />
                </div>
              </template>
              <v-checkbox-btn
                v-if="canSelect(h)"
                :model-value="isSelected(h)"
                class="select-overlay"
                density="compact"
                @click.stop="toggleSelect(h)"
              />
              <v-chip
                v-else
                size="x-small"
                class="select-overlay"
                :color="actionColor(getPageHashAction(h))"
                variant="flat"
              >
                {{ getPageHashAction(h) ? $formatMessage(pageHashActionMessages[getPageHashAction(h)!]) : '' }}
              </v-chip>
            </v-img>
          </div>

          <div class="d-flex flex-column flex-grow-1 ga-1 pa-2">
            <div
              v-if="h.seriesTitle"
              class="d-flex align-center text-truncate"
              :title="h.seriesTitle"
            >
              <v-icon
                icon="i-mdi:book-multiple"
                size="small"
                start
              />
              <span class="text-truncate">{{ h.seriesTitle }}</span>
            </div>

            <v-btn
              variant="tonal"
              size="small"
              prepend-icon="i-mdi:image-multiple-outline"
              :disabled="h.matchCount === 0"
              @mouseenter="(event: Event) => (dialogSimple.activator = event.currentTarget as Element)"
              @click="showDialogMatches(h.hash)"
            >
              {{ $formatMessage(matchesMsg, { count: h.matchCount }) }}
            </v-btn>

            <div class="text-caption text-medium-emphasis">
              <span>{{ h.size != null ? getFileSize(h.size) : $formatMessage(unknownSizeMsg) }}</span>
              <span v-if="h.size != null">
                · {{ $formatMessage(saveMsg, { size: getFileSize(h.size * h.matchCount) }) }}
              </span>
            </div>

            <div class="mt-auto pt-1">
              <v-btn-toggle
                :model-value="getPageHashAction(h)"
                variant="outlined"
                divided
                rounded="lg"
                class="w-100"
                :disabled="!!getPageHashAction(h)"
                @update:model-value="(v: PageHashAction) => updateHashAction(h, v)"
              >
                <v-btn
                  v-ktooltip:bottom="intl.formatMessage(pageHashActionMessages['DELETE_AUTO'])"
                  class="flex-grow-1 px-0"
                  icon="i-mdi:robot"
                  value="DELETE_AUTO"
                  color="success"
                  @click.stop
                />
                <v-btn
                  v-ktooltip:bottom="intl.formatMessage(pageHashActionMessages['DELETE_MANUAL'])"
                  class="flex-grow-1 px-0"
                  icon="i-mdi:hand-back-right"
                  value="DELETE_MANUAL"
                  color="warning"
                  @click.stop
                />
                <v-btn
                  v-ktooltip:bottom="intl.formatMessage(pageHashActionMessages['IGNORE'])"
                  class="flex-grow-1 px-0"
                  icon="i-mdi:cancel"
                  value="IGNORE"
                  @click.stop
                />
              </v-btn-toggle>
            </div>
          </div>
        </v-card>
      </div>

      <div class="d-flex align-center justify-center flex-wrap ga-2 mt-4">
        <v-btn
          size="small"
          variant="text"
          :disabled="selectablePageItems.length === 0"
          @click="selectAllOnPage"
        >
          {{ $formatMessage(selectAllMsg) }}
        </v-btn>
        <v-pagination
          v-if="pageCount > 1"
          v-model="page"
          :length="pageCount"
          :total-visible="display.xs.value ? 3 : 7"
        />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { defineMessage, useIntl } from 'vue-intl'
import { useAppStore } from '@/stores/app'
import { useQuery, useQueryCache } from '@pinia/colada'
import { pageHashesUnknownQuery, QUERY_KEYS_PAGE_HASHES } from '@/colada/page-hashes'
import { getFileSize } from '@/functions/filesize'
import { pageHashUnknownThumbnailUrl } from '@/api/images'
import { storeToRefs } from 'pinia'
import { useDialogsStore } from '@/stores/dialogs'
import { useDisplay } from 'vuetify'
import { VImg } from 'vuetify/components'
import MatchTable from '@/components/pageHash/MatchTable.vue'
import PosterSizeSlider from '@/components/PosterSizeSlider.vue'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'
import { type PageHashAction, pageHashActionMessages } from '@/types/PageHashAction'
import { useMessagesStore } from '@/stores/messages'
import { commonMessages } from '@/utils/i18n/common-messages'
import { komgaCreateOrUpdateKnownPageHash, type PageHashUnknownDto } from '@/generated/openapi'

const intl = useIntl()
const display = useDisplay()
const messagesStore = useMessagesStore()
const queryCache = useQueryCache()
const { simple: dialogSimple } = storeToRefs(useDialogsStore())
const appStore = useAppStore()

const cardWidth = computed(() => (display.smAndUp.value ? appStore.gridCardWidth : 150))

//region Data loading
const page = ref(1)
const sortKey = ref<'matchCount' | 'size' | 'totalSize'>('matchCount')
const sortOrder = ref<'asc' | 'desc'>('desc')

const { data, isLoading, error } = useQuery(() =>
  pageHashesUnknownQuery({
    page: page.value - 1,
    size: appStore.dataTablePageSize,
    sort: [`${sortKey.value},${sortOrder.value}`],
  }),
)

const pageCount = computed(() => data.value?.totalPages ?? 1)

const sortOptions = [
  {
    key: 'matchCount' as const,
    title: defineMessage({
      description: 'Unknown Duplicate Page sort: matches',
      defaultMessage: 'Most matches',
      id: 'fork.pageHash.sort.matchCount',
    }),
  },
  {
    key: 'totalSize' as const,
    title: defineMessage({
      description: 'Unknown Duplicate Page sort: potential space saved',
      defaultMessage: 'Most space saved',
      id: 'fork.pageHash.sort.totalSize',
    }),
  },
  {
    key: 'size' as const,
    title: defineMessage({
      description: 'Unknown Duplicate Page sort: page size',
      defaultMessage: 'Largest page',
      id: 'fork.pageHash.sort.size',
    }),
  },
] as const

const currentSortOption = computed(
  () => sortOptions.find((it) => it.key === sortKey.value) ?? sortOptions[0],
)

function setSort(key: 'matchCount' | 'size' | 'totalSize') {
  sortKey.value = key
  page.value = 1
}

watch([sortKey, () => appStore.dataTablePageSize], () => (page.value = 1))
//endregion

//region Selection
const selectedHashes = ref<PageHashUnknownDto[]>([])

const selectablePageItems = computed(() => (data.value?.content ?? []).filter((it) => canSelect(it)))

function canSelect(item: PageHashUnknownDto): boolean {
  return !getPageHashAction(item)
}

function isSelected(item: PageHashUnknownDto): boolean {
  return selectedHashes.value.some((it) => it.hash === item.hash)
}

function toggleSelect(item: PageHashUnknownDto) {
  const index = selectedHashes.value.findIndex((it) => it.hash === item.hash)
  if (index >= 0) selectedHashes.value.splice(index, 1)
  else selectedHashes.value.push(item)
}

function selectAllOnPage() {
  selectedHashes.value = [...selectablePageItems.value]
}

function clearSelection() {
  selectedHashes.value = []
}
//endregion

//region Dialogs
function showDialogImage(hash: string) {
  dialogSimple.value.dialogProps = {
    fullscreen: display.xs.value,
    scrollable: true,
  }
  dialogSimple.value.slot = {
    component: markRaw(VImg),
    props: {
      src: pageHashUnknownThumbnailUrl(hash),
      contain: true,
      style: 'cursor: zoom-out;',
    },
    handlers: {
      click: () => {
        dialogSimple.value.dialogProps.shown = false
      },
    },
  }
}

function showDialogMatches(hash: string) {
  dialogSimple.value.dialogProps = {
    fullscreen: display.xs.value,
    scrollable: true,
  }
  dialogSimple.value.slot = {
    component: markRaw(MatchTable),
    props: {
      modelValue: hash,
    },
  }
}
//endregion

//region Update action
const updateRequests = ref<Record<string, PageHashAction>>({})

function getPageHashAction(pageHash: PageHashUnknownDto): PageHashAction | undefined {
  return updateRequests.value[pageHash.hash]
}

function actionColor(action: PageHashAction | undefined): string {
  switch (action) {
    case 'DELETE_AUTO':
      return 'success'
    case 'DELETE_MANUAL':
      return 'warning'
    default:
      return 'grey'
  }
}

async function updateHashAction(
  pageHash: PageHashUnknownDto,
  newAction: PageHashAction,
  invalidateCache: boolean = true,
) {
  updateRequests.value[pageHash.hash] = newAction
  try {
    await komgaCreateOrUpdateKnownPageHash({
      body: {
        ...pageHash,
        action: newAction,
      },
    })
    const index = selectedHashes.value.findIndex((it) => it.hash === pageHash.hash)
    if (index >= 0) selectedHashes.value.splice(index, 1)
    if (invalidateCache)
      void queryCache.invalidateQueries({ key: QUERY_KEYS_PAGE_HASHES.unknown() })
  } catch (e) {
    messagesStore.messages.push((e as Error)?.message ?? intl.formatMessage(commonMessages.networkError))
  }
}

async function updateHashActions(pageHashes: PageHashUnknownDto[], newAction: PageHashAction) {
  await Promise.allSettled(pageHashes.map((it) => updateHashAction(it, newAction, false)))
  void queryCache.invalidateQueries({ key: QUERY_KEYS_PAGE_HASHES.unknown() })
}

const actionOptions = [
  {
    title: intl.formatMessage(pageHashActionMessages['DELETE_AUTO']),
    value: 'DELETE_AUTO' as PageHashAction,
    icon: 'i-mdi:robot',
  },
  {
    title: intl.formatMessage(pageHashActionMessages['DELETE_MANUAL']),
    value: 'DELETE_MANUAL' as PageHashAction,
    icon: 'i-mdi:hand-back-right',
  },
  {
    title: intl.formatMessage(pageHashActionMessages['IGNORE']),
    value: 'IGNORE' as PageHashAction,
    icon: 'i-mdi:cancel',
  },
] as const
//endregion

//region Messages
const titleMsg = defineMessage({
  description: 'Unknown Duplicate Page Table global header',
  defaultMessage: 'Unknown Duplicates',
  id: 'XuqK4C',
})
const markAsMsg = defineMessage({
  description: 'Unknown Duplicate Page: selection action button',
  defaultMessage: 'Mark as',
  id: 'lFTdQ+',
})
const noDataMsg = defineMessage({
  description: 'Unknown Duplicate Page Table: shown when table has no data',
  defaultMessage: 'No data found',
  id: 'hPo41m',
})
const altMsg = defineMessage({
  description: 'Unknown Duplicate Page Table: alt description for thumbnail',
  defaultMessage: 'Duplicate page',
  id: 'IXhDH6',
})
const matchesMsg = defineMessage({
  description: 'Unknown Duplicate Page: number of matching books',
  defaultMessage: '{count, plural, one {# book} other {# books}}',
  id: 'fork.pageHash.matchesCount',
})
const selectedCountMsg = defineMessage({
  description: 'Unknown Duplicate Page: number of selected items',
  defaultMessage: '{count} selected',
  id: 'fork.pageHash.selectedCount',
})
const selectAllMsg = defineMessage({
  description: 'Unknown Duplicate Page: select all on page',
  defaultMessage: 'Select all on page',
  id: 'fork.pageHash.selectAll',
})
const clearSelectionMsg = defineMessage({
  description: 'Unknown Duplicate Page: clear selection',
  defaultMessage: 'Clear selection',
  id: 'fork.pageHash.clearSelection',
})
const unknownSizeMsg = defineMessage({
  description: 'Unknown Duplicate Page: unknown page size',
  defaultMessage: 'Size unknown',
  id: 'fork.pageHash.unknownSize',
})
const saveMsg = defineMessage({
  description: 'Unknown Duplicate Page: potential space saved by deleting',
  defaultMessage: '~{size} to save',
  id: 'fork.pageHash.spaceSave',
})
//endregion
</script>

<style scoped>
.page-hash-grid {
  display: grid;
  gap: 12px;
}
.cover-wrap {
  position: relative;
  width: 100%;
  aspect-ratio: 2 / 3;
  overflow: hidden;
}
.cover-img {
  width: 100%;
  height: 100%;
}
.select-overlay {
  position: absolute;
  top: 4px;
  left: 4px;
  background: rgba(var(--v-theme-surface), 0.7);
  border-radius: 4px;
}
</style>
