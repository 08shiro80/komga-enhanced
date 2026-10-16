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
            :model-value="forms[tracker.tracker]!.enabled"
            :label="$formatMessage(enabledMessage)"
            :loading="togglingTracker === tracker.tracker"
            :disabled="
              togglingTracker === tracker.tracker ||
              savingTracker === tracker.tracker ||
              authorizingTracker === tracker.tracker
            "
            color="primary"
            density="compact"
            hide-details
            @update:model-value="(v) => toggleEnabled(tracker, v === true)"
          />
        </v-card-title>

        <v-card-subtitle class="text-wrap">
          {{ $formatMessage(hintMessages[tracker.tracker] ?? hintMessages.anilist!) }}
        </v-card-subtitle>

        <v-card-text class="pb-0">
          <v-alert
            v-if="!tracker.scrobblerEnabled"
            type="info"
            variant="tonal"
            density="compact"
            class="mb-2"
            :text="
              $formatMessage(tracker.tracker === 'metron' ? comicDisabledMessage : mangaDisabledMessage)
            "
          />
          <v-alert
            v-if="tracker.status.state === 'reconnect_required'"
            type="warning"
            variant="tonal"
            density="compact"
            class="mb-2"
            :text="
              $formatMessage(
                tracker.authorizationRedirectUri ? reconnectRequiredMessage : credentialsRejectedMessage,
                { reason: tracker.status.message ?? '' },
              )
            "
          />
          <v-alert
            v-if="tracker.status.state === 'secrets_unreadable'"
            type="error"
            variant="tonal"
            density="compact"
            class="mb-2"
            :text="$formatMessage(secretsUnreadableMessage)"
          />
          <v-alert
            v-if="tokenExpiresSoon(tracker)"
            type="warning"
            variant="tonal"
            density="compact"
            class="mb-2"
            :text="
              $formatMessage(tokenExpiresSoonMessage, {
                date: formatDateTime(tracker.status.tokenExpiresAt),
              })
            "
          />
          <div
            v-if="tracker.linked"
            class="text-body-small"
          >
            <div v-if="tracker.status.accountName">
              {{ $formatMessage(accountNameMessage, { name: tracker.status.accountName }) }}
            </div>
            <div v-if="tracker.status.lastSuccessAt">
              {{
                $formatMessage(lastSuccessMessage, {
                  date: formatDateTime(tracker.status.lastSuccessAt),
                })
              }}
            </div>
            <div
              v-if="tracker.status.lastErrorAt"
              class="text-error"
            >
              {{
                $formatMessage(lastErrorMessage, {
                  date: formatDateTime(tracker.status.lastErrorAt),
                  error: tracker.status.lastError ?? '',
                })
              }}
            </div>
            <div v-if="tracker.status.tokenExpiresAt">
              {{
                $formatMessage(tokenExpiresMessage, {
                  date: formatDateTime(tracker.status.tokenExpiresAt),
                })
              }}
            </div>
          </div>
        </v-card-text>

        <v-card-text v-if="forms[tracker.tracker]">
          <v-text-field
            v-if="tracker.authorizationRedirectUri"
            :model-value="tracker.authorizationRedirectUri"
            :label="$formatMessage(redirectUriMessage)"
            :hint="$formatMessage(redirectUriHintMessage)"
            persistent-hint
            readonly
            density="compact"
            class="mb-4"
          />
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

        <v-card-actions class="flex-wrap ga-2">
          <v-btn
            color="primary"
            variant="elevated"
            :loading="savingTracker === tracker.tracker"
            :disabled="togglingTracker === tracker.tracker"
            :text="$formatMessage(saveMessage)"
            @click="save(tracker)"
          />
          <v-btn
            v-if="tracker.authorizationRedirectUri"
            color="primary"
            variant="tonal"
            :disabled="!tracker.settings.client_id"
            :loading="authorizingTracker === tracker.tracker"
            :text="$formatMessage(tracker.linked ? reconnectMessage : connectMessage)"
            @click="authorize(tracker)"
          />
          <v-btn
            :disabled="!tracker.linked"
            :loading="testingTracker === tracker.tracker"
            :text="$formatMessage(testMessage)"
            @click="test(tracker)"
          />
          <v-btn
            :disabled="!canSync(tracker)"
            :loading="syncingTracker === tracker.tracker"
            :text="$formatMessage(syncMessage)"
            @click="sync(tracker)"
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
import { isApiErrorWithCause } from '@/api/komga-client'
import {
  useAuthorizeTracker,
  useDeleteTracker,
  useSyncTracker,
  useTestTracker,
  useTrackers,
  useUpdateTracker,
} from '@/colada/trackers'
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
const route = useRoute()
const router = useRouter()

