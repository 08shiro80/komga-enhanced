<template>
  <v-container fluid class="pa-6">
    <v-row>
      <v-col cols="auto">
        <v-checkbox
          v-model="form.posterStretch"
          @change="$v.form.posterStretch.$touch()"
          :label="$t('ui_settings.label_poster_stretch')"
          hide-details
        />

        <v-checkbox
          v-model="form.posterBlurUnread"
          @change="$v.form.posterBlurUnread.$touch()"
          :label="$t('ui_settings.label_poster_blur_unread')"
          hide-details
        />
      </v-col>
    </v-row>

    <v-divider class="my-4"/>

    <v-row>
      <v-col>
        <span class="font-weight-black text-h6">Color Theme</span>
        <span class="text-caption grey--text ml-2">(Auto-saved)</span>
      </v-col>
    </v-row>
    <v-row>
      <v-col
        v-for="preset in themePresets"
        :key="preset.name"
        cols="auto"
      >
        <v-card
          :outlined="selectedPreset !== preset.name"
          :color="selectedPreset === preset.name ? 'primary' : undefined"
          :dark="selectedPreset === preset.name"
          width="130"
          class="text-center pa-2 preset-card"
          @click="selectPreset(preset.name)"
          style="cursor: pointer"
        >
          <v-icon>{{ preset.icon }}</v-icon>
          <div class="text-body-2 mt-1">{{ preset.label }}</div>
          <div class="d-flex justify-center mt-1">
            <div
              v-for="(color, ci) in [preset.dark.primary, preset.dark.secondary, preset.dark.accent]"
              :key="ci"
              :style="{backgroundColor: color, width: '16px', height: '16px', borderRadius: '50%', margin: '0 2px'}"
            />
          </div>
        </v-card>
      </v-col>
    </v-row>

    <v-divider class="my-4"/>

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
import {CLIENT_SETTING, ClientSettingUserUpdateDto} from '@/types/komga-clientsettings'
import {THEME_PRESETS} from '@/types/theme-presets'

export default Vue.extend({
  name: 'UIUserSettings',
  data: () => ({
    form: {
      posterStretch: false,
      posterBlurUnread: false,
    },
    themePresets: THEME_PRESETS,
  }),
  validations: {
    form: {
      posterStretch: {},
      posterBlurUnread: {},
    },
  },
  computed: {
    selectedPreset: {
      get(): string {
        return this.$store.state.persistedState.themePreset || 'default'
      },
      set(val: string) {
        this.$store.commit('setThemePreset', val)
      },
    },
    saveDisabled(): boolean {
      return this.$v.form.$invalid || !this.$v.form.$anyDirty
    },
    discardDisabled(): boolean {
      return !this.$v.form.$anyDirty
    },
  },
  mounted() {
    this.refreshSettings()
  },
  methods: {
    selectPreset(name: string) {
      this.selectedPreset = name
    },
    async refreshSettings() {
      await this.$store.dispatch('getClientSettingsUser')
      this.form.posterStretch = this.$store.state.komgaSettings.clientSettingsUser[CLIENT_SETTING.WEBUI_POSTER_STRETCH]?.value === 'true'
      this.form.posterBlurUnread = this.$store.state.komgaSettings.clientSettingsUser[CLIENT_SETTING.WEBUI_POSTER_BLUR_UNREAD]?.value === 'true'
      this.$v.form.$reset()
    },
    async saveSettings() {
      let newSettings = {} as Record<string, ClientSettingUserUpdateDto>

      if (this.$v.form?.posterStretch?.$dirty)
        newSettings[CLIENT_SETTING.WEBUI_POSTER_STRETCH] = {
          value: this.form.posterStretch ? 'true' : 'false',
        }
      if (this.$v.form?.posterBlurUnread?.$dirty)
        newSettings[CLIENT_SETTING.WEBUI_POSTER_BLUR_UNREAD] = {
          value: this.form.posterBlurUnread ? 'true' : 'false',
        }

      await this.$komgaSettings.updateClientSettingUser(newSettings)

      await this.refreshSettings()
    },
  },
})
</script>

<style scoped>
.preset-card {
  transition: all 0.2s ease;
}
.preset-card:hover {
  transform: translateY(-2px);
}
</style>
