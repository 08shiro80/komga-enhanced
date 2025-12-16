<template>
  <div>
    <v-data-table
      :headers="headers"
      :items="downloads"
      :items-per-page="10"
      class="elevation-1"
      show-expand
      item-key="id"
    >
      <template v-slot:item.title="{ item }">
        <div>
          <div class="font-weight-medium">{{ item.title || 'Unknown' }}</div>
          <div class="text-caption text-truncate grey--text" style="max-width: 300px;">
            {{ item.sourceUrl }}
          </div>
        </div>
      </template>

      <template v-slot:item.status="{ item }">
        <v-chip small :color="getStatusColor(item.status)" :dark="item.status !== 'PENDING'">
          <v-icon x-small left>{{ getStatusIcon(item.status) }}</v-icon>
          {{ item.status }}
        </v-chip>
      </template>

      <template v-slot:item.progressPercent="{ item }">
        <v-progress-linear
          :value="item.progressPercent"
          height="20"
          :color="getStatusColor(item.status)"
          rounded
          :indeterminate="item.status === 'DOWNLOADING' && item.progressPercent === 0"
        >
          <strong>{{ item.progressPercent }}%</strong>
        </v-progress-linear>
      </template>

      <template v-slot:item.chapters="{ item }">
        <span v-if="item.totalChapters">
          {{ item.currentChapter || 0 }} / {{ item.totalChapters }}
        </span>
        <span v-else class="grey--text">-</span>
      </template>

      <template v-slot:item.actions="{ item }">
        <v-btn
          v-if="item.status === 'DOWNLOADING'"
          icon
          small
          @click="$emit('action', { download: item, action: 'pause' })"
          title="Pause"
        >
          <v-icon small>mdi-pause</v-icon>
        </v-btn>

        <v-btn
          v-if="item.status === 'PAUSED' || item.status === 'PENDING'"
          icon
          small
          @click="$emit('action', { download: item, action: 'resume' })"
          title="Resume"
          color="success"
        >
          <v-icon small>mdi-play</v-icon>
        </v-btn>

        <v-btn
          v-if="item.status === 'FAILED'"
          icon
          small
          @click="$emit('action', { download: item, action: 'retry' })"
          title="Retry"
          color="warning"
        >
          <v-icon small>mdi-restart</v-icon>
        </v-btn>

        <v-btn
          v-if="item.status !== 'COMPLETED' && item.status !== 'CANCELLED'"
          icon
          small
          @click="$emit('action', { download: item, action: 'cancel' })"
          title="Cancel"
          color="error"
        >
          <v-icon small>mdi-close</v-icon>
        </v-btn>

        <v-btn
          v-if="item.status === 'COMPLETED' || item.status === 'CANCELLED' || item.status === 'FAILED'"
          icon
          small
          @click="$emit('action', { download: item, action: 'delete' })"
          title="Delete from queue"
          color="error"
        >
          <v-icon small>mdi-delete</v-icon>
        </v-btn>

        <v-btn
          v-if="item.errorMessage"
          icon
          small
          @click="selectedError = item; errorDialog = true"
          title="View Error"
        >
          <v-icon small color="error">mdi-alert-circle</v-icon>
        </v-btn>
      </template>

      <template v-slot:expanded-item="{ headers, item }">
        <td :colspan="headers.length" class="pa-4">
          <v-row>
            <v-col cols="12" sm="6">
              <div class="text-subtitle-2 mb-1">Source URL</div>
              <div class="text-body-2">
                <a :href="item.sourceUrl" target="_blank">{{ item.sourceUrl }}</a>
              </div>
            </v-col>
            <v-col cols="12" sm="3">
              <div class="text-subtitle-2 mb-1">Created</div>
              <div class="text-body-2">{{ formatDate(item.createdDate) }}</div>
            </v-col>
            <v-col cols="12" sm="3">
              <div class="text-subtitle-2 mb-1">Retries</div>
              <div class="text-body-2">{{ item.retryCount || 0 }} / {{ item.maxRetries || 3 }}</div>
            </v-col>
          </v-row>
          <v-row v-if="item.destinationPath">
            <v-col cols="12">
              <div class="text-subtitle-2 mb-1">Destination</div>
              <div class="text-body-2 font-weight-light">{{ item.destinationPath }}</div>
            </v-col>
          </v-row>
          <v-row v-if="item.errorMessage">
            <v-col cols="12">
              <v-alert type="error" dense text class="mb-0">
                {{ item.errorMessage }}
              </v-alert>
            </v-col>
          </v-row>
        </td>
      </template>
    </v-data-table>

    <!-- Error Dialog -->
    <v-dialog v-model="errorDialog" max-width="500">
      <v-card v-if="selectedError">
        <v-card-title class="error white--text">
          <v-icon left dark>mdi-alert-circle</v-icon>
          Download Error
        </v-card-title>
        <v-card-text class="pt-4">
          <div class="text-subtitle-2 mb-1">Title</div>
          <div class="text-body-1 mb-3">{{ selectedError.title || 'Unknown' }}</div>

          <div class="text-subtitle-2 mb-1">URL</div>
          <div class="text-body-2 mb-3 text-truncate">{{ selectedError.sourceUrl }}</div>

          <div class="text-subtitle-2 mb-1">Error Message</div>
          <v-alert type="error" dense text class="mb-0">
            {{ selectedError.errorMessage }}
          </v-alert>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn
            text
            color="warning"
            @click="$emit('action', { download: selectedError, action: 'retry' }); errorDialog = false"
          >
            <v-icon left>mdi-restart</v-icon>
            Retry
          </v-btn>
          <v-btn text @click="errorDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script>
export default {
  name: 'DownloadTable',
  props: {
    downloads: {
      type: Array,
      default: () => [],
    },
  },
  data() {
    return {
      headers: [
        { text: '', value: 'data-table-expand' },
        { text: 'Title', value: 'title' },
        { text: 'Status', value: 'status', width: '130px' },
        { text: 'Progress', value: 'progressPercent', width: '150px' },
        { text: 'Chapters', value: 'chapters', width: '100px' },
        { text: 'Actions', value: 'actions', sortable: false, width: '180px' },
      ],
      errorDialog: false,
      selectedError: null,
    }
  },
  methods: {
    getStatusColor(status) {
      const colors = {
        PENDING: 'grey',
        DOWNLOADING: 'primary',
        PAUSED: 'warning',
        COMPLETED: 'success',
        FAILED: 'error',
        CANCELLED: 'grey',
      }
      return colors[status] || 'grey'
    },
    getStatusIcon(status) {
      const icons = {
        PENDING: 'mdi-clock-outline',
        DOWNLOADING: 'mdi-download',
        PAUSED: 'mdi-pause',
        COMPLETED: 'mdi-check',
        FAILED: 'mdi-alert',
        CANCELLED: 'mdi-close',
      }
      return icons[status] || 'mdi-help'
    },
    formatDate(dateStr) {
      if (!dateStr) return '-'
      try {
        const date = new Date(dateStr)
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString()
      } catch {
        return dateStr
      }
    },
  },
}
</script>