const { data: trackers, error, isLoading, refetch } = useTrackers()
const { mutateAsync: mutateUpdate } = useUpdateTracker()
const { mutateAsync: mutateDelete } = useDeleteTracker()
const { mutateAsync: mutateTest } = useTestTracker()
const { mutateAsync: mutateAuthorize } = useAuthorizeTracker()
const { mutateAsync: mutateSync } = useSyncTracker()

const tokenWarningDays = 14

const trackerNames: Record<string, string> = {
  anilist: 'AniList',
  mal: 'MyAnimeList',
  kitsu: 'Kitsu',
  mangadex: 'MangaDex',
  metron: 'Metron',
}

const clientFields: Array<TrackerField> = [
  { key: 'client_id' },
  { key: 'client_secret', secret: true },
]

const fields: Record<string, Array<TrackerField>> = {
  anilist: clientFields,
  mal: clientFields,
  kitsu: [{ key: 'username' }, { key: 'password', secret: true }],
  mangadex: [
    { key: 'username' },
    { key: 'password', secret: true },
    { key: 'client_id' },
    { key: 'client_secret', secret: true },
    { key: 'max_content_rating', options: ['safe', 'suggestive', 'erotica', 'pornographic'] },
  ],
  metron: [{ key: 'token', secret: true }, { key: 'username' }, { key: 'password', secret: true }],
}

const forms = ref<Record<string, TrackerForm>>({})
const savingTracker = ref<string>()
const testingTracker = ref<string>()
const authorizingTracker = ref<string>()
const togglingTracker = ref<string>()
const syncingTracker = ref<string>()
const unlinkTarget = ref<UserTrackerDto>()

function formatDateTime(value?: Date): string {
  return value ? intl.formatDate(new Date(value), { dateStyle: 'medium', timeStyle: 'short' }) : ''
}

function tokenExpiresSoon(tracker: UserTrackerDto): boolean {
  const expiry = tracker.status.tokenExpiresAt
  if (!expiry || !tracker.linked) return false
  return new Date(expiry).getTime() - Date.now() < tokenWarningDays * 24 * 60 * 60 * 1000
}

function canSync(tracker: UserTrackerDto): boolean {
  return (
    tracker.linked &&
    tracker.enabled &&
    tracker.scrobblerEnabled &&
    tracker.status.state !== 'reconnect_required' &&
    tracker.status.state !== 'secrets_unreadable'
  )
}

async function sync(tracker: UserTrackerDto) {
  syncingTracker.value = tracker.tracker
  try {
    const result = await mutateSync(tracker.tracker)
    messagesStore.messages.push({
      message: intl.formatMessage(syncQueuedMessage, { count: result.queued }),
    })
  } catch (e) {
    messagesStore.messages.push({
      message: (isApiErrorWithCause(e) ? e.cause.message : undefined) ?? (e as Error).message,
      color: 'error',
    })
  } finally {
    syncingTracker.value = undefined
  }
}

function formFrom(tracker: UserTrackerDto): TrackerForm {
  const values: Record<string, string> = {}
  for (const field of fields[tracker.tracker] ?? []) {
    values[field.key] = field.secret ? '' : (tracker.settings[field.key] ?? '')
  }
  return { enabled: tracker.enabled, values }
}

watchImmediate(trackers, (list) => {
  for (const tracker of list ?? []) {
    if (!forms.value[tracker.tracker]) forms.value[tracker.tracker] = formFrom(tracker)
  }
})

async function resetForm(trackerId: string) {
  await refetch()
  const current = trackers.value?.find((t) => t.tracker === trackerId)
  if (current) forms.value[trackerId] = formFrom(current)
}

async function refreshEnabled(trackerId: string) {
  await refetch()
  const current = trackers.value?.find((t) => t.tracker === trackerId)
  const form = forms.value[trackerId]
  if (current && form) form.enabled = current.enabled
}

async function toggleEnabled(tracker: UserTrackerDto, enabled: boolean) {
  const form = forms.value[tracker.tracker]
  if (!form) return
  form.enabled = enabled
  togglingTracker.value = tracker.tracker
  try {
    await mutateUpdate({ tracker: tracker.tracker, update: { enabled } })
    await refreshEnabled(tracker.tracker)
  } catch (e) {
    form.enabled = !enabled
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    togglingTracker.value = undefined
  }
}

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
    await resetForm(tracker.tracker)
    messagesStore.messages.push({ message: intl.formatMessage(savedMessage) })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    savingTracker.value = undefined
  }
}

