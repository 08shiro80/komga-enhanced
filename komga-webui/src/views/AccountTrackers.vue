<template>
  <v-container fluid class="pa-6">
    <v-row>
      <v-col cols="12" md="10" lg="8" xl="6">
        <div class="text-body-2 mb-4">{{ $t('account_settings.trackers.subtitle') }}</div>

        <v-card
          v-for="tracker in trackers"
          :key="tracker.tracker"
          outlined
          class="mb-4"
        >
          <v-card-title>
            {{ trackerNames[tracker.tracker] }}
            <v-chip
              small
              class="ms-3"
              :color="tracker.linked ? 'success' : undefined"
              :outlined="!tracker.linked"
            >
              {{ tracker.linked ? $t('account_settings.trackers.linked') : $t('account_settings.trackers.not_linked') }}
            </v-chip>
            <v-spacer/>
            <v-switch
              :input-value="forms[tracker.tracker].enabled"
              :label="$t('account_settings.trackers.enabled')"
              :loading="toggling === tracker.tracker"
              :disabled="toggling === tracker.tracker || busy === tracker.tracker || authorizing === tracker.tracker"
              hide-details
              class="mt-0"
              @change="toggleEnabled(tracker, $event === true)"
            />
          </v-card-title>

          <v-card-subtitle>{{ $t(`account_settings.trackers.hint_${tracker.tracker}`) }}</v-card-subtitle>

          <v-card-text class="pb-0">
            <v-alert v-if="!tracker.scrobblerEnabled" type="info" text dense class="mb-2">
              {{ tracker.tracker === 'metron' ? $t('account_settings.trackers.comic_scrobbler_disabled') : $t('account_settings.trackers.manga_scrobbler_disabled') }}
            </v-alert>
            <v-alert v-if="tracker.status.state === 'reconnect_required'" type="warning" text dense class="mb-2">
              {{ tracker.authorizationRedirectUri ? $t('account_settings.trackers.reconnect_required', {reason: tracker.status.message || ''}) : $t('account_settings.trackers.credentials_rejected', {reason: tracker.status.message || ''}) }}
            </v-alert>
            <v-alert v-if="tracker.status.state === 'secrets_unreadable'" type="error" text dense class="mb-2">
              {{ $t('account_settings.trackers.secrets_unreadable') }}
            </v-alert>
            <v-alert v-if="tokenExpiresSoon(tracker)" type="warning" text dense class="mb-2">
              {{ $t('account_settings.trackers.token_expires_soon', {date: formatDateTime(tracker.status.tokenExpiresAt)}) }}
            </v-alert>
            <div v-if="tracker.linked" class="text-caption">
              <div v-if="tracker.status.accountName">{{ $t('account_settings.trackers.account_name', {name: tracker.status.accountName}) }}</div>
              <div v-if="tracker.status.lastSuccessAt">{{ $t('account_settings.trackers.last_success', {date: formatDateTime(tracker.status.lastSuccessAt)}) }}</div>
              <div v-if="tracker.status.lastErrorAt" class="error--text">
                {{ $t('account_settings.trackers.last_error', {date: formatDateTime(tracker.status.lastErrorAt), error: tracker.status.lastError || ''}) }}
              </div>
              <div v-if="tracker.status.tokenExpiresAt">{{ $t('account_settings.trackers.token_expires', {date: formatDateTime(tracker.status.tokenExpiresAt)}) }}</div>
            </div>
          </v-card-text>

          <v-card-text>
            <v-text-field
              v-if="tracker.authorizationRedirectUri"
              :value="tracker.authorizationRedirectUri"
              :label="$t('account_settings.trackers.redirect_uri')"
              :hint="$t('account_settings.trackers.redirect_uri_hint')"
              persistent-hint
              readonly
              dense
              class="mb-4"
            />
            <template v-for="field in fields[tracker.tracker]">
              <v-select
                v-if="field.options"
                :key="field.key"
                v-model="forms[tracker.tracker].values[field.key]"
                :items="field.options"
                :label="$t(`account_settings.trackers.field_${field.key}`)"
                clearable
                dense
              />
              <v-text-field
                v-else
                :key="field.key"
                v-model="forms[tracker.tracker].values[field.key]"
                :label="$t(`account_settings.trackers.field_${field.key}`)"
                :type="field.secret ? 'password' : 'text'"
                :placeholder="field.secret && tracker.secretsSet.includes(field.key) ? $t('account_settings.trackers.secret_set') : ''"
                persistent-placeholder
                autocomplete="off"
                dense
              />
            </template>
          </v-card-text>

          <v-card-actions class="flex-wrap">
            <v-btn
              color="primary"
              :loading="busy === tracker.tracker"
              :disabled="toggling === tracker.tracker"
              @click="save(tracker)"
            >{{ $t('account_settings.trackers.save') }}</v-btn>
            <v-btn
              v-if="tracker.authorizationRedirectUri"
              color="primary"
              outlined
              :disabled="!tracker.settings.client_id"
              :loading="authorizing === tracker.tracker"
              @click="authorize(tracker)"
            >{{ tracker.linked ? $t('account_settings.trackers.reconnect') : $t('account_settings.trackers.connect') }}</v-btn>
            <v-btn
              text
              :disabled="!tracker.linked"
              :loading="testing === tracker.tracker"
              @click="test(tracker)"
            >{{ $t('account_settings.trackers.test') }}</v-btn>
            <v-btn
              text
              :disabled="!canSync(tracker)"
              :loading="syncing === tracker.tracker"
              @click="sync(tracker)"
            >{{ $t('account_settings.trackers.sync') }}</v-btn>
            <v-spacer/>
            <v-btn
              text
              color="error"
              :disabled="!tracker.linked && Object.keys(tracker.settings).length === 0"
              @click="unlinkTarget = tracker; modalConfirmUnlink = true"
            >{{ $t('account_settings.trackers.unlink') }}</v-btn>
          </v-card-actions>
        </v-card>
      </v-col>
    </v-row>

    <confirmation-dialog
      v-model="modalConfirmUnlink"
      :title="$t('account_settings.trackers.unlink')"
      :body="$t('account_settings.trackers.confirm_unlink', {name: unlinkTarget ? trackerNames[unlinkTarget.tracker] : ''})"
      :button-confirm="$t('account_settings.trackers.unlink')"
      button-confirm-color="error"
      @confirm="unlink(unlinkTarget)"
    />

    <v-snackbar v-model="snackbar" :color="snackbarColor" :timeout="4000" bottom>
      {{ snackbarText }}
    </v-snackbar>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import {UserTrackerDto} from '@/types/komga-trackers'
