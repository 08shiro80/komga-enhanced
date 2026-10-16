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
              v-model="forms[tracker.tracker].enabled"
              :label="$t('account_settings.trackers.enabled')"
              hide-details
              class="mt-0"
            />
          </v-card-title>

          <v-card-subtitle>{{ $t(`account_settings.trackers.hint_${tracker.tracker}`) }}</v-card-subtitle>

          <v-card-text>
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

          <v-card-actions>
            <v-btn
              color="primary"
              :loading="busy === tracker.tracker"
              @click="save(tracker)"
            >{{ $t('account_settings.trackers.save') }}</v-btn>
            <v-btn
              text
              :disabled="!tracker.linked"
              :loading="testing === tracker.tracker"
              @click="test(tracker)"
            >{{ $t('account_settings.trackers.test') }}</v-btn>
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

interface TrackerField {
  key: string,
  secret?: boolean,
  options?: string[],
}

interface TrackerForm {
  enabled: boolean,
  values: Record<string, string | null>,
}

const oauthFields: TrackerField[] = [
  {key: 'access_token', secret: true},
  {key: 'refresh_token', secret: true},
  {key: 'client_id'},
  {key: 'client_secret', secret: true},
]

export default Vue.extend({
  name: 'AccountTrackers',
  components: {ConfirmationDialog},
  data: () => ({
    trackers: [] as UserTrackerDto[],
    forms: {} as Record<string, TrackerForm>,
    busy: null as string | null,
    testing: null as string | null,
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
      anilist: [{key: 'token', secret: true}],
      mal: oauthFields,
      kitsu: oauthFields,
      mangadex: [
        {key: 'username'},
        {key: 'password', secret: true},
        {key: 'client_id'},
        {key: 'client_secret', secret: true},
        {key: 'max_content_rating', options: ['safe', 'suggestive', 'erotica', 'pornographic']},
      ],
      metron: [
        {key: 'username'},
        {key: 'password', secret: true},
      ],
    } as Record<string, TrackerField[]>,
  }),
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      try {
        const trackers = await this.$komgaTrackers.getAll()
        const forms = {} as Record<string, TrackerForm>
        trackers.forEach(t => {
          const values = {} as Record<string, string | null>
          ;(this.fields[t.tracker] || []).forEach(f => {
            values[f.key] = f.secret ? '' : (t.settings[f.key] ?? '')
          })
          forms[t.tracker] = {enabled: t.enabled, values}
        })
        this.forms = forms
        this.trackers = trackers
      } catch (e) {
        this.showMessage(this.$t('account_settings.trackers.load_failed') + ': ' + (e as Error).message, 'error')
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
        await this.load()
      } catch (e) {
        this.showMessage(this.$t('account_settings.trackers.save_failed') + ': ' + (e as Error).message, 'error')
      } finally {
        this.busy = null
      }
    },
    async test(tracker: UserTrackerDto) {
      this.testing = tracker.tracker
      try {
        const result = await this.$komgaTrackers.test(tracker.tracker)
        this.showMessage(result.message, result.success ? 'success' : 'error')
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
        await this.load()
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
