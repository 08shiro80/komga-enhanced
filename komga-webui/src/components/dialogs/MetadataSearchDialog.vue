<template>
  <v-dialog v-model="modal"
            :fullscreen="$vuetify.breakpoint.xsOnly"
            max-width="1200"
            @keydown.esc="dialogCancel"
            scrollable
  >
    <v-card>
      <v-toolbar class="hidden-sm-and-up">
        <v-btn icon @click="dialogCancel">
          <v-icon>mdi-close</v-icon>
        </v-btn>
        <v-toolbar-title>Search Metadata</v-toolbar-title>
      </v-toolbar>

      <v-card-title class="hidden-xs-only">
        <v-icon class="mx-4">mdi-cloud-search</v-icon>
        Search Metadata
      </v-card-title>

      <v-card-text>
        <v-container fluid>
          <!-- Plugin Selector -->
          <v-row>
            <v-col cols="12" sm="6">
              <v-select
                v-model="selectedPlugin"
                :items="availablePlugins"
                item-text="name"
                item-value="id"
                label="Metadata Provider"
                filled
                dense
              ></v-select>
            </v-col>
          </v-row>

          <!-- Search Field -->
          <v-row>
            <v-col cols="12">
              <v-text-field
                v-model="searchQuery"
                label="Search Title"
                filled
                dense
                append-icon="mdi-magnify"
                @click:append="performSearch"
                @keydown.enter="performSearch"
                :loading="searching"
                clearable
              ></v-text-field>
            </v-col>
          </v-row>

          <!-- Search Results -->
          <v-row v-if="searchResults.length > 0">
            <v-col
              cols="12" sm="6" md="4" lg="3"
              v-for="result in searchResults"
              :key="result.externalId"
            >
              <v-card>
                <v-img
                  :src="result.coverUrl || ''"
                  aspect-ratio="0.7"
                  class="white--text"
                >
                  <template v-slot:placeholder>
                    <v-row class="fill-height ma-0" align="center" justify="center">
                      <v-icon size="64">mdi-book</v-icon>
                    </v-row>
                  </template>
                </v-img>
                <v-card-title class="subtitle-2">{{ result.title }}</v-card-title>
                <v-card-subtitle v-if="result.author">
                  <v-icon small>mdi-account</v-icon>
                  {{ result.author }}
                </v-card-subtitle>
                <v-card-subtitle v-if="result.year">
                  <v-icon small>mdi-calendar</v-icon>
                  {{ result.year }}
                </v-card-subtitle>
                <v-card-text v-if="result.description" class="text--primary">
                  <div class="text-truncate-3">{{ result.description }}</div>
                </v-card-text>
                <v-card-actions>
                  <v-btn
                    small
                    color="primary"
                    text
                    @click="applyMetadata(result)"
                  >
                    Apply
                  </v-btn>
                  <v-btn
                    small
                    text
                    @click="viewDetails(result)"
                  >
                    Details
                  </v-btn>
                </v-card-actions>
              </v-card>
            </v-col>
          </v-row>

          <!-- No Results Message -->
          <v-row v-if="searched && searchResults.length === 0 && !searching">
            <v-col cols="12" class="text-center">
              <v-icon size="64" color="grey">mdi-magnify-close</v-icon>
              <p class="text-h6 grey--text">No results found</p>
            </v-col>
          </v-row>

          <!-- Loading -->
          <v-row v-if="searching">
            <v-col cols="12" class="text-center">
              <v-progress-circular indeterminate color="primary"></v-progress-circular>
            </v-col>
          </v-row>
        </v-container>
      </v-card-text>

      <v-card-actions class="hidden-xs-only">
        <v-spacer/>
        <v-btn text @click="dialogCancel">Close</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'

interface MetadataSearchResult {
  externalId: string
  title: string
  description: string | null
  coverUrl: string | null
  author: string | null
  year: number | null
  status: string | null
  tags: string[]
  provider: string
}

interface Plugin {
  id: string
  name: string
  enabled: boolean
  pluginType: string
}

export default Vue.extend({
  name: 'MetadataSearchDialog',
  data: () => {
    return {
      modal: false,
      selectedPlugin: 'mangadex-metadata',
      searchQuery: '',
      searchResults: [] as MetadataSearchResult[],
      searching: false,
      searched: false,
      availablePlugins: [] as Plugin[],
    }
  },
  props: {
    value: Boolean,
    entityId: {
      type: String,
      required: true,
    },
    entityType: {
      type: String,
      required: true,
      validator: (value: string) => ['series', 'book'].includes(value),
    },
  },
  watch: {
    value(val) {
      this.modal = val
      if (val) {
        this.loadPlugins()
      }
    },
    modal(val) {
      !val && this.dialogCancel()
    },
  },
  methods: {
    dialogCancel() {
      this.$emit('input', false)
      this.searchQuery = ''
      this.searchResults = []
      this.searched = false
    },
    async loadPlugins() {
      try {
        const plugins = await this.$komgaPlugins.getPlugins()
        // Filter to only show enabled METADATA plugins
        this.availablePlugins = plugins.filter((p: Plugin) =>
          p.enabled && p.pluginType === 'METADATA',
        )
        if (this.availablePlugins.length > 0 && !this.selectedPlugin) {
          this.selectedPlugin = this.availablePlugins[0].id
        }
      } catch (e) {
        this.$eventHub.$emit('error', { message: e.message })
      }
    },
    async performSearch() {
      if (!this.searchQuery || !this.selectedPlugin) return

      this.searching = true
      this.searched = false

      try {
        const results = await this.$komgaPlugins.searchMetadata(this.selectedPlugin, this.searchQuery)
        this.searchResults = results
        this.searched = true
      } catch (e) {
        this.$eventHub.$emit('error', { message: e.message })
      } finally {
        this.searching = false
      }
    },
    async applyMetadata(result: MetadataSearchResult) {
      try {
        const metadata = await this.$komgaPlugins.getMetadata(this.selectedPlugin, result.externalId)
        this.$emit('metadata-selected', metadata)
        this.dialogCancel()
      } catch (e) {
        this.$eventHub.$emit('error', { message: e.message })
      }
    },
    viewDetails(result: MetadataSearchResult) {
      // Could expand to show full details in a sub-dialog
      console.log('View details for:', result)
    },
  },
})
</script>

<style scoped>
.text-truncate-3 {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
