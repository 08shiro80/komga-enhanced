<template>
  <v-container
    fluid
    class="pa-0 pa-sm-4"
  >
    <EmptyStateNetworkError v-if="error" />

    <template v-else>
      <p class="text-body-medium mb-4">{{ $formatMessage(subtitleMessage) }}</p>

      <v-progress-linear
        v-if="isLoading"
        indeterminate
      />

      <v-card
        v-for="tracker in trackers"
        :key="tracker.tracker"
        variant="outlined"
        class="mb-4"
        max-width="800px"
      >
        <v-card-title class="d-flex align-center flex-wrap ga-2">
          {{ trackerNames[tracker.tracker] ?? tracker.tracker }}
          <v-chip
            size="small"
            :color="tracker.linked ? 'success' : undefined"
            :variant="tracker.linked ? 'tonal' : 'outlined'"
          >
            {{ $formatMessage(tracker.linked ? linkedMessage : notLinkedMessage) }}
          </v-chip>
          <v-spacer />
          <v-switch
            v-if="forms[tracker.tracker]"
            v-model="forms[tracker.tracker]!.enabled"
            :label="$formatMessage(enabledMessage)"
            color="primary"
            density="compact"
            hide-details
          />
        </v-card-title>

        <v-card-subtitle class="text-wrap">
          {{ $formatMessage(hintMessages[tracker.tracker] ?? hintMessages.anilist!) }}
        </v-card-subtitle>

        <v-card-text v-if="forms[tracker.tracker]">
          <template
            v-for="field in fields[tracker.tracker] ?? []"
            :key="field.key"
          >
            <v-select
              v-if="field.options"
              v-model="forms[tracker.tracker]!.values[field.key]"
              :items="field.options"
              :label="$formatMessage(fieldMessages[field.key]!)"
              clearable
              density="compact"
            />
            <v-text-field
              v-else
              v-model="forms[tracker.tracker]!.values[field.key]"
              :label="$formatMessage(fieldMessages[field.key]!)"
              :type="field.secret ? 'password' : 'text'"
              :placeholder="
                field.secret && tracker.secretsSet.includes(field.key)
                  ? $formatMessage(secretSetMessage)
                  : undefined
              "
              persistent-placeholder
              autocomplete="off"
              density="compact"
            />
          </template>
        </v-card-text>

        <v-card-actions>
          <v-btn
            color="primary"
            variant="elevated"
            :loading="savingTracker === tracker.tracker"
            :text="$formatMessage(saveMessage)"
            @click="save(tracker)"
          />
          <v-btn
            :disabled="!tracker.linked"
            :loading="testingTracker === tracker.tracker"
            :text="$formatMessage(testMessage)"
            @click="test(tracker)"
          />
          <v-spacer />
          <v-btn
            color="error"
            :disabled="!tracker.linked && Object.keys(tracker.settings).length === 0"
            :text="$formatMessage(unlinkMessage)"
            @click="unlinkTarget = tracker"
          />
        </v-card-actions>
      </v-card>
    </template>

    <v-dialog
      :model-value="unlinkTarget !== undefined"
      max-width="440"
      @update:model-value="(v) => (v ? undefined : (unlinkTarget = undefined))"
    >
      <v-card>
        <v-card-title>{{ $formatMessage(unlinkMessage) }}</v-card-title>
        <v-card-text>
          {{
            $formatMessage(confirmUnlinkMessage, {
              name: unlinkTarget
                ? (trackerNames[unlinkTarget.tracker] ?? unlinkTarget.tracker)
                : '',
            })
          }}
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn
            :text="$formatMessage(cancelMessage)"
            @click="unlinkTarget = undefined"
          />
          <v-btn
            color="error"
            :text="$formatMessage(unlinkMessage)"
            @click="unlink"
          />
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>

<script lang="ts" setup>
import { defineMessage, type MessageDescriptor, useIntl } from 'vue-intl'
import EmptyStateNetworkError from '@/components/EmptyStateNetworkError.vue'
import { useDeleteTracker, useTestTracker, useTrackers, useUpdateTracker } from '@/colada/trackers'
import type { UserTrackerDto } from '@/generated/openapi'
import { useMessagesStore } from '@/stores/messages'
import { watchImmediate } from '@vueuse/core'

