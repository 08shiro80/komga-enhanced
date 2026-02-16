<template>
  <div>
    <v-container fluid>
      <v-row>
        <v-col>
          <h1 class="text-h4 mb-4">
            <v-icon large class="mr-2">mdi-download</v-icon>
            Downloads
            <v-chip
              small
              color="success"
              class="ml-2"
            >
              <v-icon x-small left>mdi-broadcast</v-icon>
              Live
            </v-chip>
          </h1>
        </v-col>
      </v-row>

      <!-- Stats Cards -->
      <v-row>
        <v-col cols="12" sm="3">
          <v-card>
            <v-card-text>
              <div class="text-h4">{{ activeDownloads.length }}</div>
              <div class="text-subtitle-2">Active</div>
            </v-card-text>
          </v-card>
        </v-col>
        <v-col cols="12" sm="3">
          <v-card>
            <v-card-text>
              <div class="text-h4">{{ pendingDownloads.length }}</div>
              <div class="text-subtitle-2">Pending</div>
            </v-card-text>
          </v-card>
        </v-col>
        <v-col cols="12" sm="3">
          <v-card>
            <v-card-text>
              <div class="text-h4 success--text">{{ completedDownloads.length }}</div>
              <div class="text-subtitle-2">Completed</div>
            </v-card-text>
          </v-card>
        </v-col>
        <v-col cols="12" sm="3">
          <v-card>
            <v-card-text>
              <div class="text-h4 error--text">{{ failedDownloads.length }}</div>
              <div class="text-subtitle-2">Failed</div>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- Active Downloads -->
      <v-row v-if="activeDownloads.length > 0">
        <v-col cols="12">
          <v-card>
            <v-card-title>
              <v-icon left>mdi-download-circle</v-icon>
              Active Downloads
            </v-card-title>
            <v-card-text>
              <v-list>
                <v-list-item v-for="download in activeDownloads" :key="download.id">
                  <v-list-item-content>
                    <v-list-item-title>{{ download.title || download.sourceUrl }}</v-list-item-title>
                    <v-list-item-subtitle>
                      {{ download.currentChapter }}/{{ download.totalChapters }} chapters
                    </v-list-item-subtitle>
                    <v-progress-linear
                      :value="download.progressPercent"
                      height="25"
                      class="mt-2"
                    >
                      <strong>{{ download.progressPercent }}%</strong>
                    </v-progress-linear>
                  </v-list-item-content>
                  <v-list-item-action>
                    <div>
                      <v-btn icon @click="pauseDownload(download)" title="Pause">
                        <v-icon>mdi-pause</v-icon>
                      </v-btn>
                      <v-btn icon @click="cancelDownload(download)" title="Cancel">
                        <v-icon color="error">mdi-close</v-icon>
                      </v-btn>
                    </div>
                  </v-list-item-action>
                </v-list-item>
              </v-list>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- Download Queue (All) -->
      <v-row>
        <v-col cols="12">
          <v-card>
            <v-card-title>
              Download Queue
              <v-spacer></v-spacer>
              <v-btn color="primary" @click="newDownloadDialog = true">
                <v-icon left>mdi-plus</v-icon>
                New Download
              </v-btn>
              <v-menu offset-y>
                <template v-slot:activator="{ on, attrs }">
                  <v-btn text v-bind="attrs" v-on="on" class="ml-2">
                    <v-icon left>mdi-broom</v-icon>
                    Clear
                    <v-icon right>mdi-menu-down</v-icon>
                  </v-btn>
                </template>
                <v-list dense>
                  <v-list-item @click="clearByStatus('completed')" :disabled="completedDownloads.length === 0">
                    <v-list-item-icon><v-icon color="success">mdi-check-circle</v-icon></v-list-item-icon>
                    <v-list-item-content>Clear Completed ({{ completedDownloads.length }})</v-list-item-content>
                  </v-list-item>
                  <v-list-item @click="clearByStatus('failed')" :disabled="failedDownloads.length === 0">
                    <v-list-item-icon><v-icon color="error">mdi-alert-circle</v-icon></v-list-item-icon>
                    <v-list-item-content>Clear Failed ({{ failedDownloads.length }})</v-list-item-content>
                  </v-list-item>
                  <v-list-item @click="clearByStatus('cancelled')" :disabled="cancelledDownloads.length === 0">
                    <v-list-item-icon><v-icon color="warning">mdi-cancel</v-icon></v-list-item-icon>
                    <v-list-item-content>Clear Cancelled ({{ cancelledDownloads.length }})</v-list-item-content>
                  </v-list-item>
                  <v-divider></v-divider>
                  <v-list-item @click="clearByStatus('pending')" :disabled="pendingDownloads.length === 0">
                    <v-list-item-icon><v-icon color="grey">mdi-clock-outline</v-icon></v-list-item-icon>
                    <v-list-item-content>Clear Pending ({{ pendingDownloads.length }})</v-list-item-content>
                  </v-list-item>
                </v-list>
              </v-menu>
              <v-btn icon @click="loadDownloads" :loading="loading" class="ml-2">
                <v-icon>mdi-refresh</v-icon>
              </v-btn>
            </v-card-title>

            <v-card-text>
              <v-tabs v-model="tab">
                <v-tab>All</v-tab>
                <v-tab>Pending</v-tab>
                <v-tab>Downloading</v-tab>
                <v-tab>Completed</v-tab>
                <v-tab>Failed</v-tab>
                <v-tab>
                  <v-icon left>mdi-cog</v-icon>
                  Configuration
                </v-tab>
                <v-tab>
                  <v-icon left>mdi-import</v-icon>
                  Tachiyomi Import
                </v-tab>
              </v-tabs>

              <v-tabs-items v-model="tab" class="mt-4">
                <v-tab-item>
                  <download-table :downloads="allDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <download-table :downloads="pendingDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <download-table :downloads="activeDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <download-table :downloads="completedDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <download-table :downloads="failedDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <!-- Follow Configuration Tab - Library-based follow.txt -->
                  <v-row>
                    <v-col cols="12" md="4">
                      <v-card outlined>
                        <v-card-title>
                          <v-icon left>mdi-bookshelf</v-icon>
                          Libraries
                        </v-card-title>
                        <v-list>
                          <v-list-item-group v-model="selectedLibraryIndex" color="primary">
                            <v-list-item
                              v-for="(lib, index) in libraries"
                              :key="lib.id"
                              @click="selectLibrary(index)"
                            >
                              <v-list-item-content>
                                <v-list-item-title>{{ lib.name }}</v-list-item-title>
                              </v-list-item-content>
                            </v-list-item>
                          </v-list-item-group>
                        </v-list>
                      </v-card>
                    </v-col>
                    <v-col cols="12" md="8">
                      <v-card outlined v-if="selectedLibrary">
                        <v-card-title>
                          <v-icon left>mdi-file-document-outline</v-icon>
                          follow.txt - {{ selectedLibrary.name }}
                        </v-card-title>
                        <v-card-subtitle>
                          Edit the follow.txt file in this library. One URL per line.
                        </v-card-subtitle>
                        <v-card-text>
                          <v-textarea
                            v-model="followTxtContent"
                            label="Follow URLs"
                            hint="MangaDex URLs, one per line. Lines starting with # are comments."
                            persistent-hint
                            outlined
                            rows="12"
                            placeholder="https://mangadex.org/title/...&#10;# This is a comment"
                            :loading="loadingFollowTxt"
                          ></v-textarea>
                        </v-card-text>
                        <v-card-actions>
                          <v-btn
                            text
                            @click="checkNow"
                            :loading="checkingNow"
                          >
                            <v-icon left>mdi-update</v-icon>
                            Check Now
                          </v-btn>
                          <v-spacer></v-spacer>
                          <v-btn text @click="loadFollowTxt">
                            <v-icon left>mdi-refresh</v-icon>
                            Reload
                          </v-btn>
                          <v-btn
                            color="primary"
                            @click="saveFollowTxt"
                            :loading="savingFollowTxt"
                            :disabled="!followTxtChanged"
                          >
                            <v-icon left>mdi-content-save</v-icon>
                            Save
                          </v-btn>
                        </v-card-actions>
                      </v-card>
                      <v-card outlined v-else>
                        <v-card-text class="text-center pa-8">
                          <v-icon size="64" color="grey">mdi-arrow-left</v-icon>
                          <p class="mt-4">Select a library to edit its follow.txt</p>
                        </v-card-text>
                      </v-card>

                      <!-- Scheduler Settings -->
                      <v-card outlined class="mt-4">
                        <v-card-title>
                          <v-icon left>mdi-clock-outline</v-icon>
                          Auto-Check Settings
                        </v-card-title>
                        <v-card-text>
                          <v-row>
                            <v-col cols="12" sm="6">
                              <v-text-field
                                v-model.number="schedulerInterval"
                                label="Check Interval (hours)"
                                type="number"
                                outlined
                                dense
                                min="1"
                                hint="How often to check all libraries for new chapters"
                                persistent-hint
                              ></v-text-field>
                            </v-col>
                            <v-col cols="12" sm="6">
                              <v-switch
                                v-model="schedulerEnabled"
                                label="Enable Auto-Check"
                                hint="Automatically check and download new chapters"
                                persistent-hint
                              ></v-switch>
                            </v-col>
                          </v-row>
                        </v-card-text>
                        <v-card-actions>
                          <v-spacer></v-spacer>
                          <v-btn
                            color="primary"
                            @click="saveSchedulerSettings"
                            :loading="savingScheduler"
                          >
                            <v-icon left>mdi-content-save</v-icon>
                            Save Settings
                          </v-btn>
                        </v-card-actions>
                      </v-card>
                    </v-col>
                  </v-row>
                </v-tab-item>
                <v-tab-item>
                  <!-- Tachiyomi Import Tab -->
                  <v-row>
                    <v-col cols="12" md="6">
                      <v-card outlined>
                        <v-card-title>
                          <v-icon left>mdi-import</v-icon>
                          Import from Tachiyomi/Mihon Backup
                        </v-card-title>
                        <v-card-subtitle>
                          Import MangaDex URLs from a Tachiyomi or Mihon backup file into a library's follow.txt
                        </v-card-subtitle>
                        <v-card-text>
                          <v-file-input
                            v-model="tachiyomiFile"
                            label="Backup File"
                            accept=".proto.gz,.tachibk,.json,.json.gz"
                            prepend-icon="mdi-file-upload"
                            outlined
                            show-size
                            hint="Supports .proto.gz, .tachibk, .json, .json.gz formats"
                            persistent-hint
                          ></v-file-input>

                          <v-select
                            v-model="tachiyomiLibraryId"
                            :items="libraries"
                            item-text="name"
                            item-value="id"
                            label="Target Library"
                            outlined
                            prepend-icon="mdi-bookshelf"
                            hint="MangaDex URLs will be added to this library's follow.txt"
                            persistent-hint
                            class="mt-4"
                          />
                        </v-card-text>
                        <v-card-actions>
                          <v-spacer></v-spacer>
                          <v-btn
                            color="primary"
                            @click="importTachiyomi"
                            :loading="importingTachiyomi"
                            :disabled="!tachiyomiFile || !tachiyomiLibraryId"
                          >
                            <v-icon left>mdi-import</v-icon>
                            Import
                          </v-btn>
                        </v-card-actions>
                      </v-card>
                    </v-col>
                    <v-col cols="12" md="6">
                      <!-- Import Result -->
                      <v-card outlined v-if="tachiyomiResult">
                        <v-card-title>
                          <v-icon left :color="tachiyomiResult.success ? 'success' : 'warning'">
                            {{ tachiyomiResult.success ? 'mdi-check-circle' : 'mdi-alert-circle' }}
                          </v-icon>
                          Import Result
                        </v-card-title>
                        <v-card-text>
                          <v-alert :type="tachiyomiResult.success ? 'success' : 'warning'" dense>
                            {{ tachiyomiResult.message }}
                          </v-alert>
                          <v-row class="mt-2">
                            <v-col cols="6" sm="3">
                              <div class="text-h5">{{ tachiyomiResult.totalInBackup }}</div>
                              <div class="text-caption">Total in Backup</div>
                            </v-col>
                            <v-col cols="6" sm="3">
                              <div class="text-h5">{{ tachiyomiResult.mangaDexCount }}</div>
                              <div class="text-caption">MangaDex</div>
                            </v-col>
                            <v-col cols="6" sm="3">
                              <div class="text-h5 success--text">{{ tachiyomiResult.importedCount }}</div>
                              <div class="text-caption">Imported</div>
                            </v-col>
                            <v-col cols="6" sm="3">
                              <div class="text-h5 grey--text">{{ tachiyomiResult.skippedCount }}</div>
                              <div class="text-caption">Skipped</div>
                            </v-col>
                          </v-row>
                          <v-expansion-panels class="mt-4" v-if="tachiyomiResult.imported.length > 0 || tachiyomiResult.errors.length > 0">
                            <v-expansion-panel v-if="tachiyomiResult.imported.length > 0">
                              <v-expansion-panel-header>
                                <v-icon left color="success" small>mdi-check</v-icon>
                                Imported ({{ tachiyomiResult.imported.length }})
                              </v-expansion-panel-header>
                              <v-expansion-panel-content>
                                <v-list dense>
                                  <v-list-item v-for="(title, i) in tachiyomiResult.imported" :key="'imp-'+i">
                                    <v-list-item-content>{{ title }}</v-list-item-content>
                                  </v-list-item>
                                </v-list>
                              </v-expansion-panel-content>
                            </v-expansion-panel>
                            <v-expansion-panel v-if="tachiyomiResult.errors.length > 0">
                              <v-expansion-panel-header>
                                <v-icon left color="error" small>mdi-alert</v-icon>
                                Errors ({{ tachiyomiResult.errors.length }})
                              </v-expansion-panel-header>
                              <v-expansion-panel-content>
                                <v-list dense>
                                  <v-list-item v-for="(err, i) in tachiyomiResult.errors" :key="'err-'+i">
                                    <v-list-item-content class="error--text">{{ err }}</v-list-item-content>
                                  </v-list-item>
                                </v-list>
                              </v-expansion-panel-content>
                            </v-expansion-panel>
                          </v-expansion-panels>
                        </v-card-text>
                      </v-card>
                      <v-card outlined v-else>
                        <v-card-text class="text-center pa-8">
                          <v-icon size="64" color="grey">mdi-file-upload-outline</v-icon>
                          <p class="mt-4">Select a backup file and target library to import</p>
                          <p class="text-caption grey--text">
                            Only MangaDex entries will be imported from the backup.
                            Other sources are not supported.
                          </p>
                        </v-card-text>
                      </v-card>
                    </v-col>
                  </v-row>
                </v-tab-item>
              </v-tabs-items>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>
    </v-container>

    <!-- New Download Dialog -->
    <v-dialog v-model="newDownloadDialog" max-width="600">
      <v-card>
        <v-card-title>Add Download</v-card-title>
        <v-card-text>
          <v-text-field
            v-model="newDownload.sourceUrl"
            label="Source URL"
            placeholder="https://mangadex.org/title/..."
            outlined
            prepend-icon="mdi-link"
          ></v-text-field>

          <v-select
            v-model="newDownload.libraryId"
            :items="libraries"
            item-text="name"
            item-value="id"
            label="Target Library"
            outlined
            prepend-icon="mdi-bookshelf"
            hint="Downloads will go directly into this library folder"
            persistent-hint
          />

          <v-slider
            v-model="newDownload.priority"
            :min="1"
            :max="10"
            label="Priority"
            thumb-label
            prepend-icon="mdi-flag"
            class="mt-4"
          ></v-slider>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="newDownloadDialog = false">Cancel</v-btn>
          <v-btn color="primary" @click="addDownload" :loading="adding">
            Add to Queue
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Snackbar -->
    <v-snackbar v-model="snackbar" :color="snackbarColor" :timeout="3000" bottom>
      {{ snackbarText }}
      <template v-slot:action="{ attrs }">
        <v-btn text v-bind="attrs" @click="snackbar = false">Close</v-btn>
      </template>
    </v-snackbar>
  </div>
