<template>
  <v-container fluid class="pa-6">
    <v-row align="center">
      <v-col cols="auto">
        <span class="font-weight-black text-h5">Logs</span>
      </v-col>
      <v-spacer/>
      <v-col cols="auto">
        <v-text-field
          v-model="search"
          prepend-inner-icon="mdi-magnify"
          label="Filter"
          dense
          outlined
          hide-details
          clearable
          style="max-width: 300px"
        />
      </v-col>
      <v-col cols="auto">
        <v-select
          v-model="lines"
          :items="lineOptions"
          label="Lines"
          dense
          outlined
          hide-details
          style="max-width: 120px"
        />
      </v-col>
      <v-col cols="auto">
        <v-switch
          v-model="debugMode"
          label="Debug"
          dense
          hide-details
          class="mt-0"
          :loading="levelLoading"
          @change="toggleLogLevel"
        />
      </v-col>
      <v-col cols="auto">
        <v-btn
          :color="streaming ? 'success' : 'grey'"
          @click="toggleStream"
          small
        >
          <v-icon small left>{{ streaming ? 'mdi-play' : 'mdi-play-outline' }}</v-icon>
          Live
        </v-btn>
      </v-col>
      <v-col cols="auto">
        <v-btn
          :disabled="!streaming"
          :color="paused ? 'warning' : 'grey'"
          @click="togglePause"
          small
        >
          <v-icon small left>{{ paused ? 'mdi-pause' : 'mdi-pause-circle-outline' }}</v-icon>
          Pause
        </v-btn>
      </v-col>
      <v-col cols="auto">
        <v-btn color="primary" @click="fetchLogs" :loading="loading" small>
          <v-icon small left>mdi-refresh</v-icon>
          Refresh
        </v-btn>
      </v-col>
      <v-col cols="auto">
        <v-btn @click="downloadLogs" small>
          <v-icon small left>mdi-download</v-icon>
          Download
        </v-btn>
      </v-col>
    </v-row>
    <v-row>
      <v-col>
        <div class="log-container" ref="logContainer">
          <pre class="log-content"><template
            v-for="(line, i) in filteredLines"
          ><span :key="i" :class="logLevelClass(line)">{{ line }}
</span></template></pre>
        </div>
      </v-col>
    </v-row>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import urls from '@/functions/urls'

export default Vue.extend({
  name: 'LogsView',
  data: () => ({
    logLines: [] as string[],
    search: '',
    lines: 500,
    lineOptions: [100, 250, 500, 1000, 2500, 5000],
    loading: false,
    debugMode: false,
    levelLoading: false,
    streaming: false,
    paused: false,
    eventSource: null as EventSource | null,
    pauseBuffer: [] as string[],
  }),
  computed: {
    filteredLines(): string[] {
      if (!this.search) return this.logLines
      const s = this.search.toLowerCase()
      return this.logLines.filter(l => l.toLowerCase().includes(s))
    },
  },
  watch: {
    lines() {
      this.fetchLogs()
    },
  },
  mounted() {
    this.fetchLogLevel()
    this.fetchLogs()
    this.startStream()
  },
  beforeDestroy() {
    this.stopStream()
  },
  methods: {
    async fetchLogs() {
      this.loading = true
      try {
        const resp = await this.$http.get('/api/v1/logs', {
          params: {lines: this.lines},
          headers: {Accept: 'text/plain'},
        })
        this.logLines = (resp.data as string).split('\n')
        this.scrollToBottom()
      } catch (e) {
        this.logLines = ['Failed to load logs.']
      } finally {
        this.loading = false
      }
    },
    async fetchLogLevel() {
      try {
        const resp = await this.$http.get('/api/v1/logs/level')
        this.debugMode = resp.data.level === 'DEBUG' || resp.data.level === 'TRACE'
      } catch {
        this.debugMode = false
      }
    },
    async toggleLogLevel(val: boolean) {
      this.levelLoading = true
      try {
        await this.$http.post('/api/v1/logs/level', null, {
          params: {level: val ? 'DEBUG' : 'INFO'},
        })
      } catch {
        this.debugMode = !val
      } finally {
        this.levelLoading = false
      }
    },
    toggleStream() {
      if (this.streaming) {
        this.stopStream()
      } else {
        this.startStream()
      }
    },
    startStream() {
      this.stopStream()
      const url = `${urls.originNoSlash}/api/v1/logs/stream`
      this.eventSource = new EventSource(url)
      this.streaming = true
      this.paused = false
      this.pauseBuffer = []

      this.eventSource.onmessage = (event: MessageEvent) => {
        if (this.paused) {
          this.pauseBuffer.push(event.data)
          return
        }
        this.appendLine(event.data)
      }

      this.eventSource.onerror = () => {
        this.streaming = false
        this.paused = false
        if (this.eventSource) {
          this.eventSource.close()
          this.eventSource = null
        }
      }
    },
    stopStream() {
      if (this.eventSource) {
        this.eventSource.close()
        this.eventSource = null
      }
      this.streaming = false
      this.paused = false
      this.pauseBuffer = []
    },
    togglePause() {
      if (this.paused) {
        for (const line of this.pauseBuffer) {
          this.appendLine(line)
        }
        this.pauseBuffer = []
        this.paused = false
      } else {
        this.paused = true
      }
    },
    appendLine(line: string) {
      this.logLines.push(line)
      while (this.logLines.length > this.lines) {
        this.logLines.shift()
      }
      this.scrollToBottom()
    },
    scrollToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.logContainer as HTMLElement
        if (el) el.scrollTop = el.scrollHeight
      })
    },
    downloadLogs() {
      window.open(`${urls.originNoSlash}/api/v1/logs/download`, '_blank')
    },
    logLevelClass(line: string): string {
      if (line.includes(' ERROR ')) return 'log-error'
      if (line.includes(' WARN ')) return 'log-warn'
      if (line.includes(' DEBUG ') || line.includes(' TRACE ')) return 'log-debug'
      return ''
    },
  },
})
</script>

<style scoped>
.log-container {
  background-color: #1e1e1e;
  border-radius: 4px;
  max-height: calc(100vh - 200px);
  overflow: auto;
  padding: 12px;
}

.log-content {
  font-family: 'Courier New', Consolas, monospace;
  font-size: 12px;
  color: #d4d4d4;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.log-error {
  color: #f44336;
}

.log-warn {
  color: #ff9800;
}

.log-debug {
  color: #9e9e9e;
}
</style>
