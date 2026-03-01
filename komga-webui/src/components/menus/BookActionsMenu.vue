<template>
  <div>
    <v-menu offset-y v-model="menuState">
      <template v-slot:activator="{ on }">
        <v-btn icon v-on="on" @click.prevent="">
          <v-icon>mdi-dots-vertical</v-icon>
        </v-btn>
      </template>
      <v-list dense>
        <v-list-item @click="analyze" v-if="isAdmin">
          <v-list-item-title>{{ $t('menu.analyze') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="refreshMetadata" v-if="isAdmin">
          <v-list-item-title>{{ $t('menu.refresh_metadata') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="searchMetadata" v-if="isAdmin">
          <v-list-item-title>Search Online Metadata</v-list-item-title>
        </v-list-item>
        <v-list-item @click="addToReadList" v-if="isAdmin">
          <v-list-item-title>{{ $t('menu.add_to_readlist') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="markRead" v-if="!isRead">
          <v-list-item-title>{{ $t('menu.mark_read') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="markUnread" v-if="!isUnread">
          <v-list-item-title>{{ $t('menu.mark_unread') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="toggleBlacklist" v-if="isAdmin">
          <v-list-item-title>{{ isBlacklisted ? 'Remove from Blacklist' : 'Blacklist & Delete' }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="promptDeleteBook" class="list-danger" v-if="isAdmin">
          <v-list-item-title>{{ $t('menu.delete') }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-menu>
  </div>
</template>
<script lang="ts">
import {getReadProgress} from '@/functions/book-progress'
import {ReadStatus} from '@/types/enum-books'
import Vue from 'vue'
import {BookDto, ReadProgressUpdateDto} from '@/types/komga-books'

export default Vue.extend({
  name: 'BookActionsMenu',
  data: () => {
    return {
      menuState: false,
      isBlacklisted: false,
    }
  },
  props: {
    book: {
      type: Object as () => BookDto,
      required: true,
    },
    menu: {
      type: Boolean,
      default: false,
    },
  },
  watch: {
    menuState (val) {
      this.$emit('update:menu', val)
      if (val && this.isAdmin) {
        this.$komgaBooks.isBookBlacklisted(this.book.id).then((result: boolean) => {
          this.isBlacklisted = result
        })
      }
    },
  },
  computed: {
    isAdmin (): boolean {
      return this.$store.getters.meAdmin
    },
    isRead (): boolean {
      return getReadProgress(this.book) === ReadStatus.READ
    },
    isUnread (): boolean {
      return getReadProgress(this.book) === ReadStatus.UNREAD
    },
  },
  methods: {
    analyze () {
      this.$komgaBooks.analyzeBook(this.book)
    },
    refreshMetadata () {
      this.$komgaBooks.refreshMetadata(this.book)
    },
    searchMetadata () {
      this.$emit('search-metadata')
    },
    addToReadList () {
      this.$store.dispatch('dialogAddBooksToReadList', [this.book.id])
    },
    async markRead () {
      const readProgress = { completed: true } as ReadProgressUpdateDto
      await this.$komgaBooks.updateReadProgress(this.book.id, readProgress)
    },
    async markUnread () {
      await this.$komgaBooks.deleteReadProgress(this.book.id)
    },
    async toggleBlacklist () {
      try {
        if (this.isBlacklisted) {
          await this.$komgaBooks.unblacklistBook(this.book.id)
          this.isBlacklisted = false
        } else {
          await this.$komgaBooks.blacklistBook(this.book.id)
          this.isBlacklisted = true
          await this.$komgaBooks.deleteBook(this.book.id)
        }
      } catch (e) {
        this.$eventHub.$emit('showSnackbar', {message: e.message, color: 'error'})
      }
    },
    promptDeleteBook () {
      this.$store.dispatch('dialogDeleteBook', this.book)
    },
  },
})
</script>
