<template>
  <div>
    <v-container fluid>
      <v-row>
        <v-col>
          <h1 class="text-h4 mb-4">{{ $t('settings_backup.title') }}</h1>
        </v-col>
      </v-row>

      <!-- Create Backup Section -->
      <v-row>
        <v-col cols="12" md="6">
          <v-card>
            <v-card-title>{{ $t('settings_backup.create_title') }}</v-card-title>
            <v-card-text>
              <p>{{ $t('settings_backup.create_description') }}</p>
            </v-card-text>
            <v-card-actions>
              <v-btn
                color="primary"
                :loading="creating"
                @click="createBackup"
              >
                <v-icon left>mdi-database-export</v-icon>
                {{ $t('settings_backup.create_backup') }}
              </v-btn>
              <v-btn
                color="secondary"
                :loading="creatingFull"
                @click="createFullBackup"
              >
                <v-icon left>mdi-database-export-outline</v-icon>
                {{ $t('settings_backup.create_full_backup') }}
              </v-btn>
            </v-card-actions>
          </v-card>
        </v-col>

        <v-col cols="12" md="6">
          <v-card>
            <v-card-title>{{ $t('settings_backup.cleanup_title') }}</v-card-title>
            <v-card-text>
              <p>{{ $t('settings_backup.cleanup_description') }}</p>
              <v-text-field
                v-model.number="keepCount"
                :label="$t('settings_backup.field_keep_count')"
                type="number"
                min="1"
                max="50"
                outlined
                dense
              ></v-text-field>
            </v-card-text>
            <v-card-actions>
              <v-btn
                color="warning"
                :loading="cleaning"
                @click="cleanBackups"
              >
                <v-icon left>mdi-delete-sweep</v-icon>
                {{ $t('settings_backup.clean_old_backups') }}
              </v-btn>
            </v-card-actions>
          </v-card>
        </v-col>
      </v-row>

      <!-- Backups List -->
      <v-row>
        <v-col cols="12">
          <v-card>
            <v-card-title>
              {{ $t('settings_backup.available_title') }}
              <v-spacer></v-spacer>
              <v-btn icon @click="loadBackups" :loading="loading">
                <v-icon>mdi-refresh</v-icon>
              </v-btn>
            </v-card-title>

            <v-card-text>
              <v-alert v-if="backups.length === 0 && !loading" type="info" text>
                {{ $t('settings_backup.no_backups') }}
              </v-alert>

              <v-data-table
                v-else
                :headers="headers"
                :items="backups"
                :loading="loading"
                :items-per-page="10"
                class="elevation-1"
              >
                <template v-slot:item.createdDate="{ item }">
                  {{ formatDate(item.createdDate) }}
                </template>

                <template v-slot:item.sizeMb="{ item }">
                  {{ item.sizeMb.toFixed(2) }} MB
                </template>

                <template v-slot:item.type="{ item }">
                  <v-chip small :color="getTypeColor(item.type)">
                    {{ item.type }}
                  </v-chip>
                </template>

                <template v-slot:item.actions="{ item }">
                  <v-tooltip bottom>
                    <template v-slot:activator="{ on }">
                      <v-btn
                        icon
                        small
                        v-on="on"
                        @click="downloadBackup(item)"
                        :loading="downloading === item.fileName"
                      >
                        <v-icon small>mdi-download</v-icon>
                      </v-btn>
                    </template>
                    <span>{{ $t('settings_backup.download') }}</span>
                  </v-tooltip>

                  <v-tooltip bottom>
                    <template v-slot:activator="{ on }">
                      <v-btn
                        icon
                        small
                        v-on="on"
                        color="error"
                        @click="confirmDelete(item)"
                      >
                        <v-icon small>mdi-delete</v-icon>
                      </v-btn>
                    </template>
                    <span>{{ $t('settings_backup.delete') }}</span>
                  </v-tooltip>

                  <v-tooltip bottom>
                    <template v-slot:activator="{ on }">
                      <v-btn
                        icon
                        small
                        v-on="on"
                        color="warning"
                        @click="confirmRestore(item)"
                      >
                        <v-icon small>mdi-database-import</v-icon>
                      </v-btn>
                    </template>
                    <span>{{ $t('settings_backup.restore') }}</span>
                  </v-tooltip>
                </template>
              </v-data-table>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>
    </v-container>

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="deleteDialog" max-width="500">
      <v-card>
        <v-card-title class="headline">{{ $t('settings_backup.dialog_delete_title') }}</v-card-title>
        <v-card-text>
          {{ $t('settings_backup.dialog_delete_confirm') }} <strong>{{ selectedBackup?.fileName }}</strong>?
          <br>{{ $t('settings_backup.dialog_delete_info') }}
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="deleteDialog = false">{{ $t('download_manager.cancel') }}</v-btn>
          <v-btn color="error" text @click="deleteBackup" :loading="deleting">{{ $t('settings_backup.delete') }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Restore Confirmation Dialog -->
    <v-dialog v-model="restoreDialog" max-width="600">
      <v-card>
        <v-card-title class="headline error--text">
          <v-icon color="error" left>mdi-alert</v-icon>
          {{ $t('settings_backup.dialog_restore_title') }}
        </v-card-title>
        <v-card-text>
          <v-alert type="warning" text>
            <strong>{{ $t('settings_backup.dialog_restore_warning') }}</strong>
            <ul>
              <li>{{ $t('settings_backup.dialog_restore_item1') }}</li>
              <li>{{ $t('settings_backup.dialog_restore_item2') }}</li>
              <li>{{ $t('settings_backup.dialog_restore_item3') }}</li>
            </ul>
          </v-alert>
          <p class="mt-4">
            {{ $t('settings_backup.dialog_restore_confirm') }} <strong>{{ selectedBackup?.fileName }}</strong>?
          </p>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="restoreDialog = false">{{ $t('download_manager.cancel') }}</v-btn>
          <v-btn color="warning" text @click="restoreBackup" :loading="restoring">
            {{ $t('settings_backup.restore_and_restart') }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Snackbar for notifications -->
    <v-snackbar
      v-model="snackbar"
      :color="snackbarColor"
      :timeout="3000"
      bottom
    >
      {{ snackbarText }}
      <template v-slot:action="{ attrs }">
        <v-btn text v-bind="attrs" @click="snackbar = false">{{ $t('download_manager.close') }}</v-btn>
      </template>
    </v-snackbar>
  </div>
</template>

<script>
import { format, parseISO } from 'date-fns'

export default {
  name: 'SettingsBackup',
  data() {
    return {
      backups: [],
      loading: false,
      creating: false,
      creatingFull: false,
      cleaning: false,
      deleting: false,
      restoring: false,
      downloading: null,
      keepCount: 10,
      deleteDialog: false,
      restoreDialog: false,
      selectedBackup: null,
      snackbar: false,
      snackbarText: '',
      snackbarColor: 'success',
    }
  },
  computed: {
    headers() {
      return [
        { text: this.$t('settings_backup.header_filename'), value: 'fileName' },
        { text: this.$t('settings_backup.header_created'), value: 'createdDate' },
        { text: this.$t('settings_backup.header_size'), value: 'sizeMb' },
        { text: this.$t('settings_backup.header_type'), value: 'type' },
        { text: this.$t('settings_backup.header_actions'), value: 'actions', sortable: false },
      ]
    },
  },
  mounted() {
    this.loadBackups()
  },
  methods: {
    async loadBackups() {
      this.loading = true
      try {
        const response = await this.$http.get('/api/v1/backup')
        this.backups = response.data
      } catch (error) {
        this.showError(this.$t('settings_backup.snack_load_failed') + ': ' + error.message)
      } finally {
        this.loading = false
      }
    },
    async createBackup() {
      this.creating = true
      try {
        await this.$http.post('/api/v1/backup')
        this.showSuccess(this.$t('settings_backup.snack_created'))
        await this.loadBackups()
      } catch (error) {
        this.showError(this.$t('settings_backup.snack_create_failed') + ': ' + error.message)
      } finally {
        this.creating = false
      }
    },
    async createFullBackup() {
      this.creatingFull = true
      try {
        await this.$http.post('/api/v1/backup/full')
        this.showSuccess(this.$t('settings_backup.snack_full_created'))
        await this.loadBackups()
      } catch (error) {
        this.showError(this.$t('settings_backup.snack_full_create_failed') + ': ' + error.message)
      } finally {
        this.creatingFull = false
      }
    },
    async cleanBackups() {
      this.cleaning = true
      try {
        const response = await this.$http.post(`/api/v1/backup/clean?keep=${this.keepCount}`)
        this.showSuccess(response.data.message)
        await this.loadBackups()
      } catch (error) {
        this.showError(this.$t('settings_backup.snack_clean_failed') + ': ' + error.message)
      } finally {
        this.cleaning = false
      }
    },
    async downloadBackup(backup) {
      this.downloading = backup.fileName
      try {
        const response = await this.$http.get(
          `/api/v1/backup/${backup.fileName}/download`,
          { responseType: 'blob' },
        )

        const url = window.URL.createObjectURL(new Blob([response.data]))
        const link = document.createElement('a')
        link.href = url
        link.setAttribute('download', backup.fileName)
        document.body.appendChild(link)
        link.click()
        link.remove()
        window.URL.revokeObjectURL(url)

        this.showSuccess(this.$t('settings_backup.snack_downloaded'))
      } catch (error) {
        this.showError(this.$t('settings_backup.snack_download_failed') + ': ' + error.message)
      } finally {
        this.downloading = null
      }
    },
    confirmDelete(backup) {
      this.selectedBackup = backup
      this.deleteDialog = true
    },
    async deleteBackup() {
      this.deleting = true
      try {
        await this.$http.delete(`/api/v1/backup/${this.selectedBackup.fileName}`)
        this.showSuccess(this.$t('settings_backup.snack_deleted'))
        this.deleteDialog = false
        await this.loadBackups()
      } catch (error) {
        this.showError(this.$t('settings_backup.snack_delete_failed') + ': ' + error.message)
      } finally {
        this.deleting = false
      }
    },
    confirmRestore(backup) {
      this.selectedBackup = backup
      this.restoreDialog = true
    },
    async restoreBackup() {
      this.restoring = true
      try {
        const response = await this.$http.post(
          `/api/v1/backup/restore/${this.selectedBackup.fileName}`,
        )
        this.showSuccess(response.data.message)
        this.restoreDialog = false

        let countdown = 3
        const countdownInterval = setInterval(() => {
          if (countdown > 0) {
            this.showWarning(this.$t('settings_backup.restarting_countdown', { seconds: countdown }))
            countdown--
          } else {
            clearInterval(countdownInterval)
            this.showWarning(this.$t('settings_backup.restarting_message'))
            setTimeout(() => {
              window.location.href = '/'
            }, 5000)
          }
        }, 1000)
      } catch (error) {
        this.showError(this.$t('settings_backup.snack_restore_failed') + ': ' + error.message)
      } finally {
        this.restoring = false
      }
    },
    formatDate(dateString) {
      try {
        return format(parseISO(dateString), 'yyyy-MM-dd HH:mm:ss')
      } catch {
        return dateString
      }
    },
    getTypeColor(type) {
      switch (type) {
        case 'MANUAL':
          return 'primary'
        case 'AUTOMATIC':
          return 'success'
        case 'SCHEDULED':
          return 'info'
        default:
          return 'grey'
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
    showWarning(message) {
      this.snackbarText = message
      this.snackbarColor = 'warning'
      this.snackbar = true
    },
  },
}
</script>