interface TrackerField {
  key: string
  secret?: boolean
  options?: Array<string>
}

interface TrackerForm {
  enabled: boolean
  values: Record<string, string>
}

const intl = useIntl()
const messagesStore = useMessagesStore()

const { data: trackers, error, isLoading } = useTrackers()
const { mutateAsync: mutateUpdate } = useUpdateTracker()
const { mutateAsync: mutateDelete } = useDeleteTracker()
const { mutateAsync: mutateTest } = useTestTracker()

const trackerNames: Record<string, string> = {
  anilist: 'AniList',
  mal: 'MyAnimeList',
  kitsu: 'Kitsu',
  mangadex: 'MangaDex',
  metron: 'Metron',
}

const oauthFields: Array<TrackerField> = [
  { key: 'access_token', secret: true },
  { key: 'refresh_token', secret: true },
  { key: 'client_id' },
  { key: 'client_secret', secret: true },
]

const fields: Record<string, Array<TrackerField>> = {
  anilist: [{ key: 'token', secret: true }],
  mal: oauthFields,
  kitsu: oauthFields,
  mangadex: [
    { key: 'username' },
    { key: 'password', secret: true },
    { key: 'client_id' },
    { key: 'client_secret', secret: true },
    { key: 'max_content_rating', options: ['safe', 'suggestive', 'erotica', 'pornographic'] },
  ],
  metron: [{ key: 'username' }, { key: 'password', secret: true }],
}

const forms = ref<Record<string, TrackerForm>>({})
const savingTracker = ref<string>()
const testingTracker = ref<string>()
const unlinkTarget = ref<UserTrackerDto>()

watchImmediate(trackers, (list) => {
  const result: Record<string, TrackerForm> = {}
  for (const tracker of list ?? []) {
    const values: Record<string, string> = {}
    for (const field of fields[tracker.tracker] ?? []) {
      values[field.key] = field.secret ? '' : (tracker.settings[field.key] ?? '')
    }
    result[tracker.tracker] = { enabled: tracker.enabled, values }
  }
  forms.value = result
})

async function save(tracker: UserTrackerDto) {
  const form = forms.value[tracker.tracker]
  if (!form) return
  const settings: Record<string, string> = {}
  for (const field of fields[tracker.tracker] ?? []) {
    const value = form.values[field.key] ?? ''
    if (field.secret) {
      if (value) settings[field.key] = value
    } else if (value !== (tracker.settings[field.key] ?? '')) {
      settings[field.key] = value
    }
  }
  savingTracker.value = tracker.tracker
  try {
    await mutateUpdate({ tracker: tracker.tracker, update: { enabled: form.enabled, settings } })
    messagesStore.messages.push({ message: intl.formatMessage(savedMessage) })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    savingTracker.value = undefined
  }
}

async function test(tracker: UserTrackerDto) {
  testingTracker.value = tracker.tracker
  try {
    const result = await mutateTest(tracker.tracker)
    messagesStore.messages.push({
      message: result.message,
      color: result.success ? 'success' : 'error',
    })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    testingTracker.value = undefined
  }
}

