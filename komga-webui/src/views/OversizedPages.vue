<template>
  <v-container fluid class="pa-6">
    <v-data-table
      v-model="selectedPages"
      :headers="headers"
      :items="oversizedPages"
      :options.sync="options"
      :server-items-length="totalPages"
      :loading="loading"
      show-select
      item-key="rowKey"
      class="elevation-1"
      :footer-props="{
        itemsPerPageOptions: [20, 50, 100, 250, 500]
      }"
    >
      <template v-slot:top>
        <v-container>
          <v-row>
            <v-col cols="12">
              <v-alert v-if="currentMode === 'wide'" type="info" dismissible text class="body-2">
                Scan for wide pages (double pages combined in one image) and split them
                horizontally into two (or more) single pages. Uses aspect ratio
                (width &divide; height) &mdash; works at any resolution.
              </v-alert>
              <v-alert v-else type="info" dismissible text class="body-2">
                Scan for tall pages (webtoon strips, long scrolling pages) and split them into
                multiple readable pages. Uses aspect ratio (height &divide; width) instead of
                fixed pixel values &mdash; works at any resolution.
              </v-alert>
            </v-col>
          </v-row>
          <v-row align="center">
            <v-col cols="12" sm="6" md="3">
              <v-select
                v-model="selectedPreset"
                :items="presets"
                item-text="label"
                item-value="key"
                label="Preset"
                filled
                dense
                @change="applyPreset"
              />
            </v-col>
            <v-col cols="12" sm="3" md="2">
              <v-text-field
                v-model.number="detectRatio"
                label="Detect ratio"
                type="number"
                filled
                dense
                step="0.1"
                :min="1.1"
                :max="20"
                :hint="currentMode === 'wide' ? 'Find pages wider than N:1' : 'Find pages taller than N:1'"
                persistent-hint
                @input="selectedPreset = 'custom'"
              />
            </v-col>
            <v-col cols="12" sm="3" md="2">
              <v-text-field
                v-model.number="splitRatio"
                label="Split ratio"
                type="number"
                filled
                dense
                step="0.1"
                :min="0.5"
                :max="10"
                :hint="currentMode === 'wide' ? 'Max width per part (N &times; height)' : 'Max height per part (N &times; width)'"
                persistent-hint
                @input="selectedPreset = 'custom'"
              />
            </v-col>
            <v-col cols="12" sm="6" md="5" class="d-flex align-center flex-wrap">
              <v-btn color="primary" @click="loadPages" class="mr-2 mb-2">
                <v-icon left>mdi-magnify</v-icon>
                Search
              </v-btn>
              <v-btn
                color="warning"
                @click="splitSelected"
                :disabled="selectedPages.length === 0"
                :loading="splitting"
                class="mr-2 mb-2"
              >
                <v-icon left>mdi-scissors-cutting</v-icon>
                Split Selected ({{ selectedPages.length }})
              </v-btn>
              <v-btn
                color="error"
                @click="confirmSplitAll"
                :disabled="oversizedPages.length === 0"
                :loading="splitting"
                class="mb-2"
              >
                <v-icon left>mdi-content-cut</v-icon>
                Split All
              </v-btn>
            </v-col>
          </v-row>
          <v-row align="center">
            <v-col cols="12" class="d-flex align-center flex-wrap">
              <v-btn
                color="grey darken-1"
                dark
                @click="ignoreSelected"
                :disabled="selectedPages.length === 0"
                class="mr-2 mb-2"
              >
                <v-icon left>mdi-eye-off</v-icon>
                Ignore Selected ({{ selectedPages.length }})
              </v-btn>
              <v-btn
                color="red darken-2"
                dark
                @click="confirmDeleteSelected"
                :disabled="selectedPages.length === 0"
                class="mr-2 mb-2"
              >
                <v-icon left>mdi-delete</v-icon>
                Delete Selected ({{ selectedPages.length }})
              </v-btn>
              <v-switch
                v-model="includeIgnored"
                label="Show ignored"
                hide-details
                dense
                class="ml-2 mb-2 mt-0"
                @change="loadPages"
              />
            </v-col>
          </v-row>
        </v-container>
      </template>

      <template v-slot:item.thumbnail="{ item }">
        <v-img
          :src="thumbnailUrl(item)"
          :width="60"
          :height="80"
          contain
          class="my-2"
          style="cursor: pointer"
          @click="openPreview(item)"
        >
          <template v-slot:placeholder>
            <v-row class="fill-height ma-0" align="center" justify="center">
              <v-progress-circular indeterminate size="16" />
            </v-row>
          </template>
        </v-img>
      </template>

      <template v-slot:item.seriesTitle="{ item }">
        <router-link :to="{name: 'browse-series', params: {seriesId: item.seriesId}}">
          {{ item.seriesTitle }}
        </router-link>
      </template>

      <template v-slot:item.bookName="{ item }">
        <router-link :to="{name: 'browse-book', params: {bookId: item.bookId, seriesId: item.seriesId}}">
          {{ item.bookName }}
        </router-link>
      </template>

      <template v-slot:item.dimensions="{ item }">
        {{ item.width }} &times; {{ item.height }}
      </template>

      <template v-slot:item.ratio="{ item }">
        {{ item.ratio }}:1
      </template>

      <template v-slot:item.splitPreview="{ item }">
        {{ splitPreviewParts(item) }} parts
      </template>

      <template v-slot:item.fileSize="{ item }">
        {{ formatBytes(item.fileSize) }}
      </template>

      <template v-slot:item.actions="{ item }">
        <v-tooltip bottom>
          <template v-slot:activator="{ on }">
            <v-btn icon small v-on="on" @click="openPreview(item)">
              <v-icon small>mdi-image-search</v-icon>
            </v-btn>
          </template>
          <span>Preview</span>
        </v-tooltip>
        <v-tooltip bottom>
          <template v-slot:activator="{ on }">
            <v-btn icon small v-on="on" @click="ignoreRow(item)">
              <v-icon small>mdi-eye-off</v-icon>
            </v-btn>
          </template>
          <span>Ignore</span>
        </v-tooltip>
        <v-tooltip bottom>
          <template v-slot:activator="{ on }">
            <v-btn icon small v-on="on" @click="confirmDeleteRow(item)">
              <v-icon small color="red darken-2">mdi-delete</v-icon>
            </v-btn>
          </template>
          <span>Delete page from book</span>
        </v-tooltip>
      </template>

      <template v-slot:footer.prepend>
        <v-btn icon @click="loadPages">
          <v-icon>mdi-refresh</v-icon>
        </v-btn>
      </template>
    </v-data-table>

    <!-- Confirm Split All Dialog -->
    <v-dialog v-model="showConfirmDialog" max-width="500">
      <v-card>
        <v-card-title class="headline warning white--text">
          <v-icon left dark>mdi-alert</v-icon>
          {{ currentMode === 'wide' ? 'Split All Double Pages?' : 'Split All Tall Pages?' }}
        </v-card-title>
        <v-card-text class="pt-4">
          <p v-if="currentMode === 'wide'">This will split all pages with width:height ratio
            above <strong>{{ detectRatio }}:1</strong> into parts of max
            <strong>{{ splitRatio }}:1</strong>.</p>
          <p v-else>This will split all pages with ratio above
            <strong>{{ detectRatio }}:1</strong> into parts of max
            <strong>{{ splitRatio }}:1</strong>.</p>
          <p class="mb-0">This operation modifies your book files and cannot be undone.</p>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="showConfirmDialog = false">Cancel</v-btn>
          <v-btn color="warning" @click="splitAll">Split All</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Preview Dialog -->
    <v-dialog v-model="showPreviewDialog" max-width="90vw">
      <v-card v-if="previewItem">
        <v-card-title class="headline">
          {{ previewItem.bookName }} &mdash; Page {{ previewItem.pageNumber }}
          <v-spacer />
          <span class="text-caption">{{ previewItem.width }} &times; {{ previewItem.height }} ({{ previewItem.ratio }}:1)</span>
        </v-card-title>
        <v-card-text class="text-center">
          <img
            :src="fullImageUrl(previewItem)"
            :style="previewImgStyle"
            alt="Page preview"
          />
        </v-card-text>
        <v-card-actions>
          <v-btn color="grey darken-1" dark @click="ignoreFromPreview">
            <v-icon left>mdi-eye-off</v-icon>
            Ignore this page
          </v-btn>
          <v-btn color="red darken-2" dark @click="deleteFromPreview">
            <v-icon left>mdi-delete</v-icon>
            Delete this page
          </v-btn>
          <v-spacer />
          <v-btn text @click="showPreviewDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Confirm Delete Dialog -->
    <v-dialog v-model="showDeleteDialog" max-width="540">
      <v-card>
        <v-card-title class="headline red darken-2 white--text">
          <v-icon left dark>mdi-alert</v-icon>
          Delete {{ pagesToDelete.length }} page{{ pagesToDelete.length === 1 ? '' : 's' }}?
        </v-card-title>
        <v-card-text class="pt-4">
          <p>The selected page{{ pagesToDelete.length === 1 ? '' : 's' }} will be removed from
            the book archive. Page numbers of all following pages will shift. This operation
            modifies your book files and <strong>cannot be undone</strong>.</p>
          <p v-if="pagesToDelete.length <= 5" class="mb-0 text-caption">
            <span v-for="(p, idx) in pagesToDelete" :key="idx">
              <code>{{ p.bookName || p.bookId.slice(0, 8) }}</code> &mdash; page {{ p.pageNumber }}<br />
            </span>
          </p>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn text @click="showDeleteDialog = false">Cancel</v-btn>
          <v-btn color="red darken-2" dark :loading="deleting" @click="executeDelete">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Results Dialog -->
    <v-dialog v-model="showResultsDialog" max-width="700">
      <v-card>
        <v-card-title class="headline">
          Split Results
        </v-card-title>
        <v-card-text>
          <v-simple-table dense>
            <template v-slot:default>
              <thead>
                <tr>
                  <th>Book</th>
                  <th>Status</th>
                  <th>Pages Split</th>
                  <th>New Pages</th>
                  <th>Message</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="result in splitResults" :key="result.bookId">
                  <td>{{ result.bookName }}</td>
                  <td>
                    <v-icon v-if="result.success" color="success" small>mdi-check-circle</v-icon>
                    <v-icon v-else color="error" small>mdi-alert-circle</v-icon>
                  </td>
                  <td>{{ result.pagesSplit }}</td>
                  <td>{{ result.newPagesCreated }}</td>
                  <td class="text-caption">{{ result.message }}</td>
                </tr>
              </tbody>
            </template>
          </v-simple-table>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="showResultsDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import {OversizedPageDto} from '@/types/komga-books'
