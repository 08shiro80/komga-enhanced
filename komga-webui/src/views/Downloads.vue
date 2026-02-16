<template>
  <div>
    <v-container fluid>
      <v-row>
        <v-col>
          <h1 class="text-h4 mb-4">{{ $t('download_manager.title') }}</h1>
          <p class="text-subtitle-1">{{ $t('download_manager.subtitle') }}</p>
        </v-col>
      </v-row>

      <v-row>
        <v-col cols="12">
          <v-card>
            <v-card-title>
              {{ $t('download_manager.queue_title') }}
              <v-spacer></v-spacer>
              <v-btn color="primary" @click="addDownloadDialog = true">
                <v-icon left>mdi-plus</v-icon>
                {{ $t('download_manager.add_download') }}
              </v-btn>
              <v-menu offset-y>
                <template v-slot:activator="{ on, attrs }">
                  <v-btn text v-bind="attrs" v-on="on" class="ml-2">
                    <v-icon left>mdi-broom</v-icon>
                    {{ $t('download_manager.clear') }}
                    <v-icon right>mdi-menu-down</v-icon>
                  </v-btn>
                </template>
                <v-list dense>
                  <v-list-item @click="clearByStatus('completed')" :disabled="!hasStatus('COMPLETED')">
                    <v-list-item-icon><v-icon color="success">mdi-check-circle</v-icon></v-list-item-icon>
                    <v-list-item-content>{{ $t('download_manager.clear_completed') }} ({{ countByStatus('COMPLETED') }})</v-list-item-content>
                  </v-list-item>
                  <v-list-item @click="clearByStatus('failed')" :disabled="!hasStatus('FAILED')">
                    <v-list-item-icon><v-icon color="error">mdi-alert-circle</v-icon></v-list-item-icon>
                    <v-list-item-content>{{ $t('download_manager.clear_failed') }} ({{ countByStatus('FAILED') }})</v-list-item-content>
                  </v-list-item>
                  <v-list-item @click="clearByStatus('cancelled')" :disabled="!hasStatus('CANCELLED')">
                    <v-list-item-icon><v-icon color="warning">mdi-cancel</v-icon></v-list-item-icon>
                    <v-list-item-content>{{ $t('download_manager.clear_cancelled') }} ({{ countByStatus('CANCELLED') }})</v-list-item-content>
                  </v-list-item>
                  <v-divider></v-divider>
                  <v-list-item @click="clearByStatus('pending')" :disabled="!hasStatus('PENDING')">
                    <v-list-item-icon><v-icon color="grey">mdi-clock-outline</v-icon></v-list-item-icon>
                    <v-list-item-content>{{ $t('download_manager.clear_pending') }} ({{ countByStatus('PENDING') }})</v-list-item-content>
                  </v-list-item>
                </v-list>
              </v-menu>
              <v-btn icon @click="loadDownloads" :loading="loading" class="ml-2">
                <v-icon>mdi-refresh</v-icon>
              </v-btn>
            </v-card-title>

            <v-card-text>
              <v-alert v-if="downloads.length === 0 && !loading" type="info" text>
                {{ $t('download_manager.no_downloads') }}
              </v-alert>

              <v-data-table
                v-else
                :headers="headers"
                :items="downloads"
                :loading="loading"
                :items-per-page="20"
                class="elevation-1"
              >
                <template v-slot:item.title="{ item }">
                  <div>
                    <div class="font-weight-medium">{{ item.title || $t('download_manager.unknown_title') }}</div>
                    <div class="text-caption text--secondary">{{ item.sourceUrl }}</div>
                  </div>
                </template>

                <template v-slot:item.status="{ item }">
                  <v-chip small :color="getStatusColor(item.status)">
                    {{ item.status }}
                  </v-chip>
                </template>

                <template v-slot:item.progress="{ item }">
                  <div v-if="item.status === 'DOWNLOADING'" style="min-width: 200px;">
                    <v-progress-linear
                      :value="item.progressPercent"
                      height="20"
                      :color="getStatusColor(item.status)"
                    >
                      <span class="white--text caption">
                        {{ item.progressPercent }}%
                        <span v-if="item.totalChapters">
                          ({{ item.currentChapter }}/{{ item.totalChapters }})
                        </span>
                      </span>
                    </v-progress-linear>
                  </div>
                  <div v-else-if="item.status === 'COMPLETED'">
                    <v-chip small color="success">100%</v-chip>
                  </div>
                  <div v-else>-</div>
                </template>

                <template v-slot:item.library="{ item }">
                  <span v-if="item.libraryId">
                    {{ getLibraryName(item.libraryId) }}
                  </span>
                  <span v-else class="text--secondary">{{ $t('download_manager.downloads_folder') }}</span>
                </template>

                <template v-slot:item.createdDate="{ item }">
                  {{ formatDate(item.createdDate) }}
                </template>

                <template v-slot:item.actions="{ item }">
                  <v-tooltip bottom v-if="item.status === 'DOWNLOADING' || item.status === 'PENDING'">
                    <template v-slot:activator="{ on }">
                      <v-btn icon small v-on="on" @click="cancelDownload(item)" color="warning">
                        <v-icon small>mdi-stop</v-icon>
                      </v-btn>
                    </template>
                    <span>{{ $t('download_manager.cancel') }}</span>
                  </v-tooltip>

                  <v-tooltip bottom v-if="item.status === 'FAILED'">
                    <template v-slot:activator="{ on }">
                      <v-btn icon small v-on="on" @click="retryDownload(item)" color="primary">
                        <v-icon small>mdi-refresh</v-icon>
                      </v-btn>
                    </template>
                    <span>{{ $t('download_manager.retry') }}</span>
                  </v-tooltip>

                  <v-tooltip bottom v-if="item.errorMessage">
                    <template v-slot:activator="{ on }">
                      <v-btn icon small v-on="on" @click="showError(item)" color="error">
                        <v-icon small>mdi-alert-circle</v-icon>
                      </v-btn>
                    </template>
                    <span>{{ $t('download_manager.view_error') }}</span>
                  </v-tooltip>

                  <v-tooltip bottom>
                    <template v-slot:activator="{ on }">
                      <v-btn icon small v-on="on" @click="confirmDelete(item)" color="error">
                        <v-icon small>mdi-delete</v-icon>
                      </v-btn>
                    </template>
                    <span>{{ $t('download_manager.delete') }}</span>
                  </v-tooltip>
                </template>
              </v-data-table>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>
    </v-container>

    <!-- Add Download Dialog -->
    <v-dialog v-model="addDownloadDialog" max-width="700">
      <v-card>
        <v-card-title>{{ $t('download_manager.dialog_add_title') }}</v-card-title>
        <v-card-text>
          <v-alert type="info" text class="mb-4">
            {{ $t('download_manager.dialog_add_info') }}
          </v-alert>

          <v-text-field
            v-model="newDownload.sourceUrl"
            :label="$t('download_manager.field_url') + ' *'"
            outlined
            prepend-icon="mdi-link"
            :rules="[v => !!v || $t('download_manager.field_url_required')]"
            placeholder="https://mangadex.org/title/..."
          ></v-text-field>

          <v-text-field
            v-model="newDownload.title"
            :label="$t('download_manager.field_title')"
            outlined
            prepend-icon="mdi-book"
            :hint="$t('download_manager.field_title_hint')"
          ></v-text-field>

          <v-select
            v-model="newDownload.libraryId"
            :label="$t('download_manager.field_library')"
            :items="libraries"
            item-text="name"
            item-value="id"
            outlined
            prepend-icon="mdi-folder"
            clearable
            :hint="$t('download_manager.field_library_hint')"
          ></v-select>

          <v-slider
            v-model="newDownload.priority"
            :label="$t('download_manager.field_priority')"
            min="1"
            max="10"
            step="1"
            thumb-label
            prepend-icon="mdi-priority-high"
          ></v-slider>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="addDownloadDialog = false">{{ $t('download_manager.cancel') }}</v-btn>
          <v-btn
            color="primary"
            @click="addDownload"
            :loading="adding"
            :disabled="!newDownload.sourceUrl"
          >
            {{ $t('download_manager.add_download') }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="deleteDialog" max-width="500">
      <v-card>
        <v-card-title class="headline">{{ $t('download_manager.dialog_delete_title') }}</v-card-title>
        <v-card-text>
          {{ $t('download_manager.dialog_delete_confirm') }} <strong>{{ selectedDownload?.title }}</strong>?
          <br>{{ $t('download_manager.dialog_delete_info') }}
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="deleteDialog = false">{{ $t('download_manager.cancel') }}</v-btn>
          <v-btn color="error" text @click="deleteDownload" :loading="deleting">
            {{ $t('download_manager.delete') }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Error Dialog -->
    <v-dialog v-model="errorDialog" max-width="600">
      <v-card>
        <v-card-title class="error white--text">
          {{ $t('download_manager.dialog_error_title') }}
        </v-card-title>
        <v-card-text class="pt-4">
          <div class="font-weight-medium mb-2">{{ selectedDownload?.title }}</div>
          <v-alert type="error" text>
            {{ selectedDownload?.errorMessage }}
          </v-alert>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="errorDialog = false">{{ $t('download_manager.close') }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Snackbar -->
    <v-snackbar v-model="snackbar" :color="snackbarColor" :timeout="3000" bottom>
      {{ snackbarText }}
      <template v-slot:action="{ attrs }">
        <v-btn text v-bind="attrs" @click="snackbar = false">{{ $t('download_manager.close') }}</v-btn>
      </template>
    </v-snackbar>
  </div>
</template>

<script>
export default {
  name: 'Downloads',
  data() {
    return {
      downloads: [],
      libraries: [],
      loading: false,
      adding: false,
      deleting: false,
      addDownloadDialog: false,
      deleteDialog: false,
      errorDialog: false,
      selectedDownload: null,
      newDownload: {
        sourceUrl: '',
        title: '',
        libraryId: null,
        priority: 5,
      },
      snackbar: false,
      snackbarText: '',
      snackbarColor: 'success',
      refreshInterval: null,
    }
  },
  computed: {
    headers() {
      return [
        { text: this.$t('download_manager.header_title'), value: 'title', sortable: false },
        { text: this.$t('download_manager.header_status'), value: 'status' },
        { text: this.$t('download_manager.header_progress'), value: 'progress', sortable: false },
        { text: this.$t('download_manager.header_library'), value: 'library', sortable: false },
        { text: this.$t('download_manager.header_created'), value: 'createdDate' },
        { text: this.$t('download_manager.header_actions'), value: 'actions', sortable: false },
      ]
    },
  },
  mounted() {
    this.loadDownloads()
    this.loadLibraries()
    this.refreshInterval = setInterval(() => {
      this.loadDownloads(true)
    }, 5000)
  },
  beforeDestroy() {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval)
    }
  },
  methods: {
    async loadDownloads(silent = false) {
      if (!silent) this.loading = true
      try {
        const response = await this.$http.get('/api/v1/downloads')
        this.downloads = response.data
      } catch (error) {
        if (!silent) {
          this.showErrorSnackbar(this.$t('download_manager.snack_load_failed') + ': ' + error.message)
        }
      } finally {
        if (!silent) this.loading = false
      }
    },
    async loadLibraries() {
      try {
        const response = await this.$http.get('/api/v1/libraries')
        this.libraries = response.data
      } catch (error) {
        // Silent fail for libraries
      }
    },
    async addDownload() {
      this.adding = true
      try {
        await this.$http.post('/api/v1/downloads', this.newDownload)
        this.showSuccessSnackbar(this.$t('download_manager.snack_added'))
        this.addDownloadDialog = false
        this.newDownload = {
          sourceUrl: '',
          title: '',
          libraryId: null,
          priority: 5,
        }
        await this.loadDownloads()
      } catch (error) {
        this.showErrorSnackbar(this.$t('download_manager.snack_add_failed') + ': ' + error.message)
      } finally {
        this.adding = false
      }
    },
    async cancelDownload(download) {
      try {
        await this.$http.post(`/api/v1/downloads/${download.id}/action`, { action: 'cancel' })
        this.showSuccessSnackbar(this.$t('download_manager.snack_cancelled'))
        await this.loadDownloads()
      } catch (error) {
        this.showErrorSnackbar(this.$t('download_manager.snack_cancel_failed') + ': ' + error.message)
      }
    },
    async retryDownload(download) {
      try {
        await this.$http.post(`/api/v1/downloads/${download.id}/action`, { action: 'retry' })
        this.showSuccessSnackbar(this.$t('download_manager.snack_retrying'))
        await this.loadDownloads()
      } catch (error) {
        this.showErrorSnackbar(this.$t('download_manager.snack_retry_failed') + ': ' + error.message)
      }
    },
    confirmDelete(download) {
      this.selectedDownload = download
      this.deleteDialog = true
    },
    async deleteDownload() {
      this.deleting = true
      try {
        await this.$http.delete(`/api/v1/downloads/${this.selectedDownload.id}`)
        this.showSuccessSnackbar(this.$t('download_manager.snack_deleted'))
        this.deleteDialog = false
        await this.loadDownloads()
      } catch (error) {
        this.showErrorSnackbar(this.$t('download_manager.snack_delete_failed') + ': ' + error.message)
      } finally {
        this.deleting = false
      }
    },
    showError(download) {
      this.selectedDownload = download
      this.errorDialog = true
    },
    getStatusColor(status) {
      const colors = {
        PENDING: 'grey',
        DOWNLOADING: 'primary',
        COMPLETED: 'success',
        FAILED: 'error',
        CANCELLED: 'warning',
      }
      return colors[status] || 'grey'
    },
    getLibraryName(libraryId) {
      const library = this.libraries.find(l => l.id === libraryId)
      return library ? library.name : libraryId
    },
    formatDate(date) {
      return new Date(date).toLocaleString()
    },
    showSuccessSnackbar(message) {
      this.snackbarText = message
      this.snackbarColor = 'success'
      this.snackbar = true
    },
    showErrorSnackbar(message) {
      this.snackbarText = message
      this.snackbarColor = 'error'
      this.snackbar = true
    },
    hasStatus(status) {
      return this.downloads.some(d => d.status === status)
    },
    countByStatus(status) {
      return this.downloads.filter(d => d.status === status).length
    },
    async clearByStatus(status) {
      try {
        const response = await this.$http.delete(`/api/v1/downloads/clear/${status}`)
        this.showSuccessSnackbar(response.data.message || this.$t('download_manager.snack_clear_failed'))
        await this.loadDownloads()
      } catch (error) {
        this.showErrorSnackbar(this.$t('download_manager.snack_clear_failed') + ': ' + error.message)
      }
    },
  },
}
</script>
