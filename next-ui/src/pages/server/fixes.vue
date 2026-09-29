<template>
  <v-container fluid>
    <div class="d-flex align-center mb-1">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Fixes view: title',
            defaultMessage: 'Maintenance & fixes',
            id: 'fork/fixes/title',
          })
        }}
      </span>
    </div>
    <p class="text-body-2 text-medium-emphasis mb-4">
      {{
        $formatMessage({
          description: 'Fixes view: subtitle',
          defaultMessage: 'One-time maintenance actions. Long-running fixes keep running in the background — you can leave this page and come back for the result.',
          id: 'fork/fixes/subtitle',
        })
      }}
    </p>

    <v-row>
      <!-- Re-inject ComicInfo.xml -->
      <v-col
        cols="12"
        md="6"
      >
        <v-card
          variant="outlined"
          class="h-100"
        >
          <v-card-title class="d-flex align-center ga-2">
            <v-icon
              icon="i-mdi:file-document-refresh-outline"
              color="warning"
            />
            {{ $formatMessage(comicTitle) }}
          </v-card-title>
          <v-card-text>
            <p class="text-body-2 mb-3">
              {{ $formatMessage(comicDescription) }}
            </p>
            <v-select
              v-model="comicLibraryId"
              :items="libraryItems"
              :label="$formatMessage(libraryLabel)"
              variant="outlined"
              density="compact"
              hide-details
              class="mb-3"
            />
            <v-switch
              v-model="comicForce"
              :label="$formatMessage(comicForceLabel)"
              color="primary"
              density="compact"
              hide-details
            />

            <div
              v-if="comicRunning"
              class="d-flex align-center ga-2 mt-2 text-caption text-medium-emphasis"
            >
              <v-progress-circular
                indeterminate
                size="16"
                width="2"
              />
              {{ $formatMessage(runningBackgroundLabel) }}
              <template v-if="comicStatus && comicStatus.total > 0">
                — {{ comicStatus.processed }}/{{ comicStatus.total }}
              </template>
            </div>

            <v-alert
              v-if="comicResultVisible"
              :type="comicStatus?.errors?.length ? 'warning' : 'success'"
              variant="tonal"
              density="compact"
              class="mt-3 mb-0"
            >
              {{
                $formatMessage(comicResultLabel, {
                  repaired: comicStatus?.repaired ?? 0,
                  skipped: comicStatus?.skipped ?? 0,
                  processed: comicStatus?.processed ?? 0,
                  total: comicStatus?.total ?? 0,
                })
              }}
              <div
                v-for="(err, i) in comicStatus?.errors ?? []"
                :key="i"
                class="text-caption mt-1"
              >
                {{ err }}
              </div>
            </v-alert>
          </v-card-text>
          <v-card-actions>
            <v-btn
              color="warning"
              variant="tonal"
              prepend-icon="i-mdi:play"
              :loading="comicRunning"
              :disabled="!comicLibraryId || comicRunning"
              @click="runComicInfo()"
            >
              {{ $formatMessage(runLabel) }}
            </v-btn>
          </v-card-actions>
        </v-card>
      </v-col>

      <!-- Deleted-chapters scan -->
      <v-col
        cols="12"
        md="6"
      >
        <v-card
          variant="outlined"
          class="h-100"
        >
          <v-card-title class="d-flex align-center ga-2">
            <v-icon
              icon="i-mdi:database-search-outline"
              color="warning"
            />
            {{ $formatMessage(scanTitle) }}
          </v-card-title>
          <v-card-text>
            <p class="text-body-2 mb-3">
              {{ $formatMessage(scanDescription) }}
            </p>
            <v-select
              v-model="scanLibraryId"
              :items="libraryItems"
              :label="$formatMessage(libraryLabel)"
              variant="outlined"
              density="compact"
              hide-details
              class="mb-3"
            />
            <v-switch
              v-model="scanDryRun"
              :label="$formatMessage(scanDryRunLabel)"
              color="primary"
              density="compact"
              hide-details
            />

            <div
              v-if="scanRunning"
              class="d-flex align-center ga-2 mt-2 text-caption text-medium-emphasis"
            >
              <v-progress-circular
                indeterminate
                size="16"
                width="2"
              />
              {{ $formatMessage(runningBackgroundLabel) }}
            </div>

            <v-alert
              v-if="scanStatus?.error"
              type="error"
              variant="tonal"
              density="compact"
              class="mt-3 mb-0"
            >
              {{ scanStatus.error }}
            </v-alert>
            <v-alert
              v-else-if="scanStatus?.result"
              :type="scanStatus.result.entriesRemoved ? 'warning' : 'success'"
              variant="tonal"
              density="compact"
              class="mt-3 mb-0"
            >
              {{
                $formatMessage(scanResultLabel, {
                  dryRun: scanStatus.dryRun ? 1 : 0,
                  removed: scanStatus.result.entriesRemoved,
                  scanned: scanStatus.result.seriesScanned,
                  total: scanStatus.result.totalSeries,
                })
              }}
              <div
                v-for="(d, i) in scanStatus.result.details"
                :key="i"
                class="text-caption mt-1"
              >
                {{ d.seriesName }}: {{ d.removedCount }} / {{ d.cbzFileCount }}
              </div>
            </v-alert>
          </v-card-text>
          <v-card-actions>
            <v-btn
              color="warning"
              variant="tonal"
              prepend-icon="i-mdi:play"
              :loading="scanRunning"
              :disabled="!scanLibraryId || scanRunning"
              @click="runScan()"
            >
              {{ $formatMessage(runLabel) }}
            </v-btn>
          </v-card-actions>
        </v-card>
      </v-col>
    </v-row>

    <!-- Schema-driven fixes (FixRegistry) -->
    <v-divider class="my-6" />
    <div class="text-subtitle-1 mb-3">
      {{
        $formatMessage({
          description: 'Fixes view: automated section',
          defaultMessage: 'Automated fixes',
          id: 'fork/fixes/automated',
        })
      }}
    </div>

    <v-skeleton-loader
      v-if="isPending && !fixes"
      type="card@2"
    />
    <EmptyStateNetworkError v-else-if="error" />
    <v-alert
      v-else-if="(fixes?.length ?? 0) === 0"
      type="success"
      variant="tonal"
    >
      {{
        $formatMessage({
          description: 'Fixes view: none',
          defaultMessage: 'No automated maintenance actions available.',
          id: 'fork/fixes/empty',
        })
      }}
    </v-alert>

    <v-row v-else>
      <v-col
        v-for="fix in fixes ?? []"
        :key="fix.id"
        cols="12"
        md="6"
      >
        <v-card
          variant="outlined"
          class="h-100"
        >
          <v-card-title class="d-flex align-center ga-2">
            <v-icon
              :icon="mapIcon(fix.icon)"
              color="warning"
            />
            {{ fix.title }}
          </v-card-title>
          <v-card-text>
            <p class="text-body-2 mb-3">{{ fix.description }}</p>

            <template
              v-for="p in fix.params"
              :key="p.key"
            >
              <v-select
                v-if="p.type === 'library'"
                v-model="fs(fix.id)[p.key]"
                :items="libraryItems"
                :label="p.label"
                :hint="p.hint"
                persistent-hint
                variant="outlined"
                density="compact"
                class="mb-2"
              />
              <v-switch
                v-else-if="p.type === 'boolean'"
                v-model="fs(fix.id)[p.key]"
                :label="p.label"
                :hint="p.hint"
                persistent-hint
                color="primary"
                density="compact"
              />
              <v-text-field
                v-else-if="p.type === 'number'"
                v-model.number="fs(fix.id)[p.key]"
                type="number"
                :label="p.label"
                :hint="p.hint"
                :min="p.min"
                :max="p.max"
                persistent-hint
                variant="outlined"
                density="compact"
                class="mb-2"
              />
              <v-text-field
                v-else
                v-model="fs(fix.id)[p.key]"
                :label="p.label"
                :hint="p.hint"
                persistent-hint
                variant="outlined"
                density="compact"
                class="mb-2"
              />
            </template>
          </v-card-text>
          <v-card-actions>
            <v-btn
              color="warning"
              variant="tonal"
              :loading="running[fix.id]"
              :disabled="running[fix.id] || isDisabled(fix)"
              @click="runFix(fix)"
            >
              {{ $formatMessage(runLabel) }}
            </v-btn>
            <span
              v-if="result[fix.id]"
              class="text-caption text-medium-emphasis ms-3"
            >
              {{ result[fix.id] }}
            </span>
          </v-card-actions>
        </v-card>
      </v-col>
    </v-row>
  </v-container>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import {
  useDeletedChaptersScanStatus,
  useFixes,
  useRepairComicInfoStatus,
  useRunDeletedChaptersScan,
  useRunRepairComicInfo,
} from '@/colada/fixes'
import { useLibraries } from '@/colada/libraries'
import { client } from '@/generated/openapi/client.gen'
import { useMessagesStore } from '@/stores/messages'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'
import type { Fix } from '@/generated/openapi'

