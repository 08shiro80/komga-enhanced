<template>
  <div>
    <v-container fluid>
      <v-row>
        <v-col>
          <h1 class="text-h4 mb-4">Database Backup & Restore</h1>
        </v-col>
      </v-row>

      <!-- Create Backup Section -->
      <v-row>
        <v-col cols="12" md="6">
          <v-card>
            <v-card-title>Create New Backup</v-card-title>
            <v-card-text>
              <p>Create a backup of your Komga database. Backups are stored locally and can be downloaded or restored later.</p>
            </v-card-text>
            <v-card-actions>
              <v-btn
                color="primary"
                :loading="creating"
                @click="createBackup"
              >
                <v-icon left>mdi-database-export</v-icon>
                Create Backup
              </v-btn>
              <v-btn
                color="secondary"
                :loading="creatingFull"
                @click="createFullBackup"
              >
                <v-icon left>mdi-database-export-outline</v-icon>
                Full Backup (Main + Tasks)
              </v-btn>
            </v-card-actions>
          </v-card>
        </v-col>

        <v-col cols="12" md="6">
          <v-card>
            <v-card-title>Cleanup Old Backups</v-card-title>
            <v-card-text>
              <p>Remove old backups to free up disk space. Keep the most recent backups.</p>
              <v-text-field
                v-model.number="keepCount"
                label="Number of backups to keep"
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
                Clean Old Backups
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
              Available Backups
              <v-spacer></v-spacer>
              <v-btn icon @click="loadBackups" :loading="loading">
                <v-icon>mdi-refresh</v-icon>
              </v-btn>
            </v-card-title>

            <v-card-text>
              <v-alert v-if="backups.length === 0 && !loading" type="info" text>
                No backups found. Create your first backup using the button above.
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
                    <span>Download</span>
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
                    <span>Delete</span>
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
                    <span>Restore (requires restart)</span>
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
        <v-card-title class="headline">Delete Backup?</v-card-title>
        <v-card-text>
          Are you sure you want to delete backup: <strong>{{ selectedBackup?.fileName }}</strong>?
          <br>This action cannot be undone.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="deleteDialog = false">Cancel</v-btn>
          <v-btn color="error" text @click="deleteBackup" :loading="deleting">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Restore Confirmation Dialog -->
    <v-dialog v-model="restoreDialog" max-width="600">
      <v-card>
        <v-card-title class="headline error--text">
          <v-icon color="error" left>mdi-alert</v-icon>
          Restore Backup?
        </v-card-title>
        <v-card-text>
          <v-alert type="warning" text>
            <strong>Warning:</strong> Restoring a backup will:
            <ul>
              <li>Replace your current database</li>
              <li>Require an application restart</li>
              <li>Potentially lose recent changes</li>
            </ul>
          </v-alert>
          <p class="mt-4">
            Restore from backup: <strong>{{ selectedBackup?.fileName }}</strong>?
          </p>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="restoreDialog = false">Cancel</v-btn>
          <v-btn color="warning" text @click="restoreBackup" :loading="restoring">
            Restore & Restart
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
        <v-btn text v-bind="attrs" @click="snackbar = false">Close</v-btn>
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
      headers: [
        { text: 'File Name', value: 'fileName' },
        { text: 'Created Date', value: 'createdDate' },
        { text: 'Size', value: 'sizeMb' },
        { text: 'Type', value: 'type' },
        { text: 'Actions', value: 'actions', sortable: false },
      ],
    }
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
        this.showError('Failed to load backups: ' + error.message)
      } finally {
        this.loading = false
      }
    },
    async createBackup() {
      this.creating = true
      try {
        await this.$http.post('/api/v1/backup')
        this.showSuccess('Backup created successfully')
        await this.loadBackups()
      } catch (error) {
        this.showError('Failed to create backup: ' + error.message)
      } finally {
        this.creating = false
      }
    },
    async createFullBackup() {
      this.creatingFull = true
      try {
        await this.$http.post('/api/v1/backup/full')
        this.showSuccess('Full backup created successfully')
        await this.loadBackups()
      } catch (error) {
        this.showError('Failed to create full backup: ' + error.message)
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
        this.showError('Failed to clean backups: ' + error.message)
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

        // Create download link
        const url = window.URL.createObjectURL(new Blob([response.data]))
        const link = document.createElement('a')
        link.href = url
        link.setAttribute('download', backup.fileName)
        document.body.appendChild(link)
        link.click()
        link.remove()
        window.URL.revokeObjectURL(url)

        this.showSuccess('Backup downloaded successfully')
      } catch (error) {
        this.showError('Failed to download backup: ' + error.message)
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
        this.showSuccess('Backup deleted successfully')
        this.deleteDialog = false
        await this.loadBackups()
      } catch (error) {
        this.showError('Failed to delete backup: ' + error.message)
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

        // Show countdown and redirect
        let countdown = 3
        const countdownInterval = setInterval(() => {
          if (countdown > 0) {
            this.showWarning(`Application restarting in ${countdown} seconds...`)
            countdown--
          } else {
            clearInterval(countdownInterval)
            // Redirect to home page - server will be restarting
            this.showWarning('Server restarting... Please wait and refresh if needed.')
            // Try to reconnect after 5 seconds
            setTimeout(() => {
              window.location.href = '/'
            }, 5000)
          }
        }, 1000)
      } catch (error) {
        this.showError('Failed to restore backup: ' + error.message)
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
