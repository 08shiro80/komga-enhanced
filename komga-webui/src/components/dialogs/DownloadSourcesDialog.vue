<template>
  <v-dialog v-model="modalVisible" :fullscreen="$vuetify.breakpoint.xsOnly" max-width="600">
    <v-card>
      <v-toolbar dark color="primary" flat>
        <v-toolbar-title>Download sources</v-toolbar-title>
        <v-spacer/>
        <v-btn icon @click="modalVisible = false"><v-icon>mdi-close</v-icon></v-btn>
      </v-toolbar>

      <v-card-text class="pt-4">
        <p v-if="series" class="text-caption mb-1">
          Series: <strong>{{ series.metadata.title }}</strong>
        </p>
        <p class="text-caption text--secondary mb-3">
          URLs linked here are checked on this library's follow schedule and downloaded straight into this series.
        </p>

        <div v-if="loading" class="text-center my-4">
          <v-progress-circular indeterminate color="primary"/>
        </div>
        <div v-else-if="items.length === 0" class="caption text--secondary my-2">
          No sources linked to this series yet.
        </div>
        <v-list v-else dense class="py-0">
          <v-list-item v-for="f in items" :key="f.id" class="px-0">
            <v-list-item-action class="my-0 mr-2">
              <v-switch :input-value="f.enabled" dense hide-details class="mt-0" @change="toggleEnabled(f)"/>
            </v-list-item-action>
            <v-list-item-content class="py-1">
              <v-list-item-title v-if="f.title">{{ f.title }}</v-list-item-title>
              <v-list-item-title v-else class="text--secondary font-italic">(no name)</v-list-item-title>
              <v-list-item-subtitle style="white-space: normal; word-break: break-all">{{ f.url }}</v-list-item-subtitle>
            </v-list-item-content>
            <v-list-item-action class="my-0">
              <v-btn icon small @click="remove(f)"><v-icon small>mdi-delete-outline</v-icon></v-btn>
            </v-list-item-action>
          </v-list-item>
        </v-list>

        <v-text-field
          v-model="newUrl"
          label="Add source URL"
          placeholder="https://mangadex.org/title/… or another site URL"
          outlined
          dense
          clearable
          class="mt-3"
          :disabled="busy"
          @keyup.enter="add"
        />
      </v-card-text>

      <v-card-actions>
        <v-spacer/>
        <v-btn text @click="modalVisible = false">Close</v-btn>
        <v-btn color="primary" :disabled="!newUrl || busy" :loading="busy" @click="add">Add</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'
import {SeriesDto} from '@/types/komga-series'
import {FollowDto} from '@/types/komga-follows'

export default Vue.extend({
  name: 'DownloadSourcesDialog',
  props: {
    value: Boolean,
    series: {
      type: Object as () => SeriesDto | undefined,
      default: undefined,
    },
  },
  data: () => ({
    items: [] as FollowDto[],
    newUrl: '',
    loading: false,
    busy: false,
  }),
  computed: {
    modalVisible: {
      get(): boolean {
        return this.value
      },
      set(val: boolean) {
        this.$emit('input', val)
      },
    },
  },
  watch: {
    value(newVal) {
      if (newVal) {
        this.newUrl = ''
        this.load()
      }
    },
  },
  methods: {
    async load() {
      if (!this.series) return
      this.loading = true
      try {
        this.items = await this.$komgaFollows.getBySeries(this.series.id)
      } catch (e: any) {
        this.$eventHub.$emit('error', {message: e.message})
      } finally {
        this.loading = false
      }
    },
    async add() {
      if (!this.series || !this.newUrl) return
      this.busy = true
      try {
        await this.$komgaFollows.add(this.series.libraryId, {url: this.newUrl.trim(), seriesId: this.series.id})
        this.newUrl = ''
        await this.load()
      } catch (e: any) {
        this.$eventHub.$emit('error', {message: e.response?.data?.message || e.message})
      } finally {
        this.busy = false
      }
    },
    async remove(f: FollowDto) {
      if (!this.series) return
      try {
        await this.$komgaFollows.remove(this.series.libraryId, f.id)
        this.items = this.items.filter(x => x.id !== f.id)
      } catch (e: any) {
        this.$eventHub.$emit('error', {message: e.message})
      }
    },
    async toggleEnabled(f: FollowDto) {
      if (!this.series) return
      try {
        await this.$komgaFollows.update(this.series.libraryId, f.id, {enabled: !f.enabled})
        f.enabled = !f.enabled
      } catch (e: any) {
        this.$eventHub.$emit('error', {message: e.message})
      }
    },
  },
})
</script>
