<template>
  <v-container fluid>
    <div class="d-flex align-center flex-wrap ga-2 mb-3">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Logs view: page title',
            defaultMessage: 'Logs',
            id: 'fork/logs/title',
          })
        }}
      </span>
      <v-spacer />

      <v-text-field
        v-model="search"
        :label="$formatMessage(filterLabel)"
        variant="outlined"
        density="compact"
        hide-details
        clearable
        prepend-inner-icon="i-mdi:filter"
        style="max-width: 240px"
      />
      <v-select
        v-model="lines"
        :items="lineOptions"
        :label="$formatMessage(linesLabel)"
        variant="outlined"
        density="compact"
        hide-details
        style="max-width: 120px"
      />
      <v-select
        v-model="level"
        :items="levelOptions"
        :label="$formatMessage(levelLabel)"
        variant="outlined"
        density="compact"
        hide-details
        :loading="settingLevel"
        style="max-width: 140px"
        @update:model-value="changeLevel"
      />
      <v-btn
        :color="streaming ? 'success' : undefined"
        :variant="streaming ? 'tonal' : 'text'"
        prepend-icon="i-mdi:play"
        @click="toggleStream"
      >
        {{ $formatMessage(liveLabel) }}
      </v-btn>
      <v-btn
        :disabled="!streaming"
        :color="paused ? 'warning' : undefined"
        variant="text"
        :prepend-icon="paused ? 'i-mdi:play-pause' : 'i-mdi:pause'"
        @click="togglePause"
      >
        {{ $formatMessage(pauseLabel) }}
      </v-btn>
      <v-btn
        icon="i-mdi:refresh"
        variant="text"
        :loading="isPending"
        @click="refetch()"
      />
      <v-btn
        icon="i-mdi:download"
        variant="text"
        :loading="downloading"
        :aria-label="$formatMessage(downloadLabel)"
        @click="downloadLogs"
      />
    </div>

    <EmptyStateNetworkError v-if="error" />

    <v-card
      v-else
      variant="outlined"
    >
      <v-virtual-scroll
        ref="virtualScroll"
        :items="filteredLines"
        height="calc(100vh - 220px)"
        item-height="20"
      >
        <template #default="{ item }">
          <pre
            class="log-line"
            :class="lineClass(item)"
          >{{ item }}</pre>
        </template>
      </v-virtual-scroll>
    </v-card>
  </v-container>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useLogLevel, useLogs, useSetLogLevel } from '@/colada/logs'
import { komgaDownloadLogs } from '@/generated/openapi'
import { ApiBaseUrl } from '@/api/base'
import { useMessagesStore } from '@/stores/messages'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'
import type { VVirtualScroll } from 'vuetify/components'

const messagesStore = useMessagesStore()

const lines = ref(500)
const lineOptions = [100, 250, 500, 1000, 2500, 5000]
const search = ref('')
const streaming = ref(false)
const paused = ref(false)
const downloading = ref(false)

const virtualScroll = ref<InstanceType<typeof VVirtualScroll> | null>(null)

const { data: logText, error, isPending, refetch } = useLogs(lines)
const { data: levelData } = useLogLevel()
const { mutateAsync: setLevel, isLoading: settingLevel } = useSetLogLevel()

const level = ref('WARN')
const levelOptions = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF']

watch(
  levelData,
  (d) => {
    const current = (d as { level?: string } | undefined)?.level
    if (current) level.value = current
  },
  { immediate: true },
)

const logLines = ref<string[]>([])

watch(
  logText,
  (text) => {
    // While streaming, keep the live buffer — a background refetch must not wipe streamed lines.
    if (streaming.value) return
    logLines.value = (text ?? '').split('\n')
    scrollToBottom()
  },
  { immediate: true },
)

const filteredLines = computed(() => {
  if (!search.value) return logLines.value
  const s = search.value.toLowerCase()
  return logLines.value.filter((l) => l.toLowerCase().includes(s))
})

function scrollToBottom() {
  nextTick(() => {
    virtualScroll.value?.scrollToIndex(filteredLines.value.length - 1)
  })
}

function lineClass(line: string): string {
  if (line.includes(' ERROR ')) return 'text-error'
  if (line.includes(' WARN ')) return 'text-warning'
  if (line.includes(' DEBUG ') || line.includes(' TRACE ')) return 'text-medium-emphasis'
  return ''
}

async function changeLevel(newLevel: string) {
  try {
    await setLevel(newLevel)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

let eventSource: EventSource | null = null
let lineBuffer: string[] = []
let pauseBuffer: string[] = []
let flushTimer: ReturnType<typeof setTimeout> | null = null

function flushBuffer() {
  if (lineBuffer.length === 0) return
  logLines.value.push(...lineBuffer)
  const excess = logLines.value.length - 5000
  if (excess > 0) logLines.value.splice(0, excess)
  lineBuffer = []
  scrollToBottom()
}

function startStream() {
  stopStream()
  eventSource = new EventSource(`${ApiBaseUrl.noSlash}/api/v1/logs/stream`, {
    withCredentials: true,
  })
  eventSource.onmessage = (e) => {
    const incoming = (e.data as string).split('\n')
    if (paused.value) {
      pauseBuffer.push(...incoming)
      return
    }
    lineBuffer.push(...incoming)
    if (!flushTimer) {
      flushTimer = setTimeout(() => {
        flushBuffer()
        flushTimer = null
      }, 150)
    }
  }
  eventSource.onerror = () => stopStream()
  streaming.value = true
  paused.value = false
}

function stopStream() {
  if (flushTimer) {
    clearTimeout(flushTimer)
    flushTimer = null
  }
  eventSource?.close()
  eventSource = null
  streaming.value = false
  paused.value = false
  lineBuffer = []
  pauseBuffer = []
}

function toggleStream() {
  if (streaming.value) stopStream()
  else startStream()
}

function togglePause() {
  if (paused.value) {
    lineBuffer.push(...pauseBuffer)
    pauseBuffer = []
    paused.value = false
    flushBuffer()
  } else {
    paused.value = true
  }
}

async function downloadLogs() {
  downloading.value = true
  try {
    const blob = (await komgaDownloadLogs()) as Blob
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'komga.log'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    downloading.value = false
  }
}

onMounted(() => startStream())
onBeforeUnmount(() => stopStream())

const filterLabel = defineMessage({
  description: 'Logs view: filter field',
  defaultMessage: 'Filter',
  id: 'fork/logs/filter',
})
const linesLabel = defineMessage({
  description: 'Logs view: line count',
  defaultMessage: 'Lines',
  id: 'fork/logs/lines',
})
const levelLabel = defineMessage({
  description: 'Logs view: log level',
  defaultMessage: 'Level',
  id: 'fork/logs/level',
})
const liveLabel = defineMessage({
  description: 'Logs view: live stream toggle',
  defaultMessage: 'Live',
  id: 'fork/logs/live',
})
const pauseLabel = defineMessage({
  description: 'Logs view: pause stream',
  defaultMessage: 'Pause',
  id: 'fork/logs/pause',
})
const downloadLabel = defineMessage({
  description: 'Logs view: download logs',
  defaultMessage: 'Download logs',
  id: 'fork/logs/download',
})
</script>

<style scoped>
.log-line {
  margin: 0;
  padding: 0 12px;
  font-family: monospace;
  font-size: 12px;
  line-height: 20px;
  white-space: pre;
}
</style>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
