<template>
  <v-container fluid class="pa-6">
    <!-- Mobile card layout -->
    <div v-if="$vuetify.breakpoint.smAndDown">
      <div class="d-flex align-center mb-2">
        <v-btn icon @click="loadBooks" :loading="loading">
          <v-icon>mdi-refresh</v-icon>
        </v-btn>
        <span class="text-caption ml-2">{{ totalBooks }} total</span>
      </div>
      <div v-if="loading" class="text-center my-8">
        <v-progress-circular indeterminate color="primary" />
      </div>
      <div v-else-if="books.length === 0" class="text-center my-8 grey--text">
        No duplicates.
      </div>
      <div v-else>
        <div v-for="group in groupedBooks" :key="group.hash" class="mb-4">
          <div class="text-caption grey--text mb-1">
            <v-icon x-small>mdi-fingerprint</v-icon>
            <code>{{ group.hash }}</code>
            <span class="ml-1">({{ group.items.length }})</span>
          </div>
          <v-card
            v-for="item in group.items"
            :key="item.id"
            outlined
            class="mb-2"
          >
            <v-card-text class="pa-3">
              <div class="d-flex align-start">
                <div class="flex-grow-1" style="min-width: 0">
                  <router-link
                    :to="{name: item.oneshot ? 'browse-oneshot' : 'browse-book', params: {bookId: item.id, seriesId: item.seriesId}}"
                    class="text-body-2 d-block"
                    style="word-break: break-all"
                  >
                    {{ item.url }}
                  </router-link>
                  <div class="text-caption text--secondary mt-1">{{ item.size }}</div>
                </div>
                <v-btn icon color="error" @click="promptDeleteBook(item)" class="ml-2 flex-shrink-0">
                  <v-icon>mdi-trash-can-outline</v-icon>
                </v-btn>
              </div>
              <v-chip
                v-if="item.deleted"
                label
                x-small
                color="error"
                class="mt-1"
              >
                {{ $t('common.unavailable') }}
              </v-chip>
            </v-card-text>
          </v-card>
        </div>
      </div>
    </div>

    <!-- Desktop table layout -->
    <v-data-table
      v-else
      :headers="headers"
      :items="books"
      :options.sync="options"
      :server-items-length="totalBooks"
      :loading="loading"
      sort-by="fileHash"
      multi-sort
      show-group-by
      group-by="fileHash"
      class="elevation-1"
      :footer-props="{
        itemsPerPageOptions: [20, 50, 100]
      }"
    >
      <template v-slot:item.url="{ item }">
        <router-link
          :to="{name: item.oneshot ? 'browse-oneshot' : 'browse-book', params: {bookId: item.id, seriesId: item.seriesId}}">
          {{ item.url }}
        </router-link>
      </template>

      <template v-slot:item.deleted="{ item }">
        <v-chip
          v-if="item.deleted"
          label small color="error">
          {{ $t('common.unavailable') }}
        </v-chip>
      </template>

      <template v-slot:item.id="{ item }">
        <v-btn
          icon
          color="error"
          @click="promptDeleteBook(item)"
        >
          <v-icon>mdi-trash-can-outline</v-icon>
        </v-btn>
      </template>

      <template v-slot:footer.prepend>
        <v-btn icon @click="loadBooks"><v-icon>mdi-refresh</v-icon></v-btn>
      </template>
    </v-data-table>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import {BookDto} from '@/types/komga-books'

export default Vue.extend({
  name: 'DuplicateFiles',
  data: function () {
    return {
      books: [] as BookDto[],
      totalBooks: 0,
      loading: true,
      options: {
        itemsPerPage: this.$store?.state?.persistedState?.dataTablePageSize || 20,
      } as any,
    }
  },
  watch: {
    options: {
      handler() {
        if (this.options.itemsPerPage) {
          this.$store.commit('setDataTablePageSize', this.options.itemsPerPage)
        }
        this.loadBooks()
      },
      deep: true,
    },
  },
  computed: {
    headers(): object[] {
      return [
        {text: this.$i18n.t('duplicates.file_hash').toString(), value: 'fileHash'},
        {text: this.$i18n.t('duplicates.url').toString(), value: 'url', groupable: false},
        {text: this.$i18n.t('duplicates.size').toString(), value: 'size', groupable: false},
        {text: '', value: 'deleted', groupable: false, sortable: false},
        {text: this.$i18n.t('menu.delete').toString(), value: 'id', groupable: false},
      ]
    },
    groupedBooks(): {hash: string; items: BookDto[]}[] {
      const map = new Map<string, BookDto[]>()
      for (const b of this.books) {
        const key = (b as any).fileHash || ''
        const arr = map.get(key) || []
        arr.push(b)
        map.set(key, arr)
      }
      return Array.from(map.entries()).map(([hash, items]) => ({hash, items}))
    },
  },
  methods: {
    async loadBooks() {
      this.loading = true

      const {sortBy, sortDesc, page, itemsPerPage} = this.options

      const pageRequest = {
        page: page - 1,
        size: itemsPerPage,
        sort: [],
      } as PageRequest

      for (let i = 0; i < sortBy.length; i++) {
        pageRequest.sort!!.push(`${sortBy[i]},${sortDesc[i] ? 'desc' : 'asc'}`)
      }

      const booksPage = await this.$komgaBooks.getDuplicateBooks(pageRequest)
      this.totalBooks = booksPage.totalElements
      this.books = booksPage.content

      this.loading = false
    },
    promptDeleteBook(book: BookDto) {
      this.$store.dispatch('dialogDeleteBook', book)
    },
  },
})
</script>

<style scoped>

</style>