async function unlink() {
  const target = unlinkTarget.value
  unlinkTarget.value = undefined
  if (!target) return
  try {
    await mutateDelete(target.tracker)
    messagesStore.messages.push({ message: intl.formatMessage(unlinkedMessage) })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const subtitleMessage = defineMessage({
  description: 'Account trackers: page explanation',
  defaultMessage:
    'Link your own tracker accounts. When you finish a book, your progress is synced only to the accounts linked here. Scrobbling must be enabled by an administrator (Manga Scrobbler / Comic Scrobbler plugin).',
  id: 'fork/account/trackers/subtitle',
})
const linkedMessage = defineMessage({
  description: 'Account trackers: linked chip',
  defaultMessage: 'Linked',
  id: 'fork/account/trackers/linked',
})
const notLinkedMessage = defineMessage({
  description: 'Account trackers: not linked chip',
  defaultMessage: 'Not linked',
  id: 'fork/account/trackers/notLinked',
})
const enabledMessage = defineMessage({
  description: 'Account trackers: active switch',
  defaultMessage: 'Active',
  id: 'fork/account/trackers/enabled',
})
const saveMessage = defineMessage({
  description: 'Account trackers: save button',
  defaultMessage: 'Save',
  id: 'fork/account/trackers/save',
})
const testMessage = defineMessage({
  description: 'Account trackers: test connection button',
  defaultMessage: 'Test connection',
  id: 'fork/account/trackers/test',
})
const unlinkMessage = defineMessage({
  description: 'Account trackers: unlink button',
  defaultMessage: 'Unlink',
  id: 'fork/account/trackers/unlink',
})
const savedMessage = defineMessage({
  description: 'Account trackers: saved notification',
  defaultMessage: 'Tracker saved',
  id: 'fork/account/trackers/saved',
})
const unlinkedMessage = defineMessage({
  description: 'Account trackers: unlinked notification',
  defaultMessage: 'Tracker unlinked',
  id: 'fork/account/trackers/unlinked',
})
const secretSetMessage = defineMessage({
  description: 'Account trackers: placeholder for a stored secret',
  defaultMessage: 'Saved — leave empty to keep',
  id: 'fork/account/trackers/secretSet',
})
const cancelMessage = defineMessage({
  description: 'Account trackers: cancel button',
  defaultMessage: 'Cancel',
  id: 'fork/account/trackers/cancel',
})
const confirmUnlinkMessage = defineMessage<{ name: string }>({
  description: 'Account trackers: unlink confirmation',
  defaultMessage: 'Remove your {name} account and its saved credentials?',
  id: 'fork/account/trackers/confirmUnlink',
})

const fieldMessages: Record<string, MessageDescriptor> = {
  token: defineMessage({
    description: 'Account trackers: token field',
    defaultMessage: 'Access token',
    id: 'fork/account/trackers/field/token',
  }),
  access_token: defineMessage({
    description: 'Account trackers: access token field',
    defaultMessage: 'Access token',
    id: 'fork/account/trackers/field/accessToken',
  }),
  refresh_token: defineMessage({
    description: 'Account trackers: refresh token field',
    defaultMessage: 'Refresh token',
    id: 'fork/account/trackers/field/refreshToken',
  }),
  client_id: defineMessage({
    description: 'Account trackers: client id field',
    defaultMessage: 'Client ID',
    id: 'fork/account/trackers/field/clientId',
  }),
  client_secret: defineMessage({
    description: 'Account trackers: client secret field',
    defaultMessage: 'Client secret',
    id: 'fork/account/trackers/field/clientSecret',
  }),
  username: defineMessage({
    description: 'Account trackers: username field',
    defaultMessage: 'Username',
    id: 'fork/account/trackers/field/username',
  }),
  password: defineMessage({
    description: 'Account trackers: password field',
    defaultMessage: 'Password',
    id: 'fork/account/trackers/field/password',
  }),
  max_content_rating: defineMessage({
    description: 'Account trackers: MangaDex max content rating field',
    defaultMessage: 'Max content rating to sync',
    id: 'fork/account/trackers/field/maxContentRating',
  }),
}

const hintMessages: Record<string, MessageDescriptor> = {
  anilist: defineMessage({
    description: 'Account trackers: AniList hint',
    defaultMessage:
      'Personal access token from anilist.co/api/v2/oauth/pin (implicit grant of your own AniList API client).',
    id: 'fork/account/trackers/hint/anilist',
  }),
  mal: defineMessage({
    description: 'Account trackers: MyAnimeList hint',
    defaultMessage:
      'OAuth2 tokens of your own MyAnimeList API client. With refresh token, client ID and client secret the access token is renewed automatically.',
    id: 'fork/account/trackers/hint/mal',
  }),
  kitsu: defineMessage({
    description: 'Account trackers: Kitsu hint',
    defaultMessage:
      'OAuth2 tokens from kitsu.app/api/oauth/token. With refresh token, client ID and client secret the access token is renewed automatically.',
    id: 'fork/account/trackers/hint/kitsu',
  }),
  mangadex: defineMessage({
    description: 'Account trackers: MangaDex hint',
    defaultMessage:
      'Your MangaDex account and personal API client (mangadex.org/settings → API Clients).',
    id: 'fork/account/trackers/hint/mangadex',
  }),
  metron: defineMessage({
    description: 'Account trackers: Metron hint',
    defaultMessage: 'Your Metron (metron.cloud) account. Used by the Comic Scrobbler.',
    id: 'fork/account/trackers/hint/metron',
  }),
}
</script>
