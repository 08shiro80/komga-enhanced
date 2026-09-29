<template>
  <v-container
    fluid
    class="pa-0 pa-sm-4"
  >
    <v-skeleton-loader
      v-if="isPending"
      type="article@6, button@2"
    />

    <EmptyStateNetworkError v-else-if="error" />

    <template v-else-if="settings">
      <v-card max-width="600px">
        <v-card-text>
          <ServerSettings
            :settings="settings"
            :loading="loading"
            @update-settings="(s) => saveSettings(s)"
          />
        </v-card-text>
      </v-card>

      <v-card
        max-width="600px"
        class="mt-4"
      >
        <v-card-title class="text-subtitle-1">
          {{
            $formatMessage({
              description: 'Server settings: maintenance section',
              defaultMessage: 'Server maintenance',
              id: 'fork/server/maintenance',
            })
          }}
        </v-card-title>
        <v-card-text class="d-flex flex-wrap ga-2">
          <v-btn
            color="warning"
            variant="tonal"
            prepend-icon="i-mdi:cancel"
            :loading="cancellingTasks"
            @click="cancelAllTasks"
          >
            {{ $formatMessage(cancelTasksLabel) }}
          </v-btn>
          <v-btn
            color="error"
            variant="tonal"
            prepend-icon="i-mdi:power"
            @click="shutdownDialog = true"
          >
            {{ $formatMessage(shutdownLabel) }}
          </v-btn>
        </v-card-text>
      </v-card>
    </template>

    <v-dialog
      v-model="shutdownDialog"
      max-width="440"
    >
      <v-card>
        <v-card-title>{{ $formatMessage(shutdownLabel) }}</v-card-title>
        <v-card-text>
          {{
            $formatMessage({
              description: 'Server settings: shutdown confirm',
              defaultMessage: 'The server will stop and must be restarted manually. Continue?',
              id: 'fork/server/shutdown/confirm',
            })
          }}
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="shutdownDialog = false">
            {{ $formatMessage(cancelLabel) }}
          </v-btn>
          <v-btn
            color="error"
            @click="shutdown"
          >
            {{ $formatMessage(shutdownLabel) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>

<script lang="ts" setup>
import { useSettings, useUpdateSettings } from '@/colada/settings'
import { commonMessages } from '@/utils/i18n/common-messages'

import { useMessagesStore } from '@/stores/messages'
import { komgaBooksRegenerateThumbnails, komgaEmptyTaskQueue } from '@/generated/openapi'
import { useMutation } from '@pinia/colada'
import { defineMessage } from 'vue-intl'
import { ApiBaseUrl } from '@/api/base'
import type { SettingsUpdateDtoExtended, ThumbnailRegenerate } from '@/types/ThumbnailRegenerate'

const messagesStore = useMessagesStore()

const loading = ref<boolean>(false)
const cancellingTasks = ref(false)
const shutdownDialog = ref(false)

async function cancelAllTasks() {
  cancellingTasks.value = true
  try {
    await komgaEmptyTaskQueue()
    messagesStore.messages.push({ message: tasksCancelledMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    cancellingTasks.value = false
  }
}

async function shutdown() {
  shutdownDialog.value = false
  try {
    await fetch(`${ApiBaseUrl.noSlash}/actuator/shutdown`, {
      method: 'POST',
      credentials: 'include',
    })
    messagesStore.messages.push({ message: shutdownStartedMessage })
  } catch {
    // the connection typically drops as the server stops — treat as initiated
    messagesStore.messages.push({ message: shutdownStartedMessage })
  }
}

const cancelTasksLabel = defineMessage({
  description: 'Server settings: cancel all tasks button',
  defaultMessage: 'Cancel all tasks',
  id: 'fork/server/cancelTasks',
})
const shutdownLabel = defineMessage({
  description: 'Server settings: shutdown button',
  defaultMessage: 'Shut down server',
  id: 'fork/server/shutdown',
})
const cancelLabel = defineMessage({
  description: 'Server settings: cancel',
  defaultMessage: 'Cancel',
  id: 'fork/server/cancel',
})
const tasksCancelledMessage = defineMessage({
  description: 'Server settings: tasks cancelled',
  defaultMessage: 'Task queue cleared',
  id: 'fork/server/tasksCancelled',
})
const shutdownStartedMessage = defineMessage({
  description: 'Server settings: shutdown started',
  defaultMessage: 'Server is shutting down',
  id: 'fork/server/shutdownStarted',
})

const { data: settings, error, isPending } = useSettings()
const { mutateAsync } = useUpdateSettings()

function saveSettings(settings: SettingsUpdateDtoExtended) {
  loading.value = true
  mutateAsync(settings)
    .then(() => {
      messagesStore.messages.push({
        description: 'Snackbar notification shown upon successful server settings update',
        defaultMessage: 'Settings updated',
        id: 'TL5bVZ',
      })

      regenerateThumbnails(settings.thumbnailRegenerate)
    })
    .catch((error) => {
      messagesStore.messages.push(error?.cause?.message ?? commonMessages.networkError)
    })
    .finally(() => {
      loading.value = false
    })
}

function regenerateThumbnails(regenerate: ThumbnailRegenerate) {
  if (regenerate === 'no') return
  const { mutate } = useMutation({
    mutation: () =>
      komgaBooksRegenerateThumbnails({
        query: {
          for_bigger_result_only: regenerate === 'bigger',
        },
      }),
  })
  void mutate()
}
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
