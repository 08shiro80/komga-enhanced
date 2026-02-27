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
        <v-btn-toggle v-model="autoRefresh" dense>
          <v-btn :value="true" small>
            <v-icon small left>mdi-refresh-auto</v-icon>
            Auto
          </v-btn>
        </v-btn-toggle>
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
    logText: '',
    search: '',
    lines: 500,
    lineOptions: [100, 250, 500, 1000, 2500, 5000],
    autoRefresh: false,
    loading: false,
    refreshTimer: null as number | null,
  }),
  computed: {
    filteredLines(): string[] {
      const allLines = this.logText.split('\n')
      if (!this.search) return allLines
      const s = this.search.toLowerCase()
      return allLines.filter(l => l.toLowerCase().includes(s))
    },
  },
  watch: {
    autoRefresh(val) {
      if (val) {
        this.startAutoRefresh()
      } else {
        this.stopAutoRefresh()
      }
    },
    lines() {
      this.fetchLogs()
    },
  },
  mounted() {
    this.fetchLogs()
  },
  beforeDestroy() {
    this.stopAutoRefresh()
  },
  methods: {
    async fetchLogs() {
      this.loading = true
      try {
        const resp = await this.$http.get('/api/v1/logs', {
          params: {lines: this.lines},
          headers: {Accept: 'text/plain'},
        })
        this.logText = resp.data
        this.$nextTick(() => {
          const el = this.$refs.logContainer as HTMLElement
          if (el) el.scrollTop = el.scrollHeight
        })
      } catch (e) {
        this.logText = 'Failed to load logs.'
      } finally {
        this.loading = false
      }
    },
    downloadLogs() {
      window.open(`${urls.originNoSlash}/api/v1/logs/download`, '_blank')
    },
    startAutoRefresh() {
      this.stopAutoRefresh()
      this.refreshTimer = window.setInterval(() => {
        this.fetchLogs()
      }, 5000)
    },
    stopAutoRefresh() {
      if (this.refreshTimer !== null) {
        window.clearInterval(this.refreshTimer)
        this.refreshTimer = null
      }
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
