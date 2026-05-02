<template>
  <v-container fluid class="pa-6">
    <v-row>
      <v-col cols="auto">
        <span class="font-weight-black text-h6">{{ $t('ui_settings.general') }}</span>

        <v-radio-group
          v-model="form.seriesGroups"
          @change="$v.form.seriesGroups.$touch()"
          :label="$t('ui_settings.label_series_groups')"
          hide-details
        >
          <v-radio value="alpha" :label="$t('ui_settings.series_groups.alpha')"/>
          <v-radio value="japanese" :label="$t('ui_settings.series_groups.japanese')"/>
        </v-radio-group>
      </v-col>
    </v-row>
    <v-row>
      <v-col cols="auto">
        <span class="font-weight-black text-h6">Guest Mode</span>

        <v-checkbox
          v-model="form.guestAccess"
          @change="$v.form.guestAccess.$touch()"
          label="Guest Mode — Lesezugriff ohne Login erlauben"
          hide-details
        >
          <template v-slot:append>
            <v-tooltip bottom>
              <template v-slot:activator="{ on }">
                <v-icon v-on="on">
                  mdi-information-outline
                </v-icon>
              </template>
              Allows unauthenticated users to browse and read without logging in. Read-only access only.
            </v-tooltip>
          </template>
        </v-checkbox>

        <v-select
          v-if="form.guestAccess"
          v-model="form.guestLibraries"
          @change="$v.form.guestLibraries.$touch()"
          :items="availableLibraries"
          item-text="name"
          item-value="id"
          label="Bibliotheken für Gäste"
          multiple
          chips
          small-chips
          deletable-chips
          hide-details
          class="mt-3"
        >
          <template v-slot:prepend-item>
            <v-list-item @click="toggleAllLibraries">
              <v-list-item-action>
                <v-icon>{{ allLibrariesSelected ? 'mdi-checkbox-marked' : 'mdi-checkbox-blank-outline' }}</v-icon>
              </v-list-item-action>
              <v-list-item-content>
                <v-list-item-title>Alle Bibliotheken</v-list-item-title>
              </v-list-item-content>
            </v-list-item>
            <v-divider/>
          </template>
        </v-select>
      </v-col>
    </v-row>
    <v-row>
      <v-col cols="auto">
        <span class="font-weight-black text-h6">{{ $t('ui_settings.section_oauth2') }}</span>

        <v-checkbox
          v-model="form.oauth2HideLogin"
          @change="$v.form.oauth2HideLogin.$touch()"
          :label="$t('ui_settings.label_oauth2_hide_login')"
          hide-details
        />

        <v-checkbox
          v-model="form.oauth2AutoLogin"
          @change="$v.form.oauth2AutoLogin.$touch()"
          :label="$t('ui_settings.label_oauth2_auto_login')"
          hide-details
        >
          <template v-slot:append>
            <v-tooltip bottom>
              <template v-slot:activator="{ on }">
                <v-icon v-on="on">
                  mdi-information-outline
                </v-icon>
              </template>
              {{ $t('ui_settings.tooltip_oauth2_auto_login') }}
            </v-tooltip>
          </template>
        </v-checkbox>
      </v-col>
    </v-row>
    <v-row>
      <v-col cols="auto">
        <v-btn @click="refreshSettings"
               :disabled="discardDisabled"
        >{{ $t('common.discard') }}
        </v-btn>
      </v-col>
      <v-col cols="auto">
        <v-btn color="primary"
               :disabled="saveDisabled"
               @click="saveSettings"
        >{{ $t('common.save_changes') }}
        </v-btn>
      </v-col>
    </v-row>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import {
  CLIENT_SETTING,
  ClientSettingGlobalUpdateDto,
  ClientSettingsSeriesGroup,
  SERIES_GROUP_ALPHA,
  SERIES_GROUP_JAPANESE,
} from '@/types/komga-clientsettings'

