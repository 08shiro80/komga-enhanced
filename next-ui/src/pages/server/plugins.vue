<template>
  <v-container fluid>
    <div class="d-flex align-center mb-3">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Plugins view: title',
            defaultMessage: 'Plugins',
            id: 'fork/plugins/title',
          })
        }}
      </span>
      <v-spacer />
      <v-btn
        color="primary"
        prepend-icon="i-mdi:plus"
        @click="installDialog = true"
      >
        {{
          $formatMessage({
            description: 'Plugins view: install button',
            defaultMessage: 'Install plugin',
            id: 'fork/plugins/install',
          })
        }}
      </v-btn>
    </div>

    <v-skeleton-loader
      v-if="isPending && !plugins"
      type="table"
    />
    <EmptyStateNetworkError v-else-if="error" />

    <v-card
      v-else
      variant="outlined"
    >
      <v-table density="comfortable">
        <thead>
          <tr>
            <th>{{ $formatMessage(colName) }}</th>
            <th>{{ $formatMessage(colType) }}</th>
            <th>{{ $formatMessage(colVersion) }}</th>
            <th class="text-center">
              {{ $formatMessage(colEnabled) }}
            </th>
            <th class="text-end">
              {{ $formatMessage(colActions) }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="p in plugins ?? []"
            :key="p.id"
          >
            <td>
              <div class="font-weight-medium">{{ p.name }}</div>
              <div class="text-caption text-medium-emphasis">{{ p.description }}</div>
            </td>
            <td>
              <v-chip
                size="small"
                :color="typeColor(p.pluginType)"
                variant="tonal"
              >
                {{ p.pluginType }}
              </v-chip>
            </td>
            <td>{{ p.version }}</td>
            <td class="text-center">
              <v-switch
                :model-value="p.enabled"
                color="primary"
                density="compact"
                hide-details
                inset
                class="d-inline-flex"
                @update:model-value="toggle(p.id, $event as boolean)"
              />
            </td>
            <td class="text-end text-no-wrap">
              <v-btn
                icon="i-mdi:text-box-search-outline"
                variant="text"
                size="small"
                color="info"
                :aria-label="$formatMessage(logsLabel)"
                @click="openLogs(p.id, p.name)"
              />
              <v-btn
                v-if="p.configSchema"
                icon="i-mdi:cog"
                variant="text"
                size="small"
                :aria-label="$formatMessage(configLabel)"
                @click="openConfig(p.id)"
              />
              <v-btn
                v-if="p.external"
                icon="i-mdi:delete"
                variant="text"
                size="small"
                color="error"
                :aria-label="$formatMessage(deleteLabel)"
                @click="askRemove(p.id, p.name)"
              />
              <v-tooltip
                v-else
                :text="$formatMessage(builtinLabel)"
              >
                <template #activator="{ props }">
                  <v-icon
                    v-bind="props"
                    icon="i-mdi:lock"
                    size="small"
                    class="text-medium-emphasis"
                  />
                </template>
              </v-tooltip>
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card>

    <!-- Install dialog -->
    <v-dialog
      v-model="installDialog"
      max-width="520"
    >
      <v-card>
        <v-card-title>{{ $formatMessage(installTitle) }}</v-card-title>
        <v-card-text>
          <v-file-input
            v-model="installFile"
            :label="$formatMessage(fileLabel)"
            accept=".jar"
            variant="outlined"
            density="comfortable"
            prepend-icon="i-mdi:file"
          />
          <div class="text-center text-caption text-medium-emphasis my-2">
            {{ $formatMessage(orLabel) }}
          </div>
          <v-text-field
            v-model="installUrl"
            :label="$formatMessage(urlLabel)"
            variant="outlined"
            density="comfortable"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="installDialog = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="primary"
            :loading="installing"
            :disabled="!installFile && !installUrl"
            @click="doInstall()"
          >
            {{ $formatMessage(installConfirm) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Config dialog -->
    <v-dialog
      v-model="configDialog"
      max-width="560"
    >
      <v-card>
        <v-card-title>{{ $formatMessage(configTitle) }}</v-card-title>
        <v-card-text>
          <v-skeleton-loader
            v-if="configPending"
            type="list-item@3"
          />
          <template v-else>
            <v-text-field
              v-for="(_value, key) in configForm"
              :key="key"
              v-model="configForm[key]"
              :label="key"
              variant="outlined"
              density="compact"
              class="mb-1"
            />
            <v-alert
              v-if="Object.keys(configForm).length === 0"
              type="info"
              variant="tonal"
              density="compact"
            >
              {{ $formatMessage(noConfigLabel) }}
            </v-alert>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="configDialog = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="primary"
            :loading="savingConfig"
            @click="saveConfig()"
          >
            {{ $formatMessage(saveLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Logs dialog -->
    <v-dialog
      v-model="logsDialog"
      max-width="820"
    >
      <v-card>
        <v-card-title class="d-flex align-center">
          <span class="text-truncate">{{ $formatMessage(logsTitle, { name: logsPluginName }) }}</span>
          <v-spacer />
          <v-btn
            variant="text"
            size="small"
            prepend-icon="i-mdi:delete-sweep"
            :loading="clearingLogs"
            @click="clearLogs()"
          >
            {{ $formatMessage(clearLogsLabel) }}
          </v-btn>
        </v-card-title>
        <v-card-subtitle class="pb-0">
          <v-chip-group
            v-model="logLevelFilter"
            column
          >
            <v-chip
              :value="undefined"
              size="small"
              filter
            >
              {{ $formatMessage(allLevelsLabel) }}
            </v-chip>
            <v-chip
              v-for="lvl in logLevels"
              :key="lvl"
              :value="lvl"
              size="small"
              filter
              :color="logColor(lvl)"
            >
              {{ lvl }}
            </v-chip>
          </v-chip-group>
        </v-card-subtitle>
        <v-card-text style="max-height: 60vh">
          <v-skeleton-loader
            v-if="logsPending"
            type="list-item@4"
          />
          <v-alert
            v-else-if="(logs?.content?.length ?? 0) === 0"
            type="info"
            variant="tonal"
            density="compact"
          >
            {{ $formatMessage(noLogsLabel) }}
          </v-alert>
          <v-timeline
            v-else
            density="compact"
            side="end"
            truncate-line="both"
          >
            <v-timeline-item
              v-for="log in logs?.content ?? []"
              :key="log.id"
              :dot-color="logColor(log.logLevel)"
              size="x-small"
            >
              <div class="d-flex align-center ga-2 mb-1">
                <v-chip
                  size="x-small"
                  :color="logColor(log.logLevel)"
                >
                  {{ log.logLevel }}
                </v-chip>
                <span class="text-caption text-medium-emphasis">{{ formatDate(log.createdDate) }}</span>
              </div>
              <div class="text-body-2">{{ log.message }}</div>
              <v-expansion-panels
                v-if="log.exceptionTrace"
                flat
                class="mt-1"
              >
                <v-expansion-panel>
                  <v-expansion-panel-title class="text-caption text-error px-0">
                    {{ $formatMessage(stackTraceLabel) }}
                  </v-expansion-panel-title>
                  <v-expansion-panel-text>
                    <pre class="text-caption" style="white-space: pre-wrap">{{ log.exceptionTrace }}</pre>
                  </v-expansion-panel-text>
                </v-expansion-panel>
              </v-expansion-panels>
            </v-timeline-item>
          </v-timeline>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="logsDialog = false">
            {{ $formatMessage(closeLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog
      v-model="uninstallDialog"
      max-width="480"
    >
      <v-card>
        <v-card-title class="text-error">
          {{
            $formatMessage({
              description: 'Plugins view: uninstall dialog title',
              defaultMessage: 'Uninstall plugin?',
              id: 'fork/plugins/uninstall/title',
            })
          }}
        </v-card-title>
        <v-card-text>
          {{
            $formatMessage({
              description: 'Plugins view: uninstall dialog body',
              defaultMessage: 'This removes the plugin JAR. This cannot be undone.',
              id: 'fork/plugins/uninstall/body',
            })
          }}
          <div class="mt-2 font-weight-medium">{{ uninstallTarget?.name }}</div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="uninstallDialog = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="error"
            :loading="removing"
            @click="executeRemove()"
          >
            {{ $formatMessage(deleteLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>

<script lang="ts" setup>
import { useIntl, defineMessage } from 'vue-intl'
import {
  useClearPluginLogs,
  useDeletePlugin,
  useInstallPlugin,
  usePluginConfig,
  usePluginLogs,
  usePlugins,
  useUpdatePlugin,
  useUpdatePluginConfig,
} from '@/colada/plugins'
import { useMessagesStore } from '@/stores/messages'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'
import type { SchemaEnum2 } from '@/generated/openapi'

const intl = useIntl()
const messagesStore = useMessagesStore()

const { data: plugins, error, isPending } = usePlugins()
const { mutateAsync: updatePlugin } = useUpdatePlugin()
const { mutateAsync: deletePlugin, isLoading: removing } = useDeletePlugin()
const { mutateAsync: installPlugin, isLoading: installing } = useInstallPlugin()
const { mutateAsync: updateConfig, isLoading: savingConfig } = useUpdatePluginConfig()

const logsDialog = ref(false)
const logsPluginId = ref<string | undefined>(undefined)
const logsPluginName = ref('')
const logLevelFilter = ref<SchemaEnum2 | undefined>(undefined)
const logLevels: SchemaEnum2[] = ['DEBUG', 'INFO', 'WARN', 'ERROR']
const { data: logs, isPending: logsPending } = usePluginLogs(logsPluginId, logLevelFilter)
const { mutateAsync: clearPluginLogs, isLoading: clearingLogs } = useClearPluginLogs()

function openLogs(id: string, name: string) {
  logsPluginId.value = id
  logsPluginName.value = name
  logLevelFilter.value = undefined
  logsDialog.value = true
}

async function clearLogs() {
  if (!logsPluginId.value) return
  try {
    await clearPluginLogs(logsPluginId.value)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

function logColor(level: string): string {
  switch (level) {
    case 'ERROR':
      return 'error'
    case 'WARN':
      return 'warning'
    case 'DEBUG':
      return 'grey'
    default:
      return 'info'
  }
}

function formatDate(date: Date): string {
  return intl.formatDate(date, { dateStyle: 'medium', timeStyle: 'short' })
}

const installDialog = ref(false)
const installFile = ref<File | undefined>(undefined)
const installUrl = ref('')

const uninstallDialog = ref(false)
const uninstallTarget = ref<{ id: string; name: string } | null>(null)

const configDialog = ref(false)
const configPluginId = ref<string | undefined>(undefined)
const { data: configData, isPending: configPending } = usePluginConfig(configPluginId)
const configForm = ref<Record<string, string>>({})

watch(configData, (c) => {
  configForm.value = { ...(c ?? {}) }
})

function typeColor(type: string): string {
  switch (type) {
    case 'METADATA':
      return 'primary'
    case 'NOTIFIER':
      return 'info'
    case 'PROCESSOR':
      return 'success'
    default:
      return 'grey'
  }
}

async function toggle(id: string, enabled: boolean) {
  try {
    await updatePlugin({ id, enabled })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

function askRemove(id: string, name: string) {
  uninstallTarget.value = { id, name }
  uninstallDialog.value = true
}

async function executeRemove() {
  if (!uninstallTarget.value) return
  try {
    await deletePlugin(uninstallTarget.value.id)
    uninstallDialog.value = false
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function doInstall() {
  try {
    await installPlugin({ file: installFile.value, url: installUrl.value || undefined })
    installDialog.value = false
    installFile.value = undefined
    installUrl.value = ''
    messagesStore.messages.push({ message: installedMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

function openConfig(id: string) {
  configPluginId.value = id
  configDialog.value = true
}

async function saveConfig() {
  if (!configPluginId.value) return
  try {
    await updateConfig({ id: configPluginId.value, config: configForm.value })
    configDialog.value = false
    messagesStore.messages.push({ message: savedMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const colName = defineMessage({
  description: 'Plugins view: column name',
  defaultMessage: 'Plugin',
  id: 'fork/plugins/col/name',
})
const colType = defineMessage({
  description: 'Plugins view: column type',
  defaultMessage: 'Type',
  id: 'fork/plugins/col/type',
})
const colVersion = defineMessage({
  description: 'Plugins view: column version',
  defaultMessage: 'Version',
  id: 'fork/plugins/col/version',
})
const colEnabled = defineMessage({
  description: 'Plugins view: column enabled',
  defaultMessage: 'Enabled',
  id: 'fork/plugins/col/enabled',
})
const colActions = defineMessage({
  description: 'Plugins view: column actions',
  defaultMessage: 'Actions',
  id: 'fork/plugins/col/actions',
})
const configLabel = defineMessage({
  description: 'Plugins view: configure',
  defaultMessage: 'Configure',
  id: 'fork/plugins/configure',
})
const deleteLabel = defineMessage({
  description: 'Plugins view: uninstall',
  defaultMessage: 'Uninstall',
  id: 'fork/plugins/uninstall',
})
const builtinLabel = defineMessage({
  description: 'Plugins view: built-in locked',
  defaultMessage: 'Built-in plugin (cannot be removed)',
  id: 'fork/plugins/builtin',
})
const installTitle = defineMessage({
  description: 'Plugins view: install dialog title',
  defaultMessage: 'Install plugin',
  id: 'fork/plugins/install/title',
})
const fileLabel = defineMessage({
  description: 'Plugins view: file input',
  defaultMessage: 'Plugin JAR file',
  id: 'fork/plugins/file',
})
const orLabel = defineMessage({
  description: 'Plugins view: or separator',
  defaultMessage: 'or install from URL',
  id: 'fork/plugins/or',
})
const urlLabel = defineMessage({
  description: 'Plugins view: url input',
  defaultMessage: 'Plugin URL',
  id: 'fork/plugins/url',
})
const installConfirm = defineMessage({
  description: 'Plugins view: install confirm',
  defaultMessage: 'Install',
  id: 'fork/plugins/install/confirm',
})
const cancelLabel = defineMessage({
  description: 'Plugins view: cancel',
  defaultMessage: 'Cancel',
  id: 'fork/plugins/cancel',
})
const configTitle = defineMessage({
  description: 'Plugins view: config dialog title',
  defaultMessage: 'Plugin configuration',
  id: 'fork/plugins/config/title',
})
const noConfigLabel = defineMessage({
  description: 'Plugins view: no config',
  defaultMessage: 'This plugin has no editable configuration.',
  id: 'fork/plugins/config/none',
})
const saveLabel = defineMessage({
  description: 'Plugins view: save config',
  defaultMessage: 'Save',
  id: 'fork/plugins/config/save',
})
const installedMessage = defineMessage({
  description: 'Plugins view: installed confirmation',
  defaultMessage: 'Plugin installed',
  id: 'fork/plugins/installed',
})
const savedMessage = defineMessage({
  description: 'Plugins view: config saved',
  defaultMessage: 'Configuration saved',
  id: 'fork/plugins/saved',
})
const logsLabel = defineMessage({
  description: 'Plugins view: view logs',
  defaultMessage: 'View logs',
  id: 'fork/plugins/logs',
})
const logsTitle = defineMessage({
  description: 'Plugins view: logs dialog title',
  defaultMessage: 'Logs — {name}',
  id: 'fork/plugins/logs/title',
})
const clearLogsLabel = defineMessage({
  description: 'Plugins view: clear logs',
  defaultMessage: 'Clear logs',
  id: 'fork/plugins/logs/clear',
})
const allLevelsLabel = defineMessage({
  description: 'Plugins view: all log levels',
  defaultMessage: 'All',
  id: 'fork/plugins/logs/all',
})
const noLogsLabel = defineMessage({
  description: 'Plugins view: no logs',
  defaultMessage: 'No log entries for this plugin.',
  id: 'fork/plugins/logs/none',
})
const stackTraceLabel = defineMessage({
  description: 'Plugins view: stack trace',
  defaultMessage: 'Stack trace',
  id: 'fork/plugins/logs/stackTrace',
})
const closeLabel = defineMessage({
  description: 'Plugins view: close',
  defaultMessage: 'Close',
  id: 'fork/plugins/close',
})
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