async function authorize(tracker: UserTrackerDto) {
  authorizingTracker.value = tracker.tracker
  try {
    const result = await mutateAuthorize({
      tracker: tracker.tracker,
      returnTo: router.resolve(`/next${route.path}`).href,
    })
    window.location.assign(result.authorizationUrl)
  } catch (e) {
    messagesStore.messages.push({
      message: (isApiErrorWithCause(e) ? e.cause.message : undefined) ?? (e as Error).message,
      color: 'error',
    })
    authorizingTracker.value = undefined
  }
}

onMounted(() => {
  const { trackerAuth, trackerAuthResult, trackerAuthError, ...query } = route.query
  if (!trackerAuth) return
  const name = trackerNames[String(trackerAuth)] ?? String(trackerAuth)
  if (trackerAuthResult === 'success') {
    messagesStore.messages.push({
      message: intl.formatMessage(authorizedMessage, { name }),
      color: 'success',
    })
  } else {
    const reason = authorizationErrorMessages[String(trackerAuthError)] ?? authorizationFailedMessage
    messagesStore.messages.push({
      message: intl.formatMessage(reason, { name }),
      color: 'error',
    })
  }
  void router.replace({ query })
})

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
    await resetForm(target.tracker)
    messagesStore.messages.push({ message: intl.formatMessage(unlinkedMessage) })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const mangaDisabledMessage = defineMessage({
  description: 'Account trackers: the manga scrobbler plugin is disabled',
  defaultMessage:
    'The administrator has not enabled the Manga Scrobbler plugin, nothing is synced to this tracker at the moment.',
  id: 'fork/account/trackers/mangaScrobblerDisabled',
})
const comicDisabledMessage = defineMessage({
  description: 'Account trackers: the comic scrobbler plugin is disabled',
  defaultMessage:
    'The administrator has not enabled the Comic Scrobbler plugin, nothing is synced to Metron at the moment.',
  id: 'fork/account/trackers/comicScrobblerDisabled',
})
const reconnectRequiredMessage = defineMessage<{ reason: string }>({
  description: 'Account trackers: the provider login expired or was revoked',
  defaultMessage: 'The login is no longer valid, click Connect again. {reason}',
  id: 'fork/account/trackers/reconnectRequired',
})
const credentialsRejectedMessage = defineMessage<{ reason: string }>({
  description: 'Account trackers: the stored credentials were rejected',
  defaultMessage: 'The tracker rejected the saved login, correct it and save. {reason}',
  id: 'fork/account/trackers/credentialsRejected',
})
const secretsUnreadableMessage = defineMessage({
  description: 'Account trackers: stored credentials cannot be decrypted',
  defaultMessage: 'The saved credentials cannot be read anymore (missing encryption key). Link the account again.',
  id: 'fork/account/trackers/secretsUnreadable',
})
const tokenExpiresSoonMessage = defineMessage<{ date: string }>({
  description: 'Account trackers: the provider token expires soon',
  defaultMessage: 'The login expires on {date}. Click Connect again to renew it.',
  id: 'fork/account/trackers/tokenExpiresSoon',
})
const tokenExpiresMessage = defineMessage<{ date: string }>({
  description: 'Account trackers: provider token expiry',
  defaultMessage: 'Login valid until {date}',
  id: 'fork/account/trackers/tokenExpires',
})
const accountNameMessage = defineMessage<{ name: string }>({
  description: 'Account trackers: connected remote account',
  defaultMessage: 'Account: {name}',
  id: 'fork/account/trackers/accountName',
})
const lastSuccessMessage = defineMessage<{ date: string }>({
  description: 'Account trackers: last successful sync or test',
  defaultMessage: 'Last success: {date}',
  id: 'fork/account/trackers/lastSuccess',
})
const lastErrorMessage = defineMessage<{ date: string; error: string }>({
  description: 'Account trackers: last error',
  defaultMessage: 'Last error ({date}): {error}',
  id: 'fork/account/trackers/lastError',
})
const syncMessage = defineMessage({
  description: 'Account trackers: sync now button',
  defaultMessage: 'Sync now',
  id: 'fork/account/trackers/sync',
})
const syncQueuedMessage = defineMessage<{ count: number }>({
  description: 'Account trackers: sync queued notification',
  defaultMessage: '{count, plural, one {# series queued for sync} other {# series queued for sync}}',
  id: 'fork/account/trackers/syncQueued',
})
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