</template>

<script>
import DownloadTable from '../components/DownloadTable.vue'
import {
  DOWNLOAD_STARTED,
  DOWNLOAD_PROGRESS,
  DOWNLOAD_COMPLETED,
  DOWNLOAD_FAILED,
} from '@/types/events'

export default {
  name: 'DownloadDashboard',
  components: {
    DownloadTable,
  },
  data() {
    return {
      downloads: [],
      libraries: [],
      loading: false,
      adding: false,
      tab: 0,
      newDownloadDialog: false,
      newDownload: {
        sourceUrl: '',
        libraryId: null,
        priority: 5,
      },
      snackbar: false,
      snackbarText: '',
      snackbarColor: 'success',
      // Library follow.txt
      selectedLibraryIndex: null,
      followTxtContent: '',
      originalFollowTxtContent: '',
      loadingFollowTxt: false,
      savingFollowTxt: false,
      checkingNow: false,
      // Scheduler settings
      schedulerEnabled: false,
      schedulerInterval: 6,
      savingScheduler: false,
      // SSE connection status (using existing SSE infrastructure)
      sseConnected: true,
      // Tachiyomi import
      tachiyomiFile: null,
      tachiyomiLibraryId: null,
      importingTachiyomi: false,
      tachiyomiResult: null,
    }
  },
  computed: {
    allDownloads() {
      return this.downloads
    },
    activeDownloads() {
      return this.downloads.filter(d => d.status === 'DOWNLOADING')
    },
    pendingDownloads() {
      return this.downloads.filter(d => d.status === 'PENDING')
    },
    completedDownloads() {
      return this.downloads.filter(d => d.status === 'COMPLETED')
    },
    failedDownloads() {
      return this.downloads.filter(d => d.status === 'FAILED')
    },
    cancelledDownloads() {
      return this.downloads.filter(d => d.status === 'CANCELLED')
    },
    selectedLibrary() {
      if (this.selectedLibraryIndex === null || this.selectedLibraryIndex === undefined) return null
      return this.libraries[this.selectedLibraryIndex]
    },
    followTxtChanged() {
      return this.followTxtContent !== this.originalFollowTxtContent
    },
  },
  mounted() {
    this.loadDownloads()
    this.loadLibraries()
    this.loadSchedulerSettings()
    this.setupSseListeners()
  },
  beforeDestroy() {
    this.removeSseListeners()
  },
  methods: {
    async loadDownloads() {
      this.loading = true
      try {
        const response = await this.$http.get('/api/v1/downloads')
        this.downloads = response.data
      } catch (error) {
        this.showError('Failed to load downloads: ' + error.message)
      } finally {
        this.loading = false
      }
    },
    async loadLibraries() {
      try {
        const response = await this.$komgaLibraries.getLibraries()
        this.libraries = response
        // Auto-select first library
        if (this.libraries.length > 0) {
          this.selectLibrary(0)
        }
      } catch (error) {
        // Library loading failed
      }
    },
    selectLibrary(index) {
      this.selectedLibraryIndex = index
      this.loadFollowTxt()
    },
    async loadFollowTxt() {
      if (!this.selectedLibrary) return
      this.loadingFollowTxt = true
      try {
        const response = await this.$http.get(`/api/v1/downloads/follow-txt/${this.selectedLibrary.id}`)
        this.followTxtContent = response.data.content || ''
        this.originalFollowTxtContent = this.followTxtContent
      } catch (error) {
        // File might not exist yet
        this.followTxtContent = ''
        this.originalFollowTxtContent = ''
      } finally {
        this.loadingFollowTxt = false
      }
    },
    async saveFollowTxt() {
      if (!this.selectedLibrary) return
      this.savingFollowTxt = true
      try {
        await this.$http.put(`/api/v1/downloads/follow-txt/${this.selectedLibrary.id}`, {
          content: this.followTxtContent,
        })
        this.originalFollowTxtContent = this.followTxtContent
        this.showSuccess('follow.txt saved')
      } catch (error) {
        this.showError('Failed to save follow.txt: ' + error.message)
      } finally {
        this.savingFollowTxt = false
      }
    },
    async checkNow() {
      if (!this.selectedLibrary) return
      this.checkingNow = true
      try {
        await this.$http.post(`/api/v1/downloads/follow-txt/${this.selectedLibrary.id}/check-now`)
        this.showSuccess('Check triggered. Downloads will start shortly.')
      } catch (error) {
        this.showError('Failed to trigger check: ' + error.message)
      } finally {
        this.checkingNow = false
      }
    },
    async loadSchedulerSettings() {
      try {
        const response = await this.$http.get('/api/v1/downloads/scheduler')
        this.schedulerEnabled = response.data.enabled
        this.schedulerInterval = response.data.intervalHours || 6
      } catch (error) {
        // Default values are fine
      }
    },
    async saveSchedulerSettings() {
      this.savingScheduler = true
      try {
        await this.$http.post('/api/v1/downloads/scheduler', {
          enabled: this.schedulerEnabled,
          intervalHours: this.schedulerInterval,
        })
        this.showSuccess('Scheduler settings saved')
      } catch (error) {
        this.showError('Failed to save scheduler settings: ' + error.message)
      } finally {
        this.savingScheduler = false
      }
    },
    async addDownload() {
      if (!this.newDownload.libraryId) {
        this.showError('Please select a target library')
        return
      }
      this.adding = true
      try {
        await this.$http.post('/api/v1/downloads', this.newDownload)
        this.showSuccess('Download added to queue')
        this.newDownloadDialog = false
        this.newDownload = { sourceUrl: '', libraryId: null, priority: 5 }
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to add download: ' + error.message)
      } finally {
        this.adding = false
      }
    },
    async pauseDownload(download) {
      try {
        await this.$http.post(`/api/v1/downloads/${download.id}/action`, { action: 'pause' })
        this.showSuccess('Download paused')
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to pause download: ' + error.message)
      }
    },
    async resumeDownload(download) {
      try {
        await this.$http.post(`/api/v1/downloads/${download.id}/action`, { action: 'resume' })
        this.showSuccess('Download resumed')
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to resume download: ' + error.message)
      }
    },
    async cancelDownload(download) {
      try {
        await this.$http.post(`/api/v1/downloads/${download.id}/action`, { action: 'cancel' })
        this.showSuccess('Download cancelled')
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to cancel download: ' + error.message)
      }
    },
    async deleteDownload(download) {
      try {
        await this.$http.delete(`/api/v1/downloads/${download.id}`)
        this.showSuccess('Download deleted from queue')
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to delete download: ' + error.message)
      }
    },
    handleAction({ download, action }) {
      switch (action) {
        case 'pause':
          this.pauseDownload(download)
          break
        case 'resume':
          this.resumeDownload(download)
          break
        case 'cancel':
          this.cancelDownload(download)
          break
        case 'retry':
          this.resumeDownload(download)
          break
        case 'delete':
          this.deleteDownload(download)
          break
      }
    },
    async clearByStatus(status) {
      try {
        const response = await this.$http.delete(`/api/v1/downloads/clear/${status}`)
        this.showSuccess(response.data.message || `Cleared ${status} downloads`)
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to clear downloads: ' + error.message)
      }
    },
    showSuccess(message) {
      this.snackbarText = message
      this.snackbarColor = 'success'
      this.snackbar = true
    },
    showError(message) {
      this.snackbarText = message
      this.snackbarColor = 'error'
      this.snackbar = true
    },
    // SSE event handlers (using Komga's existing SSE infrastructure)
    setupSseListeners() {
      this.$eventHub.$on(DOWNLOAD_STARTED, this.onDownloadStarted)
      this.$eventHub.$on(DOWNLOAD_PROGRESS, this.onDownloadProgress)
      this.$eventHub.$on(DOWNLOAD_COMPLETED, this.onDownloadCompleted)
      this.$eventHub.$on(DOWNLOAD_FAILED, this.onDownloadFailed)
    },
    removeSseListeners() {
      this.$eventHub.$off(DOWNLOAD_STARTED, this.onDownloadStarted)
      this.$eventHub.$off(DOWNLOAD_PROGRESS, this.onDownloadProgress)
      this.$eventHub.$off(DOWNLOAD_COMPLETED, this.onDownloadCompleted)
      this.$eventHub.$off(DOWNLOAD_FAILED, this.onDownloadFailed)
    },
    onDownloadStarted(data) {
      this.showSuccess(`Download started: ${data.title || data.sourceUrl}`)
      this.updateDownloadFromSse(data)
    },
    onDownloadProgress(data) {
      this.updateDownloadFromSse(data)
    },
    onDownloadCompleted(data) {
      this.showSuccess(`Download completed: ${data.title}`)
      this.updateDownloadFromSse(data)
    },
    onDownloadFailed(data) {
      this.showError(`Download failed: ${data.title} - ${data.errorMessage}`)
      this.updateDownloadFromSse(data)
    },
    updateDownloadFromSse(data) {
      if (!data.downloadId) return

      const index = this.downloads.findIndex(d => d.id === data.downloadId)
      if (index !== -1) {
        // Update existing download reactively
        this.$set(this.downloads, index, {
          ...this.downloads[index],
          status: data.status,
          progressPercent: data.progressPercent ?? this.downloads[index].progressPercent,
          currentChapter: data.currentChapter ?? this.downloads[index].currentChapter,
          totalChapters: data.totalChapters ?? this.downloads[index].totalChapters,
          errorMessage: data.errorMessage ?? this.downloads[index].errorMessage,
        })
      } else {
        // New download - reload full list
        this.loadDownloads()
      }
    },
    // Tachiyomi Import
    async importTachiyomi() {
      if (!this.tachiyomiFile || !this.tachiyomiLibraryId) {
        this.showError('Please select a backup file and target library')
        return
      }
      this.importingTachiyomi = true
      this.tachiyomiResult = null
      try {
        const result = await this.$komgaImport.importTachiyomi(this.tachiyomiFile, this.tachiyomiLibraryId)
        this.tachiyomiResult = result
        if (result.importedCount > 0) {
          this.showSuccess(`Imported ${result.importedCount} manga from Tachiyomi backup`)
          // Refresh follow.txt if we're on the config tab viewing the same library
          if (this.selectedLibrary && this.selectedLibrary.id === this.tachiyomiLibraryId) {
            this.loadFollowTxt()
          }
        } else if (result.skippedCount > 0) {
          this.showSuccess('All manga already exist in follow.txt')
        } else {
          this.showError('No MangaDex manga found in backup')
        }
      } catch (error) {
        this.showError(error.message || 'Failed to import Tachiyomi backup')
      } finally {
        this.importingTachiyomi = false
      }
    },
  },
}
</script>
