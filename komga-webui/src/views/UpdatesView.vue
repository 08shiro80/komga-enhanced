<template>
  <v-container fluid class="pa-6">
    <v-row>
      <v-col>
        <div class="mb-4">
          <v-chip label color="primary" class="me-2">Base: {{ currentVersion }}</v-chip>
          <v-chip v-if="forkVersion" label color="secondary">Fork: {{ forkVersion }}</v-chip>
        </div>
      </v-col>
    </v-row>

    <v-tabs v-model="tab" class="mb-4">
      <v-tab>Upstream (Komga)</v-tab>
      <v-tab>
        <v-badge
          dot
          :value="$store.getters.isForkLatestVersion() == 0"
          color="warning"
        >
          Fork
        </v-badge>
      </v-tab>
    </v-tabs>

    <v-tabs-items v-model="tab">
      <!-- UPSTREAM TAB -->
      <v-tab-item>
        <div v-if="$store.getters.isLatestVersion() == 1">
          <v-alert type="success" text>
            {{ $t('updates.latest_installed')}}
          </v-alert>
        </div>
        <div v-if="$store.getters.isLatestVersion() == 0">
          <v-alert type="warning" text>
            {{ $t('updates.available')}}
          </v-alert>
        </div>

        <div v-for="(release, index) in $store.state.releases" :key="'up-'+index">
          <v-row justify="space-between" align="center">
            <v-col cols="auto">
              <div>
                <a :href="release.url" target="_blank" class="text-h4 font-weight-medium link-underline me-2">{{
                    release.version
                  }}</a>
                <v-chip
                  v-if="release.version.replace(/^v/, '') == currentVersion.replace(/^v/, '')"
                  class="mx-2 mt-n3"
                  small
                  label
                  color="info"
                >Currently installed</v-chip>
                <v-chip
                  v-if="release.version == latestUpstream?.version"
                  class="mx-2 mt-n3"
                  small
                  label
                >Latest</v-chip>
              </div>
              <div class="mt-2 subtitle-1">
                {{ new Intl.DateTimeFormat($i18n.locale, {dateStyle: 'long'}).format(release.releaseDate) }}
              </div>
            </v-col>
          </v-row>

          <v-row>
            <v-col cols="12">
              <div v-html="marked(release.description)"></div>
            </v-col>
          </v-row>
          <v-divider class="my-8" v-if="index != $store.state.releases.length - 1"/>
        </div>
      </v-tab-item>

      <!-- FORK TAB -->
      <v-tab-item>
        <div v-if="$store.getters.isForkLatestVersion() == 1">
          <v-alert type="success" text>
            Fork ist auf dem neuesten Stand
          </v-alert>
        </div>
        <div v-if="$store.getters.isForkLatestVersion() == 0">
          <v-alert type="warning" text>
            Fork-Update verfügbar
          </v-alert>
        </div>
        <div v-if="$store.state.forkReleases.length == 0">
          <v-alert type="info" text>
            Keine Fork-Releases gefunden
          </v-alert>
        </div>

        <div v-for="(release, index) in $store.state.forkReleases" :key="'fork-'+index">
          <v-row justify="space-between" align="center">
            <v-col cols="auto">
              <div>
                <a :href="release.url" target="_blank" class="text-h4 font-weight-medium link-underline me-2">{{
                    release.version
                  }}</a>
                <v-chip
                  v-if="isCurrentForkRelease(release.version)"
                  class="mx-2 mt-n3"
                  small
                  label
                  color="info"
                >Installiert</v-chip>
                <v-chip
                  v-if="release.version == latestFork?.version"
                  class="mx-2 mt-n3"
                  small
                  label
                >Latest</v-chip>
              </div>
              <div class="mt-2 subtitle-1">
                {{ new Intl.DateTimeFormat($i18n.locale, {dateStyle: 'long'}).format(release.releaseDate) }}
              </div>
            </v-col>
          </v-row>

          <v-row>
            <v-col cols="12">
              <div v-html="marked(release.description)"></div>
            </v-col>
          </v-row>
          <v-divider class="my-8" v-if="index != $store.state.forkReleases.length - 1"/>
        </div>
      </v-tab-item>
    </v-tabs-items>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import {marked} from 'marked'

export default Vue.extend({
  name: 'UpdatesView',
  data: () => ({
    marked,
    tab: 0,
  }),
  computed: {
    latestUpstream(): ReleaseDto | undefined {
      return this.$store.state.releases.find((x: ReleaseDto) => x.latest)
    },
    latestFork(): ReleaseDto | undefined {
      return this.$store.state.forkReleases.find((x: ReleaseDto) => x.latest)
    },
    currentVersion(): string {
      const fullVersion = this.$store.state.actuatorInfo?.build?.version || ''
      return fullVersion.split('-fork')[0]
    },
    forkVersion(): string {
      const fullVersion = this.$store.state.actuatorInfo?.build?.version || ''
      const parts = fullVersion.split('-fork-')
      return parts.length > 1 ? parts[1] : ''
    },
    fullVersion(): string {
      return this.$store.state.actuatorInfo?.build?.version || ''
    },
  },
  mounted() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.$actuator.getInfo()
        .then(x => this.$store.commit('setActuatorInfo', x))
      this.$komgaReleases.getReleases()
        .then(x => this.$store.commit('setReleases', x))
      this.$komgaReleases.getForkReleases()
        .then(x => this.$store.commit('setForkReleases', x))
        .catch(() => {})
    },
    isCurrentForkRelease(tagName: string): boolean {
      const tag = tagName.replace(/^v/, '')
      const full = this.fullVersion.replace(/^v/, '')
      if (tag === full) return true
      const tagFork = tag.split('-fork-')[1] || ''
      return tagFork !== '' && tagFork === this.forkVersion
    },
  },
})
</script>

<style scoped>

</style>