export default Vue.extend({
  name: 'UISettings',
  data: () => ({
    form: {
      guestAccess: false,
      guestLibraries: [] as string[],
      oauth2HideLogin: false,
      oauth2AutoLogin: false,
      seriesGroups: 'alpha',
    },
  }),
  validations: {
    form: {
      guestAccess: {},
      guestLibraries: {},
      oauth2HideLogin: {},
      oauth2AutoLogin: {},
      seriesGroups: {},
    },
  },
  mounted() {
    this.refreshSettings()
  },
  computed: {
    availableLibraries(): any[] {
      return this.$store.getters.getLibraries
    },
    allLibrariesSelected(): boolean {
      return this.availableLibraries.length > 0 &&
        this.form.guestLibraries.length === this.availableLibraries.length
    },
    saveDisabled(): boolean {
      return this.$v.form.$invalid || !this.$v.form.$anyDirty
    },
    discardDisabled(): boolean {
      return !this.$v.form.$anyDirty
    },
  },
  methods: {
    toggleAllLibraries() {
      if (this.allLibrariesSelected) {
        this.form.guestLibraries = []
      } else {
        this.form.guestLibraries = this.availableLibraries.map((l: any) => l.id)
      }
      this.$v.form.guestLibraries.$touch()
    },
    async refreshSettings() {
      await this.$store.dispatch('getClientSettingsGlobal')
      this.form.guestAccess = this.$store.state.komgaSettings.clientSettingsGlobal[CLIENT_SETTING.WEBUI_GUEST_ACCESS]?.value === 'true'
      try {
        const libs = this.$store.state.komgaSettings.clientSettingsGlobal[CLIENT_SETTING.WEBUI_GUEST_LIBRARIES]?.value
        this.form.guestLibraries = libs ? JSON.parse(libs) : []
      } catch (_) {
        this.form.guestLibraries = []
      }
      this.form.oauth2HideLogin = this.$store.state.komgaSettings.clientSettingsGlobal[CLIENT_SETTING.WEBUI_OAUTH2_HIDE_LOGIN]?.value === 'true'
      this.form.oauth2AutoLogin = this.$store.state.komgaSettings.clientSettingsGlobal[CLIENT_SETTING.WEBUI_OAUTH2_AUTO_LOGIN]?.value === 'true'
      try {
        this.form.seriesGroups = (JSON.parse(this.$store.state.komgaSettings.clientSettingsGlobal[CLIENT_SETTING.WEBUI_SERIES_GROUPS]?.value) as ClientSettingsSeriesGroup)?.name
      } catch (_) {
      }
      this.$v.form.$reset()
    },
    async saveSettings() {
      let newSettings = {} as Record<string, ClientSettingGlobalUpdateDto>
      if (this.$v.form?.guestAccess?.$dirty)
        newSettings[CLIENT_SETTING.WEBUI_GUEST_ACCESS] = {
          value: this.form.guestAccess ? 'true' : 'false',
          allowUnauthorized: true,
        }

      if (this.$v.form?.guestLibraries?.$dirty)
        newSettings[CLIENT_SETTING.WEBUI_GUEST_LIBRARIES] = {
          value: JSON.stringify(this.form.guestLibraries),
          allowUnauthorized: true,
        }

      if (this.$v.form?.oauth2HideLogin?.$dirty)
        newSettings[CLIENT_SETTING.WEBUI_OAUTH2_HIDE_LOGIN] = {
          value: this.form.oauth2HideLogin ? 'true' : 'false',
          allowUnauthorized: true,
        }

      if (this.$v.form?.oauth2AutoLogin?.$dirty)
        newSettings[CLIENT_SETTING.WEBUI_OAUTH2_AUTO_LOGIN] = {
          value: this.form.oauth2AutoLogin ? 'true' : 'false',
          allowUnauthorized: true,
        }

      if (this.$v.form?.seriesGroups?.$dirty)
        newSettings[CLIENT_SETTING.WEBUI_SERIES_GROUPS] = {
          value: JSON.stringify(this.form.seriesGroups === 'alpha' ? SERIES_GROUP_ALPHA : SERIES_GROUP_JAPANESE),
          allowUnauthorized: false,
        }

      await this.$komgaSettings.updateClientSettingGlobal(newSettings)

      await this.refreshSettings()
    },
  },
})
</script>

<style scoped>

</style>
