<template>
  <v-container fluid>
    <div class="d-flex align-center flex-wrap ga-2 mb-3">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Backup view: title',
            defaultMessage: 'Backups',
            id: 'fork/backup/title',
          })
        }}
      </span>
      <v-spacer />
      <v-btn
        color="primary"
        prepend-icon="i-mdi:database"
        :loading="creating"
        @click="create(false)"
      >
        {{
          $formatMessage({
            description: 'Backup view: create db backup',
            defaultMessage: 'Database backup',
            id: 'fork/backup/create',
          })
        }}
      </v-btn>
      <v-btn
        variant="tonal"
        prepend-icon="i-mdi:folder-zip"
        :loading="creating"
        @click="create(true)"
      >
        {{
          $formatMessage({
            description: 'Backup view: create full backup',
            defaultMessage: 'Full backup',
            id: 'fork/backup/createFull',
          })
        }}
      </v-btn>
      <v-btn
        variant="text"
        prepend-icon="i-mdi:broom"
        :loading="cleaning"
        @click="cleanDialog = true"
      >
        {{
          $formatMessage({
            description: 'Backup view: clean old backups',
            defaultMessage: 'Clean old',
            id: 'fork/backup/clean',
          })
        }}
      </v-btn>
    </div>

    <v-skeleton-loader
      v-if="isPending && !backups"
      type="table"
    />
    <EmptyStateNetworkError v-else-if="error" />
    <v-alert
      v-else-if="(backups?.length ?? 0) === 0"
      type="info"
      variant="tonal"
    >
      {{
        $formatMessage({
          description: 'Backup view: empty',
          defaultMessage: 'No backups yet.',
          id: 'fork/backup/empty',
        })
      }}
    </v-alert>

    <v-card
      v-else
      variant="outlined"
    >
      <v-table density="comfortable">
        <thead>
          <tr>
            <th>{{ $formatMessage(colName) }}</th>
            <th>{{ $formatMessage(colType) }}</th>
            <th class="text-end">
              {{ $formatMessage(colSize) }}
            </th>
            <th>{{ $formatMessage(colDate) }}</th>
            <th class="text-end">
              {{ $formatMessage(colActions) }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="b in backups ?? []"
            :key="b.fileName"
          >
            <td class="text-truncate" style="max-width: 340px">
              {{ b.fileName }}
            </td>
            <td>
              <v-chip
                size="small"
                variant="tonal"
                :color="typeColor(b.type)"
              >
                {{ b.type }}
              </v-chip>
            </td>
            <td class="text-end">
              {{ b.sizeMb.toFixed(1) }} MB
            </td>
            <td>{{ formatDate(b.createdDate) }}</td>
            <td class="text-end text-no-wrap">
              <v-btn
                icon="i-mdi:download"
                variant="text"
                size="small"
                :loading="downloadingName === b.fileName"
                :aria-label="$formatMessage(downloadLabel)"
                @click="download(b.fileName)"
              />
              <v-btn
                icon="i-mdi:restore"
                variant="text"
                size="small"
                color="warning"
                :aria-label="$formatMessage(restoreLabel)"
                @click="askRestore(b.fileName)"
              />
              <v-btn
                icon="i-mdi:delete"
                variant="text"
                size="small"
                color="error"
                :aria-label="$formatMessage(deleteLabel)"
                @click="askDelete(b.fileName)"
              />
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card>

    <v-dialog
      v-model="restoreDialog"
      max-width="480"
    >
      <v-card>
        <v-card-title>{{ $formatMessage(restoreTitle) }}</v-card-title>
        <v-card-text>
          {{
            $formatMessage({
              description: 'Backup view: restore warning',
              defaultMessage:
                'Restoring will overwrite the current database and requires a server restart. This cannot be undone.',
              id: 'fork/backup/restore/warning',
            })
          }}
          <div class="mt-2 font-weight-medium text-break">{{ restoreTarget }}</div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="restoreDialog = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="warning"
            :loading="restoring"
            @click="doRestore()"
          >
            {{ $formatMessage(restoreConfirm) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog
      v-model="cleanDialog"
      max-width="440"
    >
      <v-card>
        <v-card-title>{{ $formatMessage(cleanTitle) }}</v-card-title>
        <v-card-text>
          <p class="mb-3 text-body-2">
            {{ $formatMessage(cleanBody) }}
          </p>
          <v-text-field
            v-model.number="keepCount"
            type="number"
            :min="1"
            :max="50"
            :label="$formatMessage(keepLabel)"
            variant="outlined"
            density="comfortable"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="cleanDialog = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="primary"
            :loading="cleaning"
            @click="clean()"
          >
            {{ $formatMessage(cleanConfirm) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog
      v-model="deleteDialog"
      max-width="480"
    >
      <v-card>
        <v-card-title class="text-error">
          {{
            $formatMessage({
              description: 'Backup view: delete dialog title',
              defaultMessage: 'Delete this backup?',
              id: 'fork/backup/delete/title',
            })
          }}
        </v-card-title>
        <v-card-text>
          {{
            $formatMessage({
              description: 'Backup view: delete dialog body',
              defaultMessage: 'This permanently deletes the backup file. This cannot be undone.',
              id: 'fork/backup/delete/body',
            })
          }}
          <div class="mt-2 font-weight-medium text-break">{{ deleteTarget }}</div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="deleteDialog = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="error"
            :loading="deleting"
            @click="executeDelete()"
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
  useBackups,
  useCleanOldBackups,
  useCreateBackup,
  useDeleteBackup,
  useRestoreBackup,
} from '@/colada/backup'
import { komgaDownloadBackup } from '@/generated/openapi'
import { useMessagesStore } from '@/stores/messages'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'

const intl = useIntl()
const messagesStore = useMessagesStore()

const { data: backups, error, isPending } = useBackups()
const { mutateAsync: createBackup, isLoading: creating } = useCreateBackup()
const { mutateAsync: deleteBackup, isLoading: deleting } = useDeleteBackup()
const { mutateAsync: restoreBackup, isLoading: restoring } = useRestoreBackup()
const { mutateAsync: cleanBackups, isLoading: cleaning } = useCleanOldBackups()

const downloadingName = ref<string | null>(null)
const restoreDialog = ref(false)
const restoreTarget = ref<string | null>(null)
const cleanDialog = ref(false)
const keepCount = ref(10)
const deleteDialog = ref(false)
const deleteTarget = ref<string | null>(null)

function formatDate(date: Date): string {
  return intl.formatDate(date, { dateStyle: 'medium', timeStyle: 'short' })
}

function typeColor(type: string): string {
  switch (type.toUpperCase()) {
    case 'FULL':
    case 'MANUAL':
      return 'primary'
    case 'AUTOMATIC':
      return 'success'
    case 'SCHEDULED':
      return 'info'
    case 'DATABASE':
      return 'secondary'
    default:
      return 'grey'
  }
}

async function create(full: boolean) {
  try {
    await createBackup(full)
    messagesStore.messages.push({ message: createdMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

function askDelete(fileName: string) {
  deleteTarget.value = fileName
  deleteDialog.value = true
}

async function executeDelete() {
  if (!deleteTarget.value) return
  try {
    await deleteBackup(deleteTarget.value)
    deleteDialog.value = false
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function clean() {
  try {
    await cleanBackups(keepCount.value)
    cleanDialog.value = false
    messagesStore.messages.push({ message: cleanedMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

function askRestore(fileName: string) {
  restoreTarget.value = fileName
  restoreDialog.value = true
}

async function doRestore() {
  if (!restoreTarget.value) return
  try {
    await restoreBackup(restoreTarget.value)
    restoreDialog.value = false
    messagesStore.messages.push({ message: restoredMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function download(fileName: string) {
  downloadingName.value = fileName
  try {
    const blob = (await komgaDownloadBackup({ path: { fileName } })) as Blob
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    downloadingName.value = null
  }
}

const colName = defineMessage({
  description: 'Backup view: column file name',
  defaultMessage: 'File',
  id: 'fork/backup/col/name',
})
const colType = defineMessage({
  description: 'Backup view: column type',
  defaultMessage: 'Type',
  id: 'fork/backup/col/type',
})
const colSize = defineMessage({
  description: 'Backup view: column size',
  defaultMessage: 'Size',
  id: 'fork/backup/col/size',
})
const colDate = defineMessage({
  description: 'Backup view: column date',
  defaultMessage: 'Created',
  id: 'fork/backup/col/date',
})
const colActions = defineMessage({
  description: 'Backup view: column actions',
  defaultMessage: 'Actions',
  id: 'fork/backup/col/actions',
})
const downloadLabel = defineMessage({
  description: 'Backup view: download',
  defaultMessage: 'Download',
  id: 'fork/backup/download',
})
const restoreLabel = defineMessage({
  description: 'Backup view: restore',
  defaultMessage: 'Restore',
  id: 'fork/backup/restore',
})
const deleteLabel = defineMessage({
  description: 'Backup view: delete',
  defaultMessage: 'Delete',
  id: 'fork/backup/delete',
})
const cancelLabel = defineMessage({
  description: 'Backup view: cancel',
  defaultMessage: 'Cancel',
  id: 'fork/backup/cancel',
})
const restoreTitle = defineMessage({
  description: 'Backup view: restore dialog title',
  defaultMessage: 'Restore this backup?',
  id: 'fork/backup/restore/title',
})
const restoreConfirm = defineMessage({
  description: 'Backup view: restore confirm',
  defaultMessage: 'Restore',
  id: 'fork/backup/restore/confirm',
})
const createdMessage = defineMessage({
  description: 'Backup view: created',
  defaultMessage: 'Backup created',
  id: 'fork/backup/created',
})
const cleanedMessage = defineMessage({
  description: 'Backup view: cleaned',
  defaultMessage: 'Old backups cleaned',
  id: 'fork/backup/cleaned',
})
const cleanTitle = defineMessage({
  description: 'Backup view: clean dialog title',
  defaultMessage: 'Clean old backups',
  id: 'fork/backup/clean/title',
})
const cleanBody = defineMessage({
  description: 'Backup view: clean dialog body',
  defaultMessage: 'Keep the most recent backups and delete the rest.',
  id: 'fork/backup/clean/body',
})
const keepLabel = defineMessage({
  description: 'Backup view: keep count field',
  defaultMessage: 'Backups to keep',
  id: 'fork/backup/clean/keep',
})
const cleanConfirm = defineMessage({
  description: 'Backup view: clean confirm',
  defaultMessage: 'Clean',
  id: 'fork/backup/clean/confirm',
})
const restoredMessage = defineMessage({
  description: 'Backup view: restored',
  defaultMessage: 'Backup restored — restart the server to apply',
  id: 'fork/backup/restored',
})
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
