<template>
  <div>
    <div class="d-flex align-center flex-wrap ga-2 mb-3">
      <v-switch
        v-model="form.enabled"
        color="primary"
        density="compact"
        hide-details
        :label="$formatMessage(enabledLabel)"
      />
      <v-spacer />
      <v-chip
        size="small"
        :color="mdPlugin?.enabled ? 'success' : 'grey'"
        variant="tonal"
      >
        {{
          $formatMessage(
            {
              description: 'Follow schedule: mangadex subscription plugin status',
              defaultMessage: 'MangaDex subscription: {state}',
              id: 'fork/schedule/plugin-status',
            },
            {
              state: mdPlugin?.enabled
                ? $formatMessage(onLabel)
                : $formatMessage(offLabel),
            },
          )
        }}
      </v-chip>
    </div>

    <v-row dense>
      <v-col
        cols="12"
        sm="4"
      >
        <v-select
          v-model="form.scheduleMode"
          :items="modeItems"
          :label="$formatMessage(modeLabel)"
          variant="outlined"
          density="compact"
          hide-details
          :disabled="!form.enabled"
        />
      </v-col>
      <v-col
        v-if="form.scheduleMode === 'interval'"
        cols="12"
        sm="4"
      >
        <v-text-field
          v-model.number="form.intervalHours"
          type="number"
          :label="$formatMessage(intervalLabel)"
          variant="outlined"
          density="compact"
          hide-details
          :disabled="!form.enabled"
        />
      </v-col>
      <v-col
        v-else
        cols="12"
        sm="4"
      >
        <v-text-field
          v-model="form.checkTime"
          :label="$formatMessage(checkTimeLabel)"
          placeholder="03:00"
          variant="outlined"
          density="compact"
          hide-details
          :disabled="!form.enabled"
        />
      </v-col>
    </v-row>

    <div class="d-flex align-center flex-wrap ga-2 mt-3">
      <v-btn
        color="primary"
        :loading="saving"
        :disabled="!libraryId"
        @click="save()"
      >
        {{ $formatMessage(saveLabel) }}
      </v-btn>
      <v-btn
        variant="text"
        prepend-icon="i-mdi:sync"
        :loading="syncing"
        :disabled="!libraryId"
        @click="syncNow()"
      >
        {{ $formatMessage(syncLabel) }}
      </v-btn>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import {
  useFollowSchedule,
  useSyncFollowsToMangaDex,
  useUpdateFollowSchedule,
} from '@/colada/follows'
import { usePlugin } from '@/colada/plugins'
import { useMessagesStore } from '@/stores/messages'

const props = defineProps<{ libraryId?: string }>()

const messagesStore = useMessagesStore()

const libraryIdRef = toRef(props, 'libraryId')
const { data: schedule } = useFollowSchedule(libraryIdRef)
const { data: mdPlugin } = usePlugin('mangadex-subscription')

const { mutateAsync: updateSchedule, isLoading: saving } = useUpdateFollowSchedule()
const { mutateAsync: syncFollows, isLoading: syncing } = useSyncFollowsToMangaDex()

const form = ref({
  enabled: false,
  scheduleMode: 'interval',
  intervalHours: 24,
  checkTime: '03:00',
})

watch(
  schedule,
  (s) => {
    if (s) {
      form.value = {
        enabled: s.enabled,
        scheduleMode: s.scheduleMode || 'interval',
        intervalHours: s.intervalHours || 24,
        checkTime: s.checkTime || '03:00',
      }
    }
  },
  { immediate: true },
)

async function save() {
  if (!props.libraryId) return
  try {
    await updateSchedule({
      libraryId: props.libraryId,
      body: {
        enabled: form.value.enabled,
        scheduleMode: form.value.scheduleMode,
        intervalHours: form.value.intervalHours,
        checkTime: form.value.scheduleMode === 'fixed_time' ? form.value.checkTime : undefined,
      },
    })
    messagesStore.messages.push({ message: savedMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function syncNow() {
  if (!props.libraryId) return
  try {
    await syncFollows(props.libraryId)
    messagesStore.messages.push({ message: syncedMessage })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const modeItems = [
  { title: 'Interval', value: 'interval' },
  { title: 'Fixed time', value: 'fixed_time' },
]

const enabledLabel = defineMessage({
  description: 'Follow schedule: enabled switch',
  defaultMessage: 'Automatic follow checks',
  id: 'fork/schedule/enabled',
})
const modeLabel = defineMessage({
  description: 'Follow schedule: mode select',
  defaultMessage: 'Schedule mode',
  id: 'fork/schedule/mode',
})
const intervalLabel = defineMessage({
  description: 'Follow schedule: interval hours',
  defaultMessage: 'Every N hours',
  id: 'fork/schedule/interval',
})
const checkTimeLabel = defineMessage({
  description: 'Follow schedule: fixed check time',
  defaultMessage: 'Check time (HH:MM)',
  id: 'fork/schedule/checkTime',
})
const saveLabel = defineMessage({
  description: 'Follow schedule: save button',
  defaultMessage: 'Save schedule',
  id: 'fork/schedule/save',
})
const syncLabel = defineMessage({
  description: 'Follow schedule: sync to mangadex button',
  defaultMessage: 'Sync follows to MangaDex',
  id: 'fork/schedule/sync',
})
const savedMessage = defineMessage({
  description: 'Follow schedule: saved confirmation',
  defaultMessage: 'Schedule saved',
  id: 'fork/schedule/saved',
})
const syncedMessage = defineMessage({
  description: 'Follow schedule: synced confirmation',
  defaultMessage: 'Follows synced to MangaDex',
  id: 'fork/schedule/synced',
})
const onLabel = defineMessage({
  description: 'Follow schedule: plugin on',
  defaultMessage: 'on',
  id: 'fork/schedule/on',
})
const offLabel = defineMessage({
  description: 'Follow schedule: plugin off',
  defaultMessage: 'off',
  id: 'fork/schedule/off',
})
</script>