const messagesStore = useMessagesStore()

const { data: fixes, error, isPending } = useFixes()
const { data: libraries } = useLibraries()

const libraryItems = computed(() =>
  (libraries.value ?? []).map((l) => ({ title: l.name, value: l.id })),
)

// --- Re-inject ComicInfo.xml ---
const comicLibraryId = ref<string | undefined>(undefined)
const comicForce = ref(true)
const comicRunning = ref(false)
const { data: comicStatus, refetch: refetchComic } = useRepairComicInfoStatus()
const { mutateAsync: runComicMutation } = useRunRepairComicInfo()

const comicResultVisible = computed(() => {
  const s = comicStatus.value
  return !!s && (s.total > 0 || s.repaired > 0 || s.skipped > 0 || !!s.finishedAt)
})

async function runComicInfo() {
  if (!comicLibraryId.value) return
  try {
    await runComicMutation({ libraryId: comicLibraryId.value, force: comicForce.value })
    comicRunning.value = true
    startComicPolling()
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

// --- Deleted-chapters scan ---
const scanLibraryId = ref<string | undefined>(undefined)
const scanDryRun = ref(true)
const scanRunning = ref(false)
const { data: scanStatus, refetch: refetchScan } = useDeletedChaptersScanStatus()
const { mutateAsync: runScanMutation } = useRunDeletedChaptersScan()

async function runScan() {
  if (!scanLibraryId.value) return
  try {
    await runScanMutation({ libraryId: scanLibraryId.value, dryRun: scanDryRun.value })
    scanRunning.value = true
    startScanPolling()
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

// --- Background polling for both long-running fixes ---
let comicPoll: ReturnType<typeof setInterval> | null = null
let scanPoll: ReturnType<typeof setInterval> | null = null

function startComicPolling() {
  if (comicPoll) return
  comicPoll = setInterval(() => void refetchComic(), 2000)
}

function startScanPolling() {
  if (scanPoll) return
  scanPoll = setInterval(() => void refetchScan(), 2000)
}

watch(
  () => comicStatus.value?.running,
  (r) => {
    if (r) {
      comicRunning.value = true
      if (comicStatus.value?.libraryId && !comicLibraryId.value) {
        comicLibraryId.value = comicStatus.value.libraryId
      }
      startComicPolling()
    } else if (comicRunning.value) {
      comicRunning.value = false
      if (comicPoll) {
        clearInterval(comicPoll)
        comicPoll = null
      }
    }
  },
)

watch(
  () => scanStatus.value?.running,
  (r) => {
    if (r) {
      scanRunning.value = true
      if (scanStatus.value?.libraryId && !scanLibraryId.value) {
        scanLibraryId.value = scanStatus.value.libraryId
      }
      startScanPolling()
    } else if (scanRunning.value) {
      scanRunning.value = false
      if (scanPoll) {
        clearInterval(scanPoll)
        scanPoll = null
      }
    }
  },
)

onMounted(() => {
  void refetchComic()
  void refetchScan()
})

onBeforeUnmount(() => {
  if (comicPoll) clearInterval(comicPoll)
  if (scanPoll) clearInterval(scanPoll)
})

// --- Schema-driven fixes (FixRegistry) ---
const state = reactive<Record<string, Record<string, unknown>>>({})
const running = reactive<Record<string, boolean>>({})
const result = reactive<Record<string, string | null>>({})

watch(
  fixes,
  (list) => {
    for (const fix of list ?? []) {
      if (!state[fix.id]) {
        const initial: Record<string, unknown> = {}
        for (const p of fix.params ?? []) initial[p.key] = p.default ?? null
        state[fix.id] = initial
        running[fix.id] = false
        result[fix.id] = null
      }
    }
  },
  { immediate: true },
)

function fs(id: string): Record<string, unknown> {
  let s = state[id]
  if (!s) {
    s = {}
    state[id] = s
  }
  return s
}

function isDisabled(fix: Fix): boolean {
  return (fix.params ?? []).some((p) => p.type === 'library' && !fs(fix.id)[p.key])
}

function mapIcon(icon: string): string {
  if (icon?.startsWith('mdi-')) return `i-mdi:${icon.slice(4)}`
  return icon || 'i-mdi:wrench'
}

async function runFix(fix: Fix) {
  running[fix.id] = true
  result[fix.id] = null
  try {
    let url = fix.endpoint
    const query: Record<string, unknown> = {}
    for (const [key, val] of Object.entries(state[fix.id] ?? {})) {
      const placeholder = `{${key}}`
      if (url.includes(placeholder)) {
        url = url.replace(placeholder, encodeURIComponent(String(val)))
      } else if (val !== null && val !== undefined && val !== '') {
        query[key] = val
      }
    }

    const options = { url, query }
    const method = (fix.method || 'POST').toUpperCase()
    const response =
      method === 'GET'
        ? await client.get(options)
        : method === 'PUT'
          ? await client.put(options)
          : method === 'DELETE'
            ? await client.delete(options)
            : await client.post(options)

    const msg = (response.data as { message?: string } | undefined)?.message ?? `${fix.title}: done`
    result[fix.id] = msg
    messagesStore.messages.push({ message: msg })
  } catch (e) {
    const msg = (e as Error).message
    result[fix.id] = `Failed: ${msg}`
    messagesStore.messages.push({ message: `${fix.title}: ${msg}`, color: 'error' })
  } finally {
    running[fix.id] = false
  }
}

const runLabel = defineMessage({
  description: 'Fixes view: run button',
  defaultMessage: 'Run',
  id: 'fork/fixes/run',
})
const libraryLabel = defineMessage({
  description: 'Fixes view: library select',
  defaultMessage: 'Library',
  id: 'fork/fixes/library',
})
const runningBackgroundLabel = defineMessage({
  description: 'Fixes view: running in background',
  defaultMessage: 'Running in the background…',
  id: 'fork/fixes/running',
})
const comicTitle = defineMessage({
  description: 'Fixes view: comicinfo title',
  defaultMessage: 'Re-inject ComicInfo.xml',
  id: 'fork/fixes/comic/title',
})
const comicDescription = defineMessage({
  description: 'Fixes view: comicinfo description',
  defaultMessage:
    'Re-generates ComicInfo.xml and series.json for every CBZ in the selected library from MangaDex metadata. Enable Force to overwrite existing ComicInfo.xml.',
  id: 'fork/fixes/comic/description',
})
const comicForceLabel = defineMessage({
  description: 'Fixes view: comicinfo force',
  defaultMessage: 'Force (re-inject even if ComicInfo already exists)',
  id: 'fork/fixes/comic/force',
})
const comicResultLabel = defineMessage({
  description: 'Fixes view: comicinfo result',
  defaultMessage: 'Repaired: {repaired} · Skipped: {skipped} · Processed: {processed}/{total}',
  id: 'fork/fixes/comic/result',
})
const scanTitle = defineMessage({
  description: 'Fixes view: deleted-scan title',
  defaultMessage: 'Deleted-chapters scan',
  id: 'fork/fixes/scan/title',
})
const scanDescription = defineMessage({
  description: 'Fixes view: deleted-scan description',
  defaultMessage:
    'Finds tracked chapter URLs whose CBZ file is gone (orphans). Preview (dry-run) changes nothing. When Preview is off, orphaned entries whose source chapter still exists are removed so they can be re-downloaded; entries whose source is gone are kept.',
  id: 'fork/fixes/scan/description',
})
const scanDryRunLabel = defineMessage({
  description: 'Fixes view: deleted-scan dry-run',
  defaultMessage: 'Preview (dry-run — no changes)',
  id: 'fork/fixes/scan/dryRun',
})
const scanResultLabel = defineMessage({
  description: 'Fixes view: deleted-scan result',
  defaultMessage:
    '{dryRun, select, 1 {Would remove} other {Removed}}: {removed} orphaned entries · Series scanned: {scanned}/{total}',
  id: 'fork/fixes/scan/result',
})
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
