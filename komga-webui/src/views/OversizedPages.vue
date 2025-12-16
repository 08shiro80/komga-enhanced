<template>
  <v-container fluid class="pa-6">
    <v-data-table
      :headers="headers"
      :items="oversizedPages"
      :options.sync="options"
      :server-items-length="totalPages"
      :loading="loading"
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
                Scan for pages with very high resolutions that may cause performance issues or excessive storage usage.
              </v-alert>
            </v-col>
          </v-row>
          <v-row>
            <v-col cols="12" sm="6" md="3">
              <v-text-field
                v-model.number="filterMinWidth"
                label="Minimum Width (px)"
                type="number"
                filled
                dense
                :min="1000"
                :max="20000"
              />
            </v-col>
            <v-col cols="12" sm="6" md="3">
              <v-text-field
                v-model.number="filterMinHeight"
                label="Minimum Height (px)"
                type="number"
                filled
                dense
                :min="1000"
                :max="20000"
              />
            </v-col>
            <v-col cols="12" sm="6" md="3" class="d-flex align-center">
              <v-btn color="primary" @click="loadPages">
                <v-icon left>mdi-magnify</v-icon>
                Search
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
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import {OversizedPageDto} from '@/types/komga-books'

export default Vue.extend({
  name: 'OversizedPages',
  data: function () {
    return {
      oversizedPages: [] as OversizedPageDto[],
      totalPages: 0,
      loading: true,
      options: {} as any,
      filterMinWidth: 4000,
      filterMinHeight: 4000,
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
