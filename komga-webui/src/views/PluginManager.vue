<template>
  <div>
    <v-container fluid>
      <v-row>
        <v-col>
          <h1 class="text-h4 mb-4">Plugin Management</h1>
          <p class="text-subtitle-1">Manage Komga plugins for metadata, downloads, and more.</p>
        </v-col>
      </v-row>

      <v-row>
        <v-col cols="12">
          <v-card>
            <v-card-title>
              Installed Plugins
              <v-spacer></v-spacer>
              <v-btn color="primary" @click="installDialog = true">
                <v-icon left>mdi-plus</v-icon>
                Install Plugin
              </v-btn>
              <v-btn icon @click="loadPlugins" :loading="loading" class="ml-2">
                <v-icon>mdi-refresh</v-icon>
              </v-btn>
            </v-card-title>

            <v-card-text>
              <v-alert v-if="plugins.length === 0 && !loading" type="info" text>
                No plugins installed. Install your first plugin using the button above.
              </v-alert>

              <v-data-table
                v-else
                :headers="headers"
                :items="plugins"
                :loading="loading"
                :items-per-page="10"
                class="elevation-1"
              >
                <template v-slot:item.enabled="{ item }">
                  <v-switch
                    v-model="item.enabled"
                    @change="togglePlugin(item)"
                    :loading="toggling === item.id"
                    dense
                    hide-details
                  ></v-switch>
                </template>

                <template v-slot:item.pluginType="{ item }">
                  <v-chip small :color="getTypeColor(item.pluginType)">
                    {{ item.pluginType }}
                  </v-chip>
                </template>

                <template v-slot:item.actions="{ item }">
                  <v-tooltip bottom>
                    <template v-slot:activator="{ on }">
                      <v-btn icon small v-on="on" @click="showConfig(item)">
                        <v-icon small>mdi-cog</v-icon>
                      </v-btn>
                    </template>
                    <span>Configure</span>
                  </v-tooltip>

                  <v-tooltip bottom>
                    <template v-slot:activator="{ on }">
                      <v-btn icon small v-on="on" @click="showLogs(item)" color="info">
                        <v-icon small>mdi-text-box</v-icon>
                      </v-btn>
                    </template>
                    <span>View Logs</span>
                  </v-tooltip>

                  <v-tooltip bottom>
                    <template v-slot:activator="{ on }">
                      <v-btn icon small v-on="on" @click="confirmUninstall(item)" color="error">
                        <v-icon small>mdi-delete</v-icon>
                      </v-btn>
                    </template>
                    <span>Uninstall</span>
                  </v-tooltip>
                </template>
              </v-data-table>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>
    </v-container>

    <!-- Install Dialog -->
    <v-dialog v-model="installDialog" max-width="600">
      <v-card>
        <v-card-title>Install Plugin</v-card-title>
        <v-card-text>
          <v-alert type="info" text class="mb-4">
            Upload a plugin JAR file or provide a URL to install.
          </v-alert>

          <v-file-input
            v-model="pluginFile"
            label="Plugin File (JAR)"
            accept=".jar"
            outlined
            prepend-icon="mdi-file"
          ></v-file-input>

          <v-text-field
            v-model="pluginUrl"
            label="Or Plugin URL"
            outlined
            prepend-icon="mdi-link"
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="installDialog = false">Cancel</v-btn>
          <v-btn color="primary" @click="installPlugin" :loading="installing">
            Install
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Uninstall Confirmation -->
    <v-dialog v-model="uninstallDialog" max-width="500">
      <v-card>
        <v-card-title class="headline">Uninstall Plugin?</v-card-title>
        <v-card-text>
          Are you sure you want to uninstall: <strong>{{ selectedPlugin?.name }}</strong>?
          <br>This action cannot be undone.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="uninstallDialog = false">Cancel</v-btn>
          <v-btn color="error" text @click="uninstallPlugin" :loading="uninstalling">
            Uninstall
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Config Dialog -->
    <v-dialog v-model="configDialog" max-width="800">
      <v-card>
        <v-card-title>
          Configure {{ selectedPlugin?.name }}
        </v-card-title>
        <v-card-text>
          <v-alert v-if="selectedPlugin?.description" type="info" text class="mb-4">
            {{ selectedPlugin.description }}
          </v-alert>

          <v-form ref="configForm">
            <v-text-field
              v-for="(value, key) in pluginConfig"
              :key="key"
              v-model="pluginConfig[key]"
              :label="formatConfigKey(key)"
              :type="key.includes('password') ? 'password' : 'text'"
              outlined
              dense
              class="mb-2"
            ></v-text-field>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="configDialog = false">Cancel</v-btn>
          <v-btn color="primary" @click="saveConfig" :loading="savingConfig">
            Save
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Logs Dialog -->
    <v-dialog v-model="logsDialog" max-width="1200" scrollable>
      <v-card>
        <v-card-title>
          {{ selectedPlugin?.name }} Logs
          <v-spacer></v-spacer>
          <v-btn icon @click="loadPluginLogs(selectedPlugin)" :loading="loadingLogs">
            <v-icon>mdi-refresh</v-icon>
          </v-btn>
          <v-btn icon @click="clearLogs" color="error" :loading="clearingLogs">
            <v-icon>mdi-delete</v-icon>
          </v-btn>
        </v-card-title>

        <v-card-subtitle>
          <v-chip-group v-model="logLevelFilter" mandatory>
            <v-chip small filter value="">All</v-chip>
            <v-chip small filter value="DEBUG">Debug</v-chip>
            <v-chip small filter value="INFO" color="info">Info</v-chip>
            <v-chip small filter value="WARN" color="warning">Warn</v-chip>
            <v-chip small filter value="ERROR" color="error">Error</v-chip>
          </v-chip-group>
        </v-card-subtitle>

        <v-divider></v-divider>

        <v-card-text style="max-height: 600px;">
          <v-alert v-if="pluginLogs.length === 0 && !loadingLogs" type="info" text>
            No logs found
          </v-alert>

          <v-timeline v-else dense>
            <v-timeline-item
              v-for="log in filteredLogs"
              :key="log.id"
              :color="getLogColor(log.logLevel)"
              small
              fill-dot
            >
              <template v-slot:icon>
                <v-icon small dark>{{ getLogIcon(log.logLevel) }}</v-icon>
              </template>

              <v-card flat>
                <v-card-subtitle class="py-1">
                  <v-chip x-small :color="getLogColor(log.logLevel)">
                    {{ log.logLevel }}
                  </v-chip>
                  <span class="text-caption ml-2">{{ formatDate(log.createdDate) }}</span>
                </v-card-subtitle>
                <v-card-text class="py-2">
                  {{ log.message }}
                  <v-expansion-panels v-if="log.exceptionTrace" flat class="mt-2">
                    <v-expansion-panel>
                      <v-expansion-panel-header class="py-0 px-0">
                        <span class="text-caption error--text">Stack Trace</span>
                      </v-expansion-panel-header>
                      <v-expansion-panel-content>
                        <pre class="text-caption">{{ log.exceptionTrace }}</pre>
                      </v-expansion-panel-content>
                    </v-expansion-panel>
                  </v-expansion-panels>
                </v-card-text>
              </v-card>
            </v-timeline-item>
          </v-timeline>
        </v-card-text>

        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="logsDialog = false">Close</v-btn>
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
export default {
  name: 'PluginManager',
  data() {
    return {
      plugins: [],
      loading: false,
      toggling: null,
      installing: false,
      uninstalling: false,
      installDialog: false,
      uninstallDialog: false,
      configDialog: false,
      logsDialog: false,
      selectedPlugin: null,
      pluginFile: null,
      pluginUrl: '',
      pluginConfig: {},
      savingConfig: false,
      pluginLogs: [],
      loadingLogs: false,
      clearingLogs: false,
      logLevelFilter: '',
      snackbar: false,
      snackbarText: '',
      snackbarColor: 'success',
      headers: [
        { text: 'Name', value: 'name' },
        { text: 'Version', value: 'version' },
        { text: 'Type', value: 'pluginType' },
        { text: 'Author', value: 'author' },
        { text: 'Enabled', value: 'enabled' },
        { text: 'Actions', value: 'actions', sortable: false },
      ],
    }
  },
  computed: {
    filteredLogs() {
      if (!this.logLevelFilter) return this.pluginLogs
      return this.pluginLogs.filter(log => log.logLevel === this.logLevelFilter)
    },
  },
  mounted() {
    this.loadPlugins()
  },
  methods: {
    async loadPlugins() {
      this.loading = true
      try {
        const response = await this.$http.get('/api/v1/plugins')
        this.plugins = response.data
      } catch (error) {
        this.showError('Failed to load plugins: ' + error.message)
      } finally {
        this.loading = false
      }
    },
    async togglePlugin(plugin) {
      this.toggling = plugin.id
      try {
        await this.$http.patch(`/api/v1/plugins/${plugin.id}`, { enabled: plugin.enabled })
        this.showSuccess(`Plugin ${plugin.enabled ? 'enabled' : 'disabled'}`)
      } catch (error) {
        plugin.enabled = !plugin.enabled // Revert on error
        this.showError('Failed to toggle plugin: ' + error.message)
      } finally {
        this.toggling = null
      }
    },
    async installPlugin() {
      this.installing = true
      try {
        // TODO: Implement file upload or URL installation
        this.showSuccess('Plugin installation will be available soon')
        this.installDialog = false
      } catch (error) {
        this.showError('Failed to install plugin: ' + error.message)
      } finally {
        this.installing = false
      }
    },
    confirmUninstall(plugin) {
      this.selectedPlugin = plugin
      this.uninstallDialog = true
    },
    async uninstallPlugin() {
      this.uninstalling = true
      try {
        await this.$http.delete(`/api/v1/plugins/${this.selectedPlugin.id}`)
        this.showSuccess('Plugin uninstalled')
        this.uninstallDialog = false
        await this.loadPlugins()
      } catch (error) {
        this.showError('Failed to uninstall plugin: ' + error.message)
      } finally {
        this.uninstalling = false
      }
    },
    async showConfig(plugin) {
      this.selectedPlugin = plugin
      try {
        const response = await this.$http.get(`/api/v1/plugins/${plugin.id}/config`)
        this.pluginConfig = response.data || {}

        // Add default fields for gallery-dl if empty
        if (plugin.id === 'gallery-dl-downloader' && Object.keys(this.pluginConfig).length === 0) {
          this.pluginConfig = {
            mangadex_username: '',
            mangadex_password: '',
            default_language: 'en',
          }
        }

        this.configDialog = true
      } catch (error) {
        this.showError('Failed to load config: ' + error.message)
      }
    },
    async saveConfig() {
      this.savingConfig = true
      try {
        await this.$http.post(`/api/v1/plugins/${this.selectedPlugin.id}/config`, this.pluginConfig)
        this.showSuccess('Configuration saved')
        this.configDialog = false
      } catch (error) {
        this.showError('Failed to save config: ' + error.message)
      } finally {
        this.savingConfig = false
      }
    },
    async showLogs(plugin) {
      this.selectedPlugin = plugin
      this.logsDialog = true
      await this.loadPluginLogs(plugin)
    },
    async loadPluginLogs(plugin) {
      if (!plugin) return
      this.loadingLogs = true
      try {
        const response = await this.$http.get(`/api/v1/plugins/${plugin.id}/logs`, {
          params: { page: 0, size: 100 },
        })
        this.pluginLogs = response.data.content || []
      } catch (error) {
        this.showError('Failed to load logs: ' + error.message)
      } finally {
        this.loadingLogs = false
      }
    },
    async clearLogs() {
      this.clearingLogs = true
      try {
        await this.$http.delete(`/api/v1/plugins/${this.selectedPlugin.id}/logs`)
        this.pluginLogs = []
        this.showSuccess('Logs cleared')
      } catch (error) {
        this.showError('Failed to clear logs: ' + error.message)
      } finally {
        this.clearingLogs = false
      }
    },
    formatConfigKey(key) {
      return key.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase())
    },
    formatDate(date) {
      return new Date(date).toLocaleString()
    },
    getLogColor(level) {
      const colors = {
        DEBUG: 'grey',
        INFO: 'info',
        WARN: 'warning',
        ERROR: 'error',
      }
      return colors[level] || 'grey'
    },
    getLogIcon(level) {
      const icons = {
        DEBUG: 'mdi-bug',
        INFO: 'mdi-information',
        WARN: 'mdi-alert',
        ERROR: 'mdi-alert-circle',
      }
      return icons[level] || 'mdi-circle'
    },
    getTypeColor(type) {
      const colors = {
        METADATA: 'blue',
        DOWNLOAD: 'green',
        TASK: 'orange',
        PROCESSOR: 'purple',
        NOTIFIER: 'pink',
        ANALYZER: 'cyan',
      }
      return colors[type] || 'grey'
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
    showInfo(message) {
      this.snackbarText = message
      this.snackbarColor = 'info'
      this.snackbar = true
    },
  },
}
</script>
