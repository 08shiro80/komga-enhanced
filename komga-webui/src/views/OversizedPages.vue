<template>
  <v-container fluid class="pa-6">
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
          :hint="currentMode === 'wide' ? 'Split threshold — halves in 2 when width &gt; N &times; height' : 'Max height per part (N &times; width)'"
          persistent-hint
          @input="selectedPreset = 'custom'"
        />
      </v-col>
      <v-col cols="12" sm="6" md="5" class="d-flex align-center flex-wrap">
        <v-btn color="primary" @click="searchPages" class="mr-2 mb-2">
          <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-magnify</v-icon>
          <span class="d-none d-sm-inline">Search</span>
        </v-btn>
        <v-btn
          color="warning"
          @click="splitSelected"
          :disabled="selectedPages.length === 0"
          :loading="splitting"
          class="mr-2 mb-2"
        >
          <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-scissors-cutting</v-icon>
          <span class="d-none d-sm-inline">Split Selected&nbsp;</span>
          <span v-if="selectedPages.length > 0">({{ selectedPages.length }})</span>
        </v-btn>
        <v-btn
          color="error"
          @click="confirmSplitAll"
          :disabled="oversizedPages.length === 0"
          :loading="splitting"
          class="mb-2"
        >
          <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-content-cut</v-icon>
          <span class="d-none d-sm-inline">Split All</span>
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
          <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-eye-off</v-icon>
          <span class="d-none d-sm-inline">Ignore Selected&nbsp;</span>
          <span v-if="selectedPages.length > 0">({{ selectedPages.length }})</span>
        </v-btn>
        <v-btn
          color="red darken-2"
          dark
          @click="confirmDeleteSelected"
          :disabled="selectedPages.length === 0"
          class="mr-2 mb-2"
        >
          <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-delete</v-icon>
          <span class="d-none d-sm-inline">Delete Selected&nbsp;</span>
          <span v-if="selectedPages.length > 0">({{ selectedPages.length }})</span>
        </v-btn>
        <v-btn
          text
          small
          @click="toggleSelectAll"
          class="mr-2 mb-2"
          :disabled="oversizedPages.length === 0"
        >
          <v-icon :left="$vuetify.breakpoint.smAndUp" small>{{ allSelected ? 'mdi-checkbox-multiple-marked' : 'mdi-checkbox-multiple-blank-outline' }}</v-icon>
          <span class="d-none d-sm-inline">{{ allSelected ? 'Deselect page' : 'Select page' }}</span>
        </v-btn>
        <v-switch
          v-model="includeIgnored"
          label="Show ignored"
          hide-details
          dense
          class="ml-2 mb-2 mt-0"
          @change="searchPages"
        />
        <v-spacer />
        <v-btn icon @click="searchPages" :loading="loading">
          <v-icon>mdi-refresh</v-icon>
        </v-btn>
      </v-col>
    </v-row>

    <v-row align="center" class="mb-2">
      <v-col cols="12" sm="6" md="5">
        <v-text-field
          v-model="searchQuery"
          label="Filter by series or book name"
          prepend-inner-icon="mdi-magnify"
          clearable
          dense
          hide-details
          @input="onSearchInput"
          @click:clear="onSearchClear"
        />
      </v-col>
      <v-col cols="12" sm="6" md="7" class="d-flex align-center flex-wrap">
        <span class="d-none d-sm-inline text-caption mr-2">Sort:</span>
        <v-btn-toggle v-model="sortKey" mandatory dense @change="loadPages">
          <v-btn small value="ratio">Ratio</v-btn>
          <v-btn small value="fileSize">Size</v-btn>
          <v-btn small value="seriesTitle">Series</v-btn>
          <v-btn small value="bookName">Book</v-btn>
          <v-btn small value="pageNumber">Page</v-btn>
        </v-btn-toggle>
        <v-btn icon small @click="toggleSortDir" class="ml-1">
          <v-icon small>{{ sortDesc ? 'mdi-sort-descending' : 'mdi-sort-ascending' }}</v-icon>
        </v-btn>
        <v-spacer />
        <span class="d-none d-sm-inline text-caption mr-2">Per page:</span>
        <v-select
          v-model="pageSize"
          :items="[20, 50, 100, 250, 500]"
          dense
          hide-details
          style="max-width: 90px"
          @change="loadPages"
        />
      </v-col>
    </v-row>

    <v-row v-if="loading" justify="center" class="my-8">
      <v-progress-circular indeterminate color="primary" />
    </v-row>

    <v-row v-else-if="oversizedPages.length === 0" justify="center" class="my-8">
      <span class="text-subtitle-1 grey--text">No oversized pages found.</span>
    </v-row>

    <v-row v-else>
      <v-slide-x-transition group hide-on-leave style="width: 100%; display: flex; flex-wrap: wrap">
        <v-card
          v-for="item in oversizedPages"
          :key="item.rowKey"
          class="ma-2"
          :style="{width: $vuetify.breakpoint.xsOnly ? '100%' : '480px'}"
          :class="isSelected(item) ? 'elevation-6' : 'elevation-1'">
            <v-row no-gutters>
              <v-col cols="auto" class="pa-2" style="position: relative">
                <v-checkbox
                  :input-value="isSelected(item)"
                  @change="toggleSelect(item)"
                  hide-details
                  dense
                  class="ma-0 pa-0"
                  style="position: absolute; top: 4px; left: 4px; z-index: 2; background: rgba(255,255,255,0.7); border-radius: 4px"
                />
                <v-img
                  :src="thumbnailUrl(item)"
                  :width="220"
                  :height="320"
                  contain
                  style="cursor: zoom-in; background: #111"
                  @click="openPreview(item)"
                >
                  <template v-slot:placeholder>
                    <v-row class="fill-height ma-0" align="center" justify="center">
                      <v-progress-circular indeterminate size="24" />
                    </v-row>
                  </template>
                </v-img>
              </v-col>
              <v-col class="pa-3 d-flex flex-column">
                <div class="text-caption grey--text">Series</div>
                <router-link
                  :to="{name: 'browse-series', params: {seriesId: item.seriesId}}"
                  class="text-body-2 text-truncate"
                  style="max-width: 100%"
                >
                  {{ item.seriesTitle }}
                </router-link>

                <div class="text-caption grey--text mt-2">Book</div>
                <router-link
                  :to="{name: 'browse-book', params: {bookId: item.bookId, seriesId: item.seriesId}}"
                  class="text-body-2 text-truncate"
                  style="max-width: 100%"
                >
                  {{ item.bookName }}
                </router-link>

                <v-row no-gutters class="mt-2">
                  <v-col cols="6">
                    <div class="text-caption grey--text">Page #</div>
                    <div class="text-body-2">{{ item.pageNumber }}</div>
                  </v-col>
                  <v-col cols="6">
                    <div class="text-caption grey--text">Ratio</div>
                    <div class="text-body-2">{{ item.ratio }}:1</div>
                  </v-col>
                </v-row>

                <v-row no-gutters class="mt-2">
                  <v-col cols="6">
                    <div class="text-caption grey--text">Dimensions</div>
                    <div class="text-body-2">{{ item.width }} &times; {{ item.height }}</div>
                  </v-col>
                  <v-col cols="6">
                    <div class="text-caption grey--text">File Size</div>
                    <div class="text-body-2">{{ formatBytes(item.fileSize) }}</div>
                  </v-col>
                </v-row>

                <v-row no-gutters class="mt-2">
                  <v-col cols="12">
                    <div class="text-caption grey--text">Split into</div>
                    <div class="text-body-2">{{ splitPreviewParts(item) }} parts</div>
                  </v-col>
                </v-row>

                <v-spacer />

                <v-card-actions class="pa-0 mt-2">
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
                </v-card-actions>
              </v-col>
            </v-row>
          </v-card>
      </v-slide-x-transition>
    </v-row>

    <v-row v-if="pageCount > 1" justify="center" class="mt-4">
      <v-pagination v-model="page" :length="pageCount" :total-visible="9" @input="loadPages" />
    </v-row>

    <!-- Confirm Split All Dialog -->
    <v-dialog v-model="showConfirmDialog" max-width="500" :fullscreen="$vuetify.breakpoint.xsOnly">
      <v-card>
        <v-card-title class="headline warning white--text">
          <v-icon left dark>mdi-alert</v-icon>
          {{ currentMode === 'wide' ? 'Split All Double Pages?' : 'Split All Tall Pages?' }}
        </v-card-title>
        <v-card-text class="pt-4">
          <p v-if="currentMode === 'wide'">This will split all pages with width:height ratio
            above <strong>{{ detectRatio }}:1</strong> in half (2 parts) whenever the ratio
            exceeds <strong>{{ splitRatio }}:1</strong>.</p>
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
    <v-dialog v-model="showPreviewDialog" max-width="90vw" :fullscreen="$vuetify.breakpoint.xsOnly">
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
    <v-dialog v-model="showDeleteDialog" max-width="540" :fullscreen="$vuetify.breakpoint.xsOnly">
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
    <v-dialog v-model="showResultsDialog" max-width="700" :fullscreen="$vuetify.breakpoint.xsOnly">
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
      oversizedPages: [] as (OversizedPageDto & {rowKey: string})[],
      selectedPages: [] as (OversizedPageDto & {rowKey: string})[],
      totalElements: 0,
      page: 1,
      pageSize: this.$store?.state?.persistedState?.dataTablePageSize || 20,
      sortKey: 'ratio',
      sortDesc: true,
      loading: true,
      splitting: false,
      selectedPreset: 'webtoon',
      currentMode: 'tall' as SplitMode,
      detectRatio: 3,
      splitRatio: 1.5,
      presets: PRESETS,
      splitResults: [] as SplitResult[],
      showResultsDialog: false,
      showConfirmDialog: false,
      showPreviewDialog: false,
      previewItem: null as (OversizedPageDto & {rowKey: string}) | null,
      includeIgnored: false,
      showDeleteDialog: false,
      pagesToDelete: [] as (OversizedPageDto & {rowKey: string})[],
      deleting: false,
      searchQuery: '',
      searchDebounce: null as number | null,
    }
  },
  computed: {
    pageCount(): number {
      if (this.pageSize <= 0) return 0
      return Math.ceil(this.totalElements / this.pageSize)
    },
    allSelected(): boolean {
      return this.oversizedPages.length > 0 && this.selectedPages.length === this.oversizedPages.length
    },
    previewImgStyle(): object {
      return {
        maxWidth: '100%',
        maxHeight: '80vh',
        objectFit: 'contain',
      }
    },
  },
  mounted() {
    this.loadPages()
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
      this.page = 1
      this.loadPages()
    },
    searchPages() {
      this.page = 1
      this.loadPages()
    },
    onSearchInput() {
      if (this.searchDebounce) window.clearTimeout(this.searchDebounce)
      this.searchDebounce = window.setTimeout(() => {
        this.page = 1
        this.loadPages()
      }, 350)
    },
    onSearchClear() {
      this.searchQuery = ''
      this.page = 1
      this.loadPages()
    },
    toggleSortDir() {
      this.sortDesc = !this.sortDesc
      this.loadPages()
    },
    splitPreviewParts(item: OversizedPageDto): number {
      if (this.splitRatio <= 0) return 1
      if (this.currentMode === 'wide') {
        return item.width > item.height * this.splitRatio ? 2 : 1
      }
      return Math.ceil(item.height / (item.width * this.splitRatio))
    },
    isSelected(item: OversizedPageDto & {rowKey: string}): boolean {
      return this.selectedPages.some(p => p.rowKey === item.rowKey)
    },
    toggleSelect(item: OversizedPageDto & {rowKey: string}) {
      const idx = this.selectedPages.findIndex(p => p.rowKey === item.rowKey)
      if (idx >= 0) this.selectedPages.splice(idx, 1)
      else this.selectedPages.push(item)
    },
    toggleSelectAll() {
      if (this.allSelected) this.selectedPages = []
      else this.selectedPages = [...this.oversizedPages]
    },
    async loadPages() {
      this.loading = true
      if (this.pageSize) {
        this.$store.commit('setDataTablePageSize', this.pageSize)
      }

      const pageRequest = {
        page: this.page - 1,
        size: this.pageSize,
        sort: [`${this.sortKey},${this.sortDesc ? 'desc' : 'asc'}`],
      } as PageRequest

      try {
        const pageResult = await this.$komgaBooks.getOversizedPages(
          this.detectRatio,
          this.currentMode,
          this.includeIgnored,
          pageRequest,
          this.searchQuery,
        )
        this.oversizedPages = pageResult.content.map(p => ({
          ...p,
          rowKey: `${p.bookId}_${p.pageNumber}`,
        }))
        this.totalElements = pageResult.totalElements
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
    openPreview(item: OversizedPageDto & {rowKey: string}) {
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
    confirmDeleteRow(item: OversizedPageDto & {rowKey: string}) {
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
