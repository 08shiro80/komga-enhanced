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
      item-key="bookId"
      class="elevation-1"
      :footer-props="{
        itemsPerPageOptions: [20, 50, 100]
      }"
    >
      <template v-slot:top>
        <v-container>
          <v-row>
            <v-col cols="12">
              <v-alert type="info" dismissible text class="body-2">
                Scan for pages with very high resolutions. You can split tall images into multiple pages
                (like TachiyomiSY's "split tall images" feature).
              </v-alert>
            </v-col>
          </v-row>
          <v-row>
            <v-col cols="12" sm="6" md="2">
              <v-text-field
                v-model.number="filterMinWidth"
                label="Min Width (px)"
                type="number"
                filled
                dense
                :min="1000"
                :max="20000"
              />
            </v-col>
            <v-col cols="12" sm="6" md="2">
              <v-text-field
                v-model.number="filterMinHeight"
                label="Min Height (px)"
                type="number"
                filled
                dense
                :min="1000"
                :max="20000"
              />
            </v-col>
            <v-col cols="12" sm="6" md="2">
              <v-text-field
                v-model.number="splitMaxHeight"
                label="Split Height (px)"
                type="number"
                filled
                dense
                :min="500"
                :max="10000"
                hint="Max height per page when splitting"
                persistent-hint
              />
            </v-col>
            <v-col cols="12" sm="6" md="4" class="d-flex align-center">
              <v-btn color="primary" @click="loadPages" class="mr-2">
                <v-icon left>mdi-magnify</v-icon>
                Search
              </v-btn>
              <v-btn
                color="warning"
                @click="splitSelected"
                :disabled="selectedPages.length === 0"
                :loading="splitting"
                class="mr-2"
              >
                <v-icon left>mdi-scissors-cutting</v-icon>
                Split Selected ({{ selectedPages.length }})
              </v-btn>
              <v-btn
                color="error"
                @click="confirmSplitAll"
                :disabled="oversizedPages.length === 0"
                :loading="splitting"
              >
                <v-icon left>mdi-content-cut</v-icon>
                Split All
              </v-btn>
            </v-col>
          </v-row>
        </v-container>
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
        {{ item.width }} x {{ item.height }}
      </template>

      <template v-slot:item.totalPixels="{ item }">
        {{ formatNumber(item.width * item.height) }}
      </template>

      <template v-slot:item.fileSize="{ item }">
        {{ formatBytes(item.fileSize) }}
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
          Split All Tall Pages?
        </v-card-title>
        <v-card-text class="pt-4">
          <p>This will split all pages taller than <strong>{{ splitMaxHeight }}px</strong> into multiple pages.</p>
          <p class="mb-0">This operation modifies your book files and cannot be undone.</p>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="showConfirmDialog = false">Cancel</v-btn>
          <v-btn color="warning" @click="splitAll">Split All</v-btn>
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

interface SplitResult {
  bookId: string
  bookName: string
  pagesAnalyzed: number
  pagesSplit: number
  newPagesCreated: number
  success: boolean
  message: string
}

export default Vue.extend({
  name: 'OversizedPages',
  data: function () {
    return {
      oversizedPages: [] as OversizedPageDto[],
      selectedPages: [] as OversizedPageDto[],
      totalPages: 0,
      loading: true,
      splitting: false,
      options: {} as any,
      filterMinWidth: 4000,
      filterMinHeight: 4000,
      splitMaxHeight: 2000,
      splitResults: [] as SplitResult[],
      showResultsDialog: false,
      showConfirmDialog: false,
    }
  },
  watch: {
    options: {
      handler() {
        this.loadPages()
      },
      deep: true,
    },
  },
  computed: {
    headers(): object[] {
      return [
        {text: 'Series', value: 'seriesTitle'},
        {text: 'Book', value: 'bookName'},
        {text: 'Page #', value: 'pageNumber', width: '100px'},
        {text: 'Dimensions', value: 'dimensions', sortable: false},
        {text: 'Total Pixels', value: 'totalPixels', sortable: false},
        {text: 'File Size', value: 'fileSize'},
        {text: 'Media Type', value: 'mediaType'},
      ]
    },
  },
  methods: {
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
          this.filterMinWidth,
          this.filterMinHeight,
          pageRequest,
        )
        this.oversizedPages = page.content
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

      // Get unique book IDs
      const uniqueBookIds = [...new Set(this.selectedPages.map(p => p.bookId))]

      for (const bookId of uniqueBookIds) {
        try {
          const response = await this.$http.post(
            `/api/v1/media-management/oversized-pages/split/${bookId}`,
            null,
            { params: { maxHeight: this.splitMaxHeight } },
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
          { maxHeight: this.splitMaxHeight },
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
    formatBytes(bytes: number): string {
      if (bytes === 0) return '0 B'
      const k = 1024
      const sizes = ['B', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i]
    },
    formatNumber(num: number): string {
      return num.toLocaleString()
    },
  },
})
</script>