const connectMessage = defineMessage({
  description: 'Account trackers: connect button for trackers with provider login',
  defaultMessage: 'Connect',
  id: 'fork/account/trackers/connect',
})
const reconnectMessage = defineMessage({
  description: 'Account trackers: connect button when the account is already linked',
  defaultMessage: 'Connect again',
  id: 'fork/account/trackers/reconnect',
})
const redirectUriMessage = defineMessage({
  description: 'Account trackers: redirect URL field',
  defaultMessage: 'Redirect URL',
  id: 'fork/account/trackers/redirectUri',
})
const redirectUriHintMessage = defineMessage({
  description: 'Account trackers: redirect URL field hint',
  defaultMessage: 'Register exactly this URL in your API client at the provider.',
  id: 'fork/account/trackers/redirectUriHint',
})
const authorizedMessage = defineMessage<{ name: string }>({
  description: 'Account trackers: provider login succeeded',
  defaultMessage: '{name} account connected',
  id: 'fork/account/trackers/authorized',
})
const authorizationFailedMessage = defineMessage<{ name: string }>({
  description: 'Account trackers: provider login failed for an unknown reason',
  defaultMessage: 'Connecting the {name} account failed',
  id: 'fork/account/trackers/authorizationFailed',
})

const authorizationErrorMessages: Record<string, typeof authorizationFailedMessage> = {
  denied: defineMessage<{ name: string }>({
    description: 'Account trackers: provider login was cancelled or denied',
    defaultMessage: 'The {name} login was cancelled or access was denied',
    id: 'fork/account/trackers/authorizationError/denied',
  }),
  missing_code: defineMessage<{ name: string }>({
    description: 'Account trackers: provider returned no authorization code',
    defaultMessage: '{name} returned no authorization code',
    id: 'fork/account/trackers/authorizationError/missingCode',
  }),
  exchange_failed: defineMessage<{ name: string }>({
    description: 'Account trackers: exchanging the authorization code failed',
    defaultMessage:
      '{name} rejected the login. Check the client ID, the client secret and the registered redirect URL. Details are in the server log.',
    id: 'fork/account/trackers/authorizationError/exchangeFailed',
  }),
  not_configured: defineMessage<{ name: string }>({
    description: 'Account trackers: client settings were removed during the provider login',
    defaultMessage: 'The {name} client settings were removed during the login',
    id: 'fork/account/trackers/authorizationError/notConfigured',
  }),
  session_expired: defineMessage<{ name: string }>({
    description: 'Account trackers: the Komga session expired during the provider login',
    defaultMessage: 'Your Komga session expired during the {name} login. Click Connect again.',
    id: 'fork/account/trackers/authorizationError/sessionExpired',
  }),
  invalid_state: defineMessage<{ name: string }>({
    description: 'Account trackers: the provider login request is unknown or expired',
    defaultMessage: 'The {name} login took too long or was already used. Click Connect again.',
    id: 'fork/account/trackers/authorizationError/invalidState',
  }),
  state_mismatch: defineMessage<{ name: string }>({
    description: 'Account trackers: the provider login belongs to another user',
    defaultMessage: 'The {name} login was started by another Komga user and was not applied.',
    id: 'fork/account/trackers/authorizationError/stateMismatch',
  }),
  storage_failed: defineMessage<{ name: string }>({
    description: 'Account trackers: tokens could not be stored encrypted',
    defaultMessage: 'The {name} tokens could not be stored. Details are in the server log.',
    id: 'fork/account/trackers/authorizationError/storageFailed',
  }),
  unsupported_tracker: defineMessage<{ name: string }>({
    description: 'Account trackers: the callback was opened for a tracker without provider login',
    defaultMessage: '{name} does not use a provider login. Enter the login details on this page instead.',
    id: 'fork/account/trackers/authorizationError/unsupportedTracker',
  }),
  rate_limited: defineMessage<{ name: string }>({
    description: 'Account trackers: the token exchange was stopped by the request limit',
    defaultMessage: 'Too many requests to {name} at the moment, try again in a few minutes.',
    id: 'fork/account/trackers/authorizationError/rateLimited',
  }),
}

const fieldMessages: Record<string, MessageDescriptor> = {
  token: defineMessage({
    description: 'Account trackers: Metron API token field',
    defaultMessage: 'API token',
    id: 'fork/account/trackers/field/token',
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
      'On anilist.co/settings/developer choose Create New Application and enter the redirect URL shown below. Save the client ID and client secret here, then click Connect.',
    id: 'fork/account/trackers/hint/anilist',
  }),
  mal: defineMessage({
    description: 'Account trackers: MyAnimeList hint',
    defaultMessage:
      'On myanimelist.net/apiconfig create an API client and enter the redirect URL shown below as App Redirect URL. Save the client ID (and the client secret of web apps) here, then click Connect.',
    id: 'fork/account/trackers/hint/mal',
  }),
  kitsu: defineMessage({
    description: 'Account trackers: Kitsu hint',
    defaultMessage:
      'Your Kitsu login (email or username and password). Komga signs in to Kitsu itself and renews the session automatically.',
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
    defaultMessage:
      'API token from your metron.cloud profile (Profile → API Tokens). Username and password are only a fallback. Used by the Comic Scrobbler.',
    id: 'fork/account/trackers/hint/metron',
  }),
}
</script>
