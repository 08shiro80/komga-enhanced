<template>
  <v-container fluid>
    <div class="d-flex align-center mb-3">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Oversized pages view: title',
            defaultMessage: 'Oversized pages',
            id: 'fork/oversized/title',
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

    <v-alert
      type="info"
      variant="tonal"
      density="compact"
      class="mb-4 text-body-2"
    >
      <template v-if="mode === 'wide'">
        {{
          $formatMessage({
            description: 'Oversized pages view: wide info',
            defaultMessage:
              'Scan for wide pages (double pages combined in one image) and split them horizontally into single pages. Uses the width ÷ height ratio, so it works at any resolution.',
            id: 'fork/oversized/info/wide',
          })
        }}
      </template>
      <template v-else>
        {{
          $formatMessage({
            description: 'Oversized pages view: tall info',
            defaultMessage:
              'Scan for tall pages (webtoon strips, long scrolling pages) and split them into multiple readable pages. Uses the height ÷ width ratio, so it works at any resolution.',
            id: 'fork/oversized/info/tall',
          })
        }}
      </template>
    </v-alert>

    <v-card
      variant="outlined"
      class="mb-4"
    >
      <v-card-text>
        <v-row dense>
          <v-col
            cols="12"
            md="3"
          >
            <v-select
              v-model="preset"
              :items="presetItems"
              :label="$formatMessage(presetLabel)"
              variant="outlined"
              density="compact"
              hide-details
            />
          </v-col>
          <v-col
            cols="6"
            md="2"
          >
            <v-select
              v-model="mode"
              :items="modeItems"
              :label="$formatMessage(modeLabel)"
              variant="outlined"
              density="compact"
              hide-details
              @update:model-value="preset = 'custom'"
            />
          </v-col>
          <v-col
            cols="6"
            md="2"
          >
            <v-text-field
              v-model.number="detectRatio"
              type="number"
              step="0.1"
              :label="$formatMessage(detectLabel)"
              variant="outlined"
              density="compact"
              hide-details
              @update:model-value="preset = 'custom'"
            />
          </v-col>
          <v-col
            cols="6"
            md="2"
          >
            <v-text-field
              v-model.number="splitRatio"
              type="number"
              step="0.1"
              :label="$formatMessage(splitLabel)"
              variant="outlined"
              density="compact"
              hide-details
              @update:model-value="preset = 'custom'"
            />
          </v-col>
          <v-col
            cols="6"
            md="3"
            class="d-flex align-center ga-2"
          >
            <v-switch
              v-model="includeIgnored"
              :label="$formatMessage(includeIgnoredLabel)"
              density="compact"
              hide-details
              color="primary"
              @update:model-value="apply()"
            />
          </v-col>
        </v-row>
        <div class="d-flex align-center ga-2 mt-2 flex-wrap">
          <v-text-field
            v-model="search"
            :label="$formatMessage(searchLabel)"
            variant="outlined"
            density="compact"
            hide-details
            clearable
            prepend-inner-icon="i-mdi:magnify"
            class="flex-grow-1"
            style="min-width: 220px"
            @keyup.enter="apply()"
            @click:clear="apply()"
          />
          <v-btn
            color="primary"
            :loading="isPending"
            @click="apply()"
          >
            {{ $formatMessage(applyLabel) }}
          </v-btn>
        </div>

        <div class="d-flex align-center ga-2 mt-3 flex-wrap">
          <v-btn
            color="warning"
            prepend-icon="i-mdi:scissors-cutting"
            :disabled="selectedCount === 0 || splittingSelected"
            :loading="splittingSelected"
            @click="splitSelected()"
          >
            {{ $formatMessage(splitSelectedLabel) }}
            <span v-if="selectedCount">&nbsp;({{ selectedCount }})</span>
          </v-btn>
          <v-btn
            prepend-icon="i-mdi:eye-off"
            variant="tonal"
            :disabled="selectedCount === 0"
            @click="ignoreSelected()"
          >
            {{ $formatMessage(ignoreSelectedLabel) }}
            <span v-if="selectedCount">&nbsp;({{ selectedCount }})</span>
          </v-btn>
          <v-btn
            color="error"
            variant="tonal"
            prepend-icon="i-mdi:delete"
            :disabled="selectedCount === 0"
            @click="confirmDelete(selectedItems)"
          >
            {{ $formatMessage(deleteSelectedLabel) }}
            <span v-if="selectedCount">&nbsp;({{ selectedCount }})</span>
          </v-btn>
          <v-btn
            variant="text"
            size="small"
            :disabled="items.length === 0"
            @click="toggleSelectAll()"
          >
            {{ allSelected ? $formatMessage(deselectAllLabel) : $formatMessage(selectAllLabel) }}
          </v-btn>
          <v-spacer />
          <v-btn
            color="error"
            variant="text"
            prepend-icon="i-mdi:content-cut"
            :disabled="items.length === 0 || splitAllRunning"
            :loading="splitAllRunning"
            @click="confirmSplitAll = true"
          >
            {{ $formatMessage(splitAllLabel) }}
          </v-btn>
        </div>

        <div class="d-flex align-center ga-2 mt-3 flex-wrap">
          <span class="text-caption text-medium-emphasis">{{ $formatMessage(sortLabel) }}</span>
          <v-btn-toggle
            v-model="sortKey"
            mandatory
            density="compact"
            variant="outlined"
            divided
            @update:model-value="apply()"
          >
            <v-btn
              v-for="s in sortKeys"
              :key="s.value"
              :value="s.value"
              size="small"
            >
              {{ $formatMessage(s.label) }}
            </v-btn>
          </v-btn-toggle>
          <v-btn
            :icon="sortDesc ? 'i-mdi:sort-descending' : 'i-mdi:sort-ascending'"
            variant="text"
            size="small"
            :aria-label="$formatMessage(sortDirLabel)"
            @click="toggleSortDir()"
          />
          <v-spacer />
          <span class="text-caption text-medium-emphasis">{{ $formatMessage(perPageLabel) }}</span>
          <v-select
            v-model="size"
            :items="[20, 50, 100, 150]"
            variant="outlined"
            density="compact"
            hide-details
            style="max-width: 100px"
            @update:model-value="apply()"
          />
        </div>
      </v-card-text>
    </v-card>

    <div
      v-if="splitAllRunning"
      class="mb-4"
    >
      <v-alert
        type="warning"
        variant="tonal"
        density="compact"
        :icon="false"
      >
        <div class="d-flex align-center ga-2">
          <v-progress-circular
            indeterminate
            size="20"
            width="2"
          />
          {{ $formatMessage(splitAllRunningLabel) }}
        </div>
      </v-alert>
    </div>

    <EmptyStateNetworkError v-if="error" />

    <v-alert
      v-else-if="items.length === 0 && !isPending"
      type="success"
      variant="tonal"
    >
      {{
        $formatMessage({
          description: 'Oversized pages view: none found',
          defaultMessage: 'No oversized pages match the current filter.',
          id: 'fork/oversized/empty',
        })
      }}
    </v-alert>

    <template v-else>
      <v-row>
        <v-col
          v-for="(p, idx) in items"
          :key="p.rowKey"
          cols="12"
          sm="6"
          lg="4"
        >
          <v-card
            variant="outlined"
            :style="isSelected(p) ? 'border-color: rgb(var(--v-theme-primary)); border-width: 2px' : ''"
            @click="onCardClick(p, idx, $event)"
          >
            <div class="d-flex">
              <div
                class="position-relative pa-2"
                style="flex: 0 0 auto"
              >
                <v-checkbox
                  :model-value="isSelected(p)"
                  hide-details
                  density="compact"
                  color="primary"
                  class="position-absolute"
                  style="top: 4px; left: 4px; z-index: 2"
                  @click.stop="onCardClick(p, idx, $event)"
                />
                <v-img
                  :src="bookPageThumbnailUrl(p.bookId, p.pageNumber)"
                  :width="140"
                  :height="200"
                  cover
                  class="bg-grey-darken-4 rounded cursor-pointer"
                  referrerpolicy="no-referrer"
                  @click.stop="openPreview(p)"
                >
                  <template #placeholder>
                    <div class="d-flex align-center justify-center fill-height">
                      <v-progress-circular
                        indeterminate
                        size="24"
                      />
                    </div>
                  </template>
                </v-img>
              </div>
              <div
                class="pa-3 flex-grow-1"
                style="min-width: 0"
              >
                <div class="text-caption text-medium-emphasis">
                  {{ $formatMessage(colSeries) }}
                </div>
                <RouterLink
                  :to="{ name: '/series/[id]', params: { id: p.seriesId } }"
                  class="text-body-2 text-decoration-none d-block text-truncate"
                  @click.stop
                >
                  {{ p.seriesTitle }}
                </RouterLink>
                <div class="text-caption text-medium-emphasis mt-1">
                  {{ $formatMessage(colBook) }}
                </div>
                <RouterLink
                  :to="{ name: '/book/[id]', params: { id: p.bookId } }"
                  class="text-body-2 text-decoration-none d-block text-truncate"
                  @click.stop
                >
                  {{ p.bookName }}
                </RouterLink>

                <div class="d-flex flex-wrap ga-x-4 mt-2 text-body-2">
                  <div>
                    <div class="text-caption text-medium-emphasis">
                      {{ $formatMessage(colPage) }}
                    </div>
                    {{ p.pageNumber }}
                  </div>
                  <div>
                    <div class="text-caption text-medium-emphasis">
                      {{ $formatMessage(colRatio) }}
                    </div>
                    {{ p.ratio.toFixed(2) }}:1
                  </div>
                  <div>
                    <div class="text-caption text-medium-emphasis">
                      {{ $formatMessage(colDimensions) }}
                    </div>
                    {{ p.width }}×{{ p.height }}
                  </div>
                  <div>
                    <div class="text-caption text-medium-emphasis">
                      {{ $formatMessage(colSize) }}
                    </div>
                    {{ formatBytes(p.fileSize) }}
                  </div>
                  <div>
                    <div class="text-caption text-medium-emphasis">
                      {{ $formatMessage(colSplitInto) }}
                    </div>
                    {{ splitPreviewParts(p) }}
                  </div>
                </div>
              </div>
            </div>
            <v-card-actions class="pt-0">
              <v-btn
                icon="i-mdi:image-search"
                variant="text"
                size="small"
                :aria-label="$formatMessage(previewActionLabel)"
                @click.stop="openPreview(p)"
              />
              <v-btn
                icon="i-mdi:scissors-cutting"
                variant="text"
                size="small"
                color="warning"
                :aria-label="$formatMessage(splitActionLabel)"
                @click.stop="splitOne(p)"
              />
              <v-btn
                icon="i-mdi:eye-off"
                variant="text"
                size="small"
                :aria-label="$formatMessage(ignoreActionLabel)"
                @click.stop="ignoreOne(p)"
              />
              <v-btn
                icon="i-mdi:delete"
                variant="text"
                size="small"
                color="error"
                :aria-label="$formatMessage(deleteActionLabel)"
                @click.stop="confirmDelete([p])"
              />
            </v-card-actions>
          </v-card>
        </v-col>
      </v-row>

      <v-pagination
        v-if="(data?.totalPages ?? 1) > 1"
        v-model="page"
        :length="data?.totalPages ?? 1"
        :total-visible="7"
        class="mt-4"
      />
    </template>

    <!-- Preview dialog -->
    <v-dialog
      v-model="showPreview"
      max-width="90vw"
    >
      <v-card v-if="previewItem">
        <v-card-title class="d-flex align-center">
          <span class="text-truncate">{{ previewItem.bookName }} — {{ $formatMessage(colPage) }} {{ previewItem.pageNumber }}</span>
          <v-spacer />
          <span class="text-caption text-no-wrap">{{ previewItem.width }}×{{ previewItem.height }} ({{ previewItem.ratio.toFixed(2) }}:1)</span>
        </v-card-title>
        <v-card-text class="text-center">
          <img
            :src="bookPageUrl(previewItem.bookId, previewItem.pageNumber)"
            referrerpolicy="no-referrer"
            style="max-width: 100%; max-height: 80vh; object-fit: contain"
            alt=""
          >
        </v-card-text>
        <v-card-actions>
          <v-btn
            prepend-icon="i-mdi:eye-off"
            @click="ignoreFromPreview()"
          >
            {{ $formatMessage(ignoreActionLabel) }}
          </v-btn>
          <v-btn
            color="error"
            prepend-icon="i-mdi:delete"
            @click="deleteFromPreview()"
          >
            {{ $formatMessage(deleteActionLabel) }}
          </v-btn>
          <v-spacer />
          <v-btn @click="showPreview = false">
            {{ $formatMessage(closeLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Split-all confirm -->
    <v-dialog
      v-model="confirmSplitAll"
      max-width="480"
    >
      <v-card>
        <v-card-title>
          {{
            $formatMessage({
              description: 'Oversized pages view: split all dialog title',
              defaultMessage: 'Split all matching pages?',
              id: 'fork/oversized/splitAll/title',
            })
          }}
        </v-card-title>
        <v-card-text>
          {{
            $formatMessage({
              description: 'Oversized pages view: split all dialog body',
              defaultMessage:
                'This rewrites the affected CBZ files (detect ratio {detect}:1, split ratio {split}:1). It runs in the background and cannot be undone.',
              id: 'fork/oversized/splitAll/body',
            }, { detect: detectRatio, split: splitRatio })
          }}
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="confirmSplitAll = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="warning"
            @click="splitAll()"
          >
            {{
              $formatMessage({
                description: 'Oversized pages view: confirm split all',
                defaultMessage: 'Split all',
                id: 'fork/oversized/splitAll/confirm',
              })
            }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Delete confirm -->
    <v-dialog
      v-model="showDelete"
      max-width="540"
    >
      <v-card>
        <v-card-title class="text-error">
          {{ $formatMessage(deleteTitleLabel, { count: deleteTargets.length }) }}
        </v-card-title>
        <v-card-text>
          <p>
            {{
              $formatMessage({
                description: 'Oversized pages view: delete dialog body',
                defaultMessage:
                  'The selected pages are removed from their book archives. Page numbers of the following pages shift. This modifies your book files and cannot be undone.',
                id: 'fork/oversized/delete/body',
              })
            }}
          </p>
          <ul
            v-if="deleteTargets.length <= 8"
            class="text-caption"
          >
            <li
              v-for="t in deleteTargets"
              :key="t.rowKey"
            >
              {{ t.bookName }} — {{ $formatMessage(colPage) }} {{ t.pageNumber }}
            </li>
          </ul>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="showDelete = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="error"
            :loading="deleting"
            @click="executeDelete()"
          >
            {{ $formatMessage(deleteActionLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Split results -->
    <v-dialog
      v-model="showResults"
      max-width="700"
    >
      <v-card>
        <v-card-title>
          {{
            $formatMessage({
              description: 'Oversized pages view: results dialog title',
              defaultMessage: 'Split results',
              id: 'fork/oversized/results/title',
            })
          }}
        </v-card-title>
        <v-card-text>
          <v-table density="compact">
            <thead>
              <tr>
                <th>{{ $formatMessage(colBook) }}</th>
                <th class="text-center">
                  {{ $formatMessage(colStatus) }}
                </th>
                <th class="text-end">
                  {{ $formatMessage(colPagesSplit) }}
                </th>
                <th class="text-end">
                  {{ $formatMessage(colNewPages) }}
                </th>
                <th>{{ $formatMessage(colMessage) }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="r in splitResults"
                :key="r.bookId"
              >
                <td>{{ r.bookName }}</td>
                <td class="text-center">
                  <v-icon
                    :icon="r.success ? 'i-mdi:check-circle' : 'i-mdi:alert-circle'"
                    :color="r.success ? 'success' : 'error'"
                    size="small"
                  />
                </td>
                <td class="text-end">
                  {{ r.pagesSplit }}
                </td>
                <td class="text-end">
                  {{ r.newPagesCreated }}
                </td>
                <td class="text-caption">
                  {{ r.message }}
                </td>
              </tr>
            </tbody>
          </v-table>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="showResults = false">
            {{ $formatMessage(closeLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useLocalStorage } from '@vueuse/core'
import {
  useDeleteOversizedPage,
  useDeleteOversizedPagesBatch,
  useIgnoreOversizedPage,
  useIgnoreOversizedPagesBatch,
  useOversizedPages,
  useSplitAll,
  useSplitAllStatus,
  useSplitPages,
  type OversizedQuery,
} from '@/colada/oversized-pages'
import { bookPageThumbnailUrl, bookPageUrl } from '@/api/images'
import { useMessagesStore } from '@/stores/messages'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'
import type { OversizedPageDto, SplitResultDto } from '@/generated/openapi'

type OversizedRow = OversizedPageDto & { rowKey: string }

const messagesStore = useMessagesStore()

const PRESETS = [
  { key: 'webtoon', label: 'Webtoon / Long strip', mode: 'tall', detect: 3, split: 1.5 },
  { key: 'moderate', label: 'Moderate', mode: 'tall', detect: 2, split: 1.5 },
  { key: 'aggressive', label: 'Aggressive', mode: 'tall', detect: 1.5, split: 1.2 },
  { key: 'double', label: 'Double page', mode: 'wide', detect: 1.3, split: 1.0 },
  { key: 'custom', label: 'Custom', mode: 'tall', detect: 0, split: 0 },
]

const preset = ref('webtoon')
const mode = ref('tall')
const detectRatio = ref(3)
const splitRatio = ref(1.5)
const includeIgnored = ref(false)
const search = ref('')
const page = ref(1)
const size = useLocalStorage('fork.oversized.pageSize', 20)
const sortKey = ref('ratio')
const sortDesc = ref(true)

const selected = ref<Set<string>>(new Set())
let lastIndex = -1

watch(preset, (key) => {
  const p = PRESETS.find((x) => x.key === key)
  if (p && p.key !== 'custom') {
    mode.value = p.mode
    detectRatio.value = p.detect
    splitRatio.value = p.split
  }
})

function buildParams(): OversizedQuery {
  return {
    mode: mode.value,
    minRatio: detectRatio.value,
    includeIgnored: includeIgnored.value,
    search: search.value || undefined,
    page: page.value - 1,
    size: size.value,
    sort: [`${sortKey.value},${sortDesc.value ? 'desc' : 'asc'}`],
  }
}

const params = ref<OversizedQuery>(buildParams())

const { data, error, isPending, refetch } = useOversizedPages(params)

const items = computed<OversizedRow[]>(() =>
  (data.value?.content ?? []).map((p) => ({ ...p, rowKey: `${p.bookId}_${p.pageNumber}` })),
)

const { mutateAsync: splitPages } = useSplitPages()
const { mutateAsync: deletePage } = useDeleteOversizedPage()
const { mutateAsync: deletePagesBatch } = useDeleteOversizedPagesBatch()
const { mutateAsync: ignorePage } = useIgnoreOversizedPage()
const { mutateAsync: ignorePagesBatch } = useIgnoreOversizedPagesBatch()
const { mutateAsync: splitAllPages } = useSplitAll()
const { data: splitAllStatus, refetch: refetchSplitAll } = useSplitAllStatus()

const splitAllRunning = ref(false)
const splittingSelected = ref(false)
const confirmSplitAll = ref(false)
const showPreview = ref(false)
const previewItem = ref<OversizedRow | null>(null)
const showDelete = ref(false)
const deleteTargets = ref<OversizedRow[]>([])
const deleting = ref(false)
const showResults = ref(false)
const splitResults = ref<SplitResultDto[]>([])

const selectedCount = computed(() => selected.value.size)
const selectedItems = computed(() => items.value.filter((p) => selected.value.has(p.rowKey)))
const allSelected = computed(
  () => items.value.length > 0 && items.value.every((p) => selected.value.has(p.rowKey)),
)

function apply() {
  page.value = 1
  selected.value = new Set()
  params.value = buildParams()
}

watch(page, () => {
  params.value = buildParams()
})

function toggleSortDir() {
  sortDesc.value = !sortDesc.value
  apply()
}

function isSelected(p: OversizedRow): boolean {
  return selected.value.has(p.rowKey)
}

function onCardClick(p: OversizedRow, idx: number, event: MouseEvent) {
  const next = new Set(selected.value)
  if (event.shiftKey && lastIndex >= 0) {
    const start = Math.min(lastIndex, idx)
    const end = Math.max(lastIndex, idx)
    const shouldSelect = !next.has(p.rowKey)
    for (let i = start; i <= end; i++) {
      const key = items.value[i]?.rowKey
      if (!key) continue
      if (shouldSelect) next.add(key)
      else next.delete(key)
    }
  } else if (next.has(p.rowKey)) {
    next.delete(p.rowKey)
  } else {
    next.add(p.rowKey)
  }
  selected.value = next
  lastIndex = idx
}

function toggleSelectAll() {
  if (allSelected.value) selected.value = new Set()
  else selected.value = new Set(items.value.map((p) => p.rowKey))
}

function splitPreviewParts(p: OversizedRow): number {
  if (splitRatio.value <= 0) return 1
  if (mode.value === 'wide') return p.width > p.height * splitRatio.value ? 2 : 1
  return Math.max(1, Math.ceil(p.height / (p.width * splitRatio.value)))
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

function openPreview(p: OversizedRow) {
  previewItem.value = p
  showPreview.value = true
}

async function splitOne(p: OversizedRow) {
  try {
    await splitPages({
      bookId: p.bookId,
      pageNumbers: [p.pageNumber],
      maxRatio: splitRatio.value,
      mode: mode.value,
    })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function splitSelected() {
  if (selectedCount.value === 0) return
  splittingSelected.value = true
  splitResults.value = []
  const byBook = new Map<string, number[]>()
  for (const p of selectedItems.value) {
    const arr = byBook.get(p.bookId) ?? []
    arr.push(p.pageNumber)
    byBook.set(p.bookId, arr)
  }
  for (const [bookId, pageNumbers] of byBook) {
    try {
      const result = await splitPages({
        bookId,
        pageNumbers,
        maxRatio: splitRatio.value,
        mode: mode.value,
      })
      splitResults.value.push(result)
    } catch (e) {
      splitResults.value.push({
        bookId,
        bookName: 'Unknown',
        message: (e as Error).message,
        newPagesCreated: 0,
        pagesAnalyzed: 0,
        pagesSplit: 0,
        success: false,
      })
    }
  }
  splittingSelected.value = false
  selected.value = new Set()
  showResults.value = true
}

async function ignoreOne(p: OversizedRow) {
  try {
    await ignorePage({ bookId: p.bookId, pageNumber: p.pageNumber, mode: mode.value })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function ignoreSelected() {
  if (selectedCount.value === 0) return
  try {
    await ignorePagesBatch({
      mode: mode.value,
      pages: selectedItems.value.map((p) => ({ bookId: p.bookId, pageNumber: p.pageNumber })),
    })
    selected.value = new Set()
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function ignoreFromPreview() {
  if (!previewItem.value) return
  const item = previewItem.value
  showPreview.value = false
  await ignoreOne(item)
}

function confirmDelete(targets: OversizedRow[]) {
  if (targets.length === 0) return
  deleteTargets.value = [...targets]
  showDelete.value = true
}

function deleteFromPreview() {
  if (!previewItem.value) return
  const item = previewItem.value
  showPreview.value = false
  confirmDelete([item])
}

async function executeDelete() {
  if (deleteTargets.value.length === 0) return
  deleting.value = true
  try {
    if (deleteTargets.value.length === 1) {
      const t = deleteTargets.value[0]
      if (t) await deletePage({ bookId: t.bookId, pageNumber: t.pageNumber, mode: mode.value })
    } else {
      await deletePagesBatch({
        mode: mode.value,
        pages: deleteTargets.value.map((t) => ({ bookId: t.bookId, pageNumber: t.pageNumber })),
      })
    }
    selected.value = new Set()
    deleteTargets.value = []
    showDelete.value = false
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    deleting.value = false
  }
}

async function splitAll() {
  confirmSplitAll.value = false
  try {
    await splitAllPages({
      mode: mode.value,
      minRatio: detectRatio.value,
      maxRatio: splitRatio.value,
      includeIgnored: includeIgnored.value,
      search: search.value || undefined,
    })
    splitAllRunning.value = true
    messagesStore.messages.push({ message: splitAllStartedMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
    void refetchSplitAll()
  }
}

let pollHandle: ReturnType<typeof setInterval> | null = null

function startPolling() {
  if (pollHandle) return
  pollHandle = setInterval(() => void refetchSplitAll(), 3000)
}

function stopPolling() {
  if (pollHandle) {
    clearInterval(pollHandle)
    pollHandle = null
  }
}

watch(
  () => (splitAllStatus.value as { inProgress?: boolean } | undefined)?.inProgress,
  (inProgress) => {
    if (inProgress) {
      splitAllRunning.value = true
      startPolling()
    } else if (splitAllRunning.value) {
      splitAllRunning.value = false
      stopPolling()
      void refetch()
    }
  },
)

watch(splitAllRunning, (running) => {
  if (running) startPolling()
})

onMounted(() => void refetchSplitAll())
onBeforeUnmount(() => stopPolling())

const presetItems = PRESETS.map((p) => ({ title: p.label, value: p.key }))
const modeItems = [
  { title: 'Tall', value: 'tall' },
  { title: 'Wide', value: 'wide' },
]
const sortKeys = [
  { value: 'ratio', label: defineMessage({ description: 'Oversized: sort ratio', defaultMessage: 'Ratio', id: 'fork/oversized/sort/ratio' }) },
  { value: 'fileSize', label: defineMessage({ description: 'Oversized: sort size', defaultMessage: 'Size', id: 'fork/oversized/sort/size' }) },
  { value: 'seriesTitle', label: defineMessage({ description: 'Oversized: sort series', defaultMessage: 'Series', id: 'fork/oversized/sort/series' }) },
  { value: 'bookName', label: defineMessage({ description: 'Oversized: sort book', defaultMessage: 'Book', id: 'fork/oversized/sort/book' }) },
  { value: 'pageNumber', label: defineMessage({ description: 'Oversized: sort page', defaultMessage: 'Page', id: 'fork/oversized/sort/page' }) },
]

const refreshLabel = defineMessage({ description: 'Oversized: refresh', defaultMessage: 'Refresh', id: 'fork/oversized/refresh' })
const presetLabel = defineMessage({ description: 'Oversized: preset select', defaultMessage: 'Preset', id: 'fork/oversized/preset' })
const modeLabel = defineMessage({ description: 'Oversized: mode select', defaultMessage: 'Mode', id: 'fork/oversized/mode' })
const detectLabel = defineMessage({ description: 'Oversized: detect ratio', defaultMessage: 'Detect ratio', id: 'fork/oversized/detect' })
const splitLabel = defineMessage({ description: 'Oversized: split ratio', defaultMessage: 'Split ratio', id: 'fork/oversized/split' })
const includeIgnoredLabel = defineMessage({ description: 'Oversized: include ignored', defaultMessage: 'Show ignored', id: 'fork/oversized/includeIgnored' })
const searchLabel = defineMessage({ description: 'Oversized: search', defaultMessage: 'Filter by series or book', id: 'fork/oversized/search' })
const applyLabel = defineMessage({ description: 'Oversized: apply filter', defaultMessage: 'Search', id: 'fork/oversized/apply' })
const splitSelectedLabel = defineMessage({ description: 'Oversized: split selected', defaultMessage: 'Split selected', id: 'fork/oversized/splitSelected' })
const ignoreSelectedLabel = defineMessage({ description: 'Oversized: ignore selected', defaultMessage: 'Ignore selected', id: 'fork/oversized/ignoreSelected' })
const deleteSelectedLabel = defineMessage({ description: 'Oversized: delete selected', defaultMessage: 'Delete selected', id: 'fork/oversized/deleteSelected' })
const selectAllLabel = defineMessage({ description: 'Oversized: select all', defaultMessage: 'Select all', id: 'fork/oversized/selectAll' })
const deselectAllLabel = defineMessage({ description: 'Oversized: deselect all', defaultMessage: 'Deselect all', id: 'fork/oversized/deselectAll' })
const splitAllLabel = defineMessage({ description: 'Oversized: split all button', defaultMessage: 'Split all matching', id: 'fork/oversized/splitAll' })
const splitAllRunningLabel = defineMessage({ description: 'Oversized: split all running', defaultMessage: 'Split-all is running in the background…', id: 'fork/oversized/splitAll/running' })
const sortLabel = defineMessage({ description: 'Oversized: sort', defaultMessage: 'Sort:', id: 'fork/oversized/sortLabel' })
const sortDirLabel = defineMessage({ description: 'Oversized: sort direction', defaultMessage: 'Toggle sort direction', id: 'fork/oversized/sortDir' })
const perPageLabel = defineMessage({ description: 'Oversized: per page', defaultMessage: 'Per page:', id: 'fork/oversized/perPage' })
const colSeries = defineMessage({ description: 'Oversized: col series', defaultMessage: 'Series', id: 'fork/oversized/col/series' })
const colBook = defineMessage({ description: 'Oversized: col book', defaultMessage: 'Book', id: 'fork/oversized/col/book' })
const colPage = defineMessage({ description: 'Oversized: col page', defaultMessage: 'Page', id: 'fork/oversized/col/page' })
const colDimensions = defineMessage({ description: 'Oversized: col dimensions', defaultMessage: 'Size (px)', id: 'fork/oversized/col/dimensions' })
const colRatio = defineMessage({ description: 'Oversized: col ratio', defaultMessage: 'Ratio', id: 'fork/oversized/col/ratio' })
const colSize = defineMessage({ description: 'Oversized: col file size', defaultMessage: 'File size', id: 'fork/oversized/col/size' })
const colSplitInto = defineMessage({ description: 'Oversized: col split into', defaultMessage: 'Split into', id: 'fork/oversized/col/splitInto' })
const colStatus = defineMessage({ description: 'Oversized: col status', defaultMessage: 'Status', id: 'fork/oversized/col/status' })
const colPagesSplit = defineMessage({ description: 'Oversized: col pages split', defaultMessage: 'Pages split', id: 'fork/oversized/col/pagesSplit' })
const colNewPages = defineMessage({ description: 'Oversized: col new pages', defaultMessage: 'New pages', id: 'fork/oversized/col/newPages' })
const colMessage = defineMessage({ description: 'Oversized: col message', defaultMessage: 'Message', id: 'fork/oversized/col/message' })
const previewActionLabel = defineMessage({ description: 'Oversized: preview action', defaultMessage: 'Preview', id: 'fork/oversized/action/preview' })
const splitActionLabel = defineMessage({ description: 'Oversized: split action', defaultMessage: 'Split page', id: 'fork/oversized/action/split' })
const ignoreActionLabel = defineMessage({ description: 'Oversized: ignore action', defaultMessage: 'Ignore', id: 'fork/oversized/action/ignore' })
const deleteActionLabel = defineMessage({ description: 'Oversized: delete action', defaultMessage: 'Delete', id: 'fork/oversized/action/delete' })
const deleteTitleLabel = defineMessage({ description: 'Oversized: delete dialog title', defaultMessage: 'Delete {count, plural, one {# page} other {# pages}}?', id: 'fork/oversized/delete/title' })
const cancelLabel = defineMessage({ description: 'Oversized: cancel', defaultMessage: 'Cancel', id: 'fork/oversized/cancel' })
const closeLabel = defineMessage({ description: 'Oversized: close', defaultMessage: 'Close', id: 'fork/oversized/close' })
const splitAllStartedMessage = defineMessage({ description: 'Oversized: split all started', defaultMessage: 'Splitting started in the background', id: 'fork/oversized/splitAll/started' })
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