import ConfirmationDialog from '@/components/dialogs/ConfirmationDialog.vue'
import urls from '@/functions/urls'

interface TrackerField {
  key: string,
  secret?: boolean,
  options?: string[],
}

interface TrackerForm {
  enabled: boolean,
  values: Record<string, string | null>,
}

const clientFields: TrackerField[] = [
  {key: 'client_id'},
  {key: 'client_secret', secret: true},
]

const authorizationErrors = ['denied', 'missing_code', 'exchange_failed', 'not_configured', 'storage_failed', 'session_expired', 'invalid_state', 'state_mismatch', 'unsupported_tracker', 'rate_limited']

const tokenWarningMillis = 14 * 24 * 60 * 60 * 1000

export default Vue.extend({
  name: 'AccountTrackers',
  components: {ConfirmationDialog},
  data: () => ({
    trackers: [] as UserTrackerDto[],
    forms: {} as Record<string, TrackerForm>,
    busy: null as string | null,
    testing: null as string | null,
    authorizing: null as string | null,
    toggling: null as string | null,
    syncing: null as string | null,
    modalConfirmUnlink: false,
    unlinkTarget: null as UserTrackerDto | null,
    snackbar: false,
    snackbarText: '',
    snackbarColor: 'success',
    trackerNames: {
      anilist: 'AniList',
      mal: 'MyAnimeList',
      kitsu: 'Kitsu',
      mangadex: 'MangaDex',
      metron: 'Metron',
    } as Record<string, string>,
    fields: {
      anilist: clientFields,
      mal: clientFields,
      kitsu: [
        {key: 'username'},
        {key: 'password', secret: true},
      ],
      mangadex: [
        {key: 'username'},
        {key: 'password', secret: true},
        {key: 'client_id'},
        {key: 'client_secret', secret: true},
        {key: 'max_content_rating', options: ['safe', 'suggestive', 'erotica', 'pornographic']},
      ],
      metron: [
        {key: 'token', secret: true},
        {key: 'username'},
        {key: 'password', secret: true},
      ],
    } as Record<string, TrackerField[]>,
  }),
  mounted() {
    this.showAuthorizationResult()
    this.load()
  },
  methods: {
    formatDateTime(value?: string): string {
      if (!value) return ''
      return new Intl.DateTimeFormat(this.$i18n.locale, {dateStyle: 'medium', timeStyle: 'short'} as Intl.DateTimeFormatOptions).format(new Date(value))
    },
    tokenExpiresSoon(tracker: UserTrackerDto): boolean {
      const expiry = tracker.status.tokenExpiresAt
      if (!expiry || !tracker.linked) return false
      return new Date(expiry).getTime() - Date.now() < tokenWarningMillis
    },
    canSync(tracker: UserTrackerDto): boolean {
      return tracker.linked && tracker.enabled && tracker.scrobblerEnabled &&
        tracker.status.state !== 'reconnect_required' && tracker.status.state !== 'secrets_unreadable'
    },
    async sync(tracker: UserTrackerDto) {
      this.syncing = tracker.tracker
      try {
        const result = await this.$komgaTrackers.sync(tracker.tracker)
        this.showMessage(this.$tc('account_settings.trackers.sync_queued', result.queued, {count: result.queued}).toString(), 'success')
      } catch (e) {
        this.showMessage((e as Error).message, 'error')
      } finally {
        this.syncing = null
      }
    },
    formFrom(tracker: UserTrackerDto): TrackerForm {
      const values = {} as Record<string, string | null>
      ;(this.fields[tracker.tracker] || []).forEach(f => {
        values[f.key] = f.secret ? '' : (tracker.settings[f.key] ?? '')
      })
      return {enabled: tracker.enabled, values}
    },
    async load(resetTracker?: string, enabledTracker?: string) {
      try {
        const trackers = await this.$komgaTrackers.getAll()
        trackers.forEach(t => {
          if (!this.forms[t.tracker] || t.tracker === resetTracker) this.$set(this.forms, t.tracker, this.formFrom(t))
          else if (t.tracker === enabledTracker) this.forms[t.tracker].enabled = t.enabled
        })
        this.trackers = trackers
      } catch (e) {
        this.showMessage(this.$t('account_settings.trackers.load_failed') + ': ' + (e as Error).message, 'error')
      }
    },
    async toggleEnabled(tracker: UserTrackerDto, enabled: boolean) {
      const form = this.forms[tracker.tracker]
      form.enabled = enabled
      this.toggling = tracker.tracker
      try {
        await this.$komgaTrackers.update(tracker.tracker, {enabled})
        await this.load(undefined, tracker.tracker)
      } catch (e) {
        form.enabled = !enabled
        this.showMessage(this.$t('account_settings.trackers.save_failed') + ': ' + (e as Error).message, 'error')
      } finally {
        this.toggling = null
      }
    },
    async save(tracker: UserTrackerDto) {
      const form = this.forms[tracker.tracker]
      const settings = {} as Record<string, string | null>
      ;(this.fields[tracker.tracker] || []).forEach(f => {
        const value = form.values[f.key]
        if (f.secret) {
          if (value) settings[f.key] = value
        } else if ((value ?? '') !== (tracker.settings[f.key] ?? '')) {
          settings[f.key] = value || null
        }
      })
      this.busy = tracker.tracker
      try {
        await this.$komgaTrackers.update(tracker.tracker, {enabled: form.enabled, settings})
        this.showMessage(this.$t('account_settings.trackers.saved').toString(), 'success')
        await this.load(tracker.tracker)
      } catch (e) {
        this.showMessage(this.$t('account_settings.trackers.save_failed') + ': ' + (e as Error).message, 'error')
      } finally {
        this.busy = null
      }
    },
    async authorize(tracker: UserTrackerDto) {
      this.authorizing = tracker.tracker
      try {
        const result = await this.$komgaTrackers.authorize(tracker.tracker, `${urls.baseNoSlash}/classic${this.$route.path}`)
        window.location.assign(result.authorizationUrl)
      } catch (e) {
        this.showMessage((e as Error).message, 'error')
        this.authorizing = null
      }
    },
    showAuthorizationResult() {
      const {trackerAuth, trackerAuthResult, trackerAuthError, ...query} = this.$route.query
      if (!trackerAuth) return
      const name = this.trackerNames[String(trackerAuth)] ?? String(trackerAuth)
      if (trackerAuthResult === 'success') {
        this.showMessage(this.$t('account_settings.trackers.authorized', {name}).toString(), 'success')
      } else {
        const reason = authorizationErrors.includes(String(trackerAuthError)) ? String(trackerAuthError) : 'unknown'
        this.showMessage(this.$t(`account_settings.trackers.authorization_error_${reason}`, {name}).toString(), 'error')
      }
      this.$router.replace({query})
    },
    async test(tracker: UserTrackerDto) {
      this.testing = tracker.tracker
      try {
        const result = await this.$komgaTrackers.test(tracker.tracker)
        this.showMessage(result.message, result.success ? 'success' : 'error')
        await this.load()
      } catch (e) {
        this.showMessage((e as Error).message, 'error')
      } finally {
        this.testing = null
      }
    },
    async unlink(tracker: UserTrackerDto | null) {
      if (!tracker) return
      try {
        await this.$komgaTrackers.remove(tracker.tracker)
        this.showMessage(this.$t('account_settings.trackers.unlinked').toString(), 'success')
        await this.load(tracker.tracker)
      } catch (e) {
        this.showMessage((e as Error).message, 'error')
      }
    },
    showMessage(text: string, color: string) {
      this.snackbarText = text
      this.snackbarColor = color
      this.snackbar = true
    },
  },
})
</script>
