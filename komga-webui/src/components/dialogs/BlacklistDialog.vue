<template>
  <v-dialog v-model="modal" max-width="800">
    <v-card>
      <v-card-title>Manage Blacklisted Chapters</v-card-title>
      <v-btn icon absolute top right @click="dialogClose">
        <v-icon>mdi-close</v-icon>
      </v-btn>

      <v-card-text>
        <v-container fluid>
          <v-row v-if="loading">
            <v-col class="text-center">
              <v-progress-circular indeterminate/>
            </v-col>
          </v-row>

          <v-row v-else-if="chapters.length === 0">
            <v-col class="text-center grey--text">
              No blacklisted chapters for this series.
            </v-col>
          </v-row>

          <v-simple-table v-else>
            <thead>
              <tr>
                <th>Chapter</th>
                <th>Title</th>
                <th>URL</th>
                <th>Blacklisted</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="chapter in chapters" :key="chapter.id">
                <td>{{ chapter.chapterNumber || '-' }}</td>
                <td>{{ chapter.chapterTitle || '-' }}</td>
                <td class="text-truncate" style="max-width: 250px;">
                  <a :href="chapter.chapterUrl" target="_blank" rel="noopener">{{ chapter.chapterUrl }}</a>
                </td>
                <td>{{ formatDate(chapter.createdDate) }}</td>
                <td>
                  <v-btn icon small color="error" @click="removeChapter(chapter)">
                    <v-icon small>mdi-delete</v-icon>
                  </v-btn>
                </td>
              </tr>
            </tbody>
          </v-simple-table>
        </v-container>
      </v-card-text>

      <v-card-actions>
        <v-spacer/>
        <v-btn text @click="dialogClose">Close</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'

export default Vue.extend({
  name: 'BlacklistDialog',
  data: function () {
    return {
      modal: false,
      loading: false,
      chapters: [] as any[],
    }
  },
  props: {
    value: Boolean,
    seriesId: {
      type: String,
      required: true,
    },
  },
  watch: {
    value (val) {
      this.modal = val
      if (val) {
        this.loadChapters()
      }
    },
    modal (val) {
      !val && this.dialogClose()
    },
  },
  methods: {
    dialogClose () {
      this.$emit('input', false)
    },
    async loadChapters () {
      this.loading = true
      try {
        this.chapters = await this.$komgaSeries.getBlacklist(this.seriesId)
      } catch (e) {
        this.chapters = []
      } finally {
        this.loading = false
      }
    },
    async removeChapter (chapter: any) {
      try {
        await this.$komgaSeries.removeBlacklist(this.seriesId, chapter.id)
        this.chapters = this.chapters.filter((c: any) => c.id !== chapter.id)
      } catch (e) {
        this.$eventHub.$emit('showSnackbar', {message: e.message, color: 'error'})
      }
    },
    formatDate (dateStr: string): string {
      if (!dateStr) return '-'
      try {
        return new Date(dateStr).toLocaleDateString()
      } catch {
        return dateStr
      }
    },
  },
})
</script>