import {bookPageThumbnailUrl, bookPageUrl} from '@/functions/urls'

const qs = require('qs')

interface SplitResult {
  bookId: string
  bookName: string
  pagesAnalyzed: number
  pagesSplit: number
  newPagesCreated: number
  success: boolean
  message: string
}

type SplitMode = 'tall' | 'wide'

interface Preset {
  key: string
  label: string
  mode: SplitMode
  detect: number
  split: number
}

const PRESETS: Preset[] = [
  {key: 'webtoon', label: 'Webtoon / Long Strip', mode: 'tall', detect: 3, split: 1.5},
  {key: 'moderate', label: 'Moderate', mode: 'tall', detect: 2, split: 1.5},
  {key: 'aggressive', label: 'Aggressive', mode: 'tall', detect: 1.5, split: 1.2},
  {key: 'double', label: 'Double Page', mode: 'wide', detect: 1.3, split: 1.0},
  {key: 'custom', label: 'Custom', mode: 'tall', detect: 0, split: 0},
]

export default Vue.extend({
  name: 'OversizedPages',
  data: function () {
    return {
      oversizedPages: [] as OversizedPageDto[],
      selectedPages: [] as OversizedPageDto[],
      totalPages: 0,
      loading: true,
      splitting: false,
      options: {
        itemsPerPage: this.$store?.state?.persistedState?.dataTablePageSize || 20,
      } as any,
      selectedPreset: 'webtoon',
      currentMode: 'tall' as SplitMode,
      detectRatio: 3,
      splitRatio: 1.5,
      presets: PRESETS,
      splitResults: [] as SplitResult[],
      showResultsDialog: false,
      showConfirmDialog: false,
      showPreviewDialog: false,
      previewItem: null as OversizedPageDto | null,
      includeIgnored: false,
      showDeleteDialog: false,
      pagesToDelete: [] as OversizedPageDto[],
      deleting: false,
    }
  },
  watch: {
    options: {
      handler() {
        if (this.options.itemsPerPage) {
          this.$store.commit('setDataTablePageSize', this.options.itemsPerPage)
        }
        this.loadPages()
      },
      deep: true,
    },
  },
  computed: {
    headers(): object[] {
      return [
        {text: '', value: 'thumbnail', sortable: false, width: '80px'},
        {text: 'Series', value: 'seriesTitle'},
        {text: 'Book', value: 'bookName'},
        {text: 'Page #', value: 'pageNumber', width: '80px'},
        {text: 'Dimensions', value: 'dimensions', sortable: false},
        {text: 'Ratio', value: 'ratio', width: '90px'},
        {text: 'Split into', value: 'splitPreview', sortable: false, width: '100px'},
        {text: 'File Size', value: 'fileSize'},
        {text: '', value: 'actions', sortable: false, width: '140px'},
      ]
    },
    previewImgStyle(): object {
      return {
        maxWidth: '100%',
        maxHeight: '80vh',
        objectFit: 'contain',
      }
    },
  },
  methods: {
    applyPreset(key: string) {
      const preset = PRESETS.find(p => p.key === key)
      if (!preset) return
      if (preset.key !== 'custom') {
        this.detectRatio = preset.detect
        this.splitRatio = preset.split
      }
      this.currentMode = preset.mode
      this.selectedPages = []
      this.loadPages()
    },
    splitPreviewParts(item: OversizedPageDto): number {
      if (this.splitRatio <= 0) return 1
      if (this.currentMode === 'wide') {
        return Math.ceil(item.width / (item.height * this.splitRatio))
      }
      return Math.ceil(item.height / (item.width * this.splitRatio))
    },
    async loadPages() {
      this.loading = true

      const pageRequest = {
        page: this.options.page - 1,
        size: this.options.itemsPerPage,
      } as PageRequest

      if (this.options.sortBy && this.options.sortBy.length > 0) {
        const orders = this.options.sortDesc.map((desc: boolean, index: number) =>
          `${this.options.sortBy[index]},${desc ? 'desc' : 'asc'}`,
        )
        pageRequest.sort = orders
      }

      try {
        const page = await this.$komgaBooks.getOversizedPages(
          this.detectRatio,
          this.currentMode,
          this.includeIgnored,
          pageRequest,
        )
        this.oversizedPages = page.content.map(p => ({
          ...p,
          rowKey: `${p.bookId}_${p.pageNumber}`,
        }))
        this.totalPages = page.totalElements
      } catch (e) {
        this.$eventHub.$emit('error', {message: e.message})
      } finally {
        this.loading = false
      }
    },
    async splitSelected() {
      if (this.selectedPages.length === 0) return

      this.splitting = true
      this.splitResults = []

      // Group selected pages by bookId so each book gets a targeted page list,
      // otherwise the backend would re-scan the entire book by ratio.
      const byBook = new Map<string, number[]>()
      for (const p of this.selectedPages) {
        const arr = byBook.get(p.bookId) || []
        arr.push(p.pageNumber)
        byBook.set(p.bookId, arr)
      }

      for (const [bookId, pageNumbers] of byBook) {
        try {
          const search = new URLSearchParams()
          search.set('maxRatio', String(this.splitRatio))
          search.set('mode', this.currentMode)
          pageNumbers.forEach(n => search.append('pageNumbers', String(n)))
          const response = await this.$http.post(
            `/api/v1/media-management/oversized-pages/split/${bookId}?${search.toString()}`,
          )
          this.splitResults.push(response.data)
        } catch (e: any) {
          this.splitResults.push({
            bookId,
            bookName: 'Unknown',
            pagesAnalyzed: 0,
            pagesSplit: 0,
            newPagesCreated: 0,
            success: false,
            message: e.message || 'Unknown error',
          })
        }
      }

      this.splitting = false
      this.showResultsDialog = true
      this.selectedPages = []
      await this.loadPages()
    },
    confirmSplitAll() {
      this.showConfirmDialog = true
    },
    async splitAll() {
      this.showConfirmDialog = false
      this.splitting = true
      this.splitResults = []

      try {
        const response = await this.$http.post(
          '/api/v1/media-management/oversized-pages/split-all',
          {maxRatio: this.splitRatio, mode: this.currentMode},
        )
        this.splitResults = response.data
      } catch (e: any) {
        this.$eventHub.$emit('error', {message: e.message})
      }

      this.splitting = false
      if (this.splitResults.length > 0) {
        this.showResultsDialog = true
      }
      await this.loadPages()
    },
    thumbnailUrl(item: OversizedPageDto): string {
      return bookPageThumbnailUrl(item.bookId, item.pageNumber)
    },
    fullImageUrl(item: OversizedPageDto): string {
      return bookPageUrl(item.bookId, item.pageNumber)
    },
    openPreview(item: OversizedPageDto) {
      this.previewItem = item
      this.showPreviewDialog = true
    },
    async ignoreRow(item: OversizedPageDto) {
      try {
        await this.$komgaBooks.ignoreOversizedPage(item.bookId, item.pageNumber, this.currentMode)
        await this.loadPages()
      } catch (e: any) {
        this.$eventHub.$emit('error', {message: e.message})
      }
    },
    async ignoreSelected() {
      if (this.selectedPages.length === 0) return
      try {
        await this.$komgaBooks.ignoreOversizedPagesBatch(
          this.currentMode,
          this.selectedPages.map(p => ({bookId: p.bookId, pageNumber: p.pageNumber})),
        )
        this.selectedPages = []
        await this.loadPages()
      } catch (e: any) {
        this.$eventHub.$emit('error', {message: e.message})
      }
    },
    async ignoreFromPreview() {
      if (!this.previewItem) return
      const item = this.previewItem
      this.showPreviewDialog = false
      await this.ignoreRow(item)
    },
    confirmDeleteRow(item: OversizedPageDto) {
      this.pagesToDelete = [item]
      this.showDeleteDialog = true
    },
    confirmDeleteSelected() {
      if (this.selectedPages.length === 0) return
      this.pagesToDelete = [...this.selectedPages]
      this.showDeleteDialog = true
    },
    deleteFromPreview() {
      if (!this.previewItem) return
      this.pagesToDelete = [this.previewItem]
      this.showPreviewDialog = false
      this.showDeleteDialog = true
    },
    async executeDelete() {
      if (this.pagesToDelete.length === 0) return
      this.deleting = true
      try {
        if (this.pagesToDelete.length === 1) {
          const p = this.pagesToDelete[0]
          await this.$komgaBooks.deleteOversizedPage(p.bookId, p.pageNumber, this.currentMode)
        } else {
          await this.$komgaBooks.deleteOversizedPagesBatch(
            this.currentMode,
            this.pagesToDelete.map(p => ({bookId: p.bookId, pageNumber: p.pageNumber})),
          )
        }
        this.selectedPages = []
        this.pagesToDelete = []
        this.showDeleteDialog = false
        await this.loadPages()
      } catch (e: any) {
        this.$eventHub.$emit('error', {message: e.message})
      } finally {
        this.deleting = false
      }
    },
    formatBytes(bytes: number): string {
      if (bytes === 0) return '0 B'
      const k = 1024
      const sizes = ['B', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i]
    },
  },
})
</script>
