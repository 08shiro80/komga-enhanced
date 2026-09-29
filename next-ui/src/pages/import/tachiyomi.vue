<template>
  <v-container fluid>
    <div class="d-flex align-center mb-1">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Tachiyomi import: title',
            defaultMessage: 'Import Tachiyomi / Mihon backup',
            id: 'fork/import/tachiyomi/title',
          })
        }}
      </span>
    </div>
    <p class="text-body-2 text-medium-emphasis mb-4">
      {{
        $formatMessage({
          description: 'Tachiyomi import: subtitle',
          defaultMessage: 'Import the MangaDex series from a Tachiyomi/Mihon backup (.proto.gz, .tachibk or .json) into a library. They are added to that library\'s followed series.',
          id: 'fork/import/tachiyomi/subtitle',
        })
      }}
    </p>

    <v-card
      variant="outlined"
      max-width="640"
    >
      <v-card-text>
        <v-select
          v-model="libraryId"
          :items="libraryItems"
          :label="$formatMessage(libraryLabel)"
          variant="outlined"
          density="comfortable"
          class="mb-2"
        />
        <v-file-input
          v-model="file"
          :label="$formatMessage(fileLabel)"
          accept=".proto.gz,.tachibk,.json,.gz"
          variant="outlined"
          density="comfortable"
          prepend-icon="i-mdi:file-upload"
        />
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn
          color="primary"
          prepend-icon="i-mdi:import"
          :loading="importing"
          :disabled="!file || !libraryId"
          @click="doImport()"
        >
          {{ $formatMessage(importLabel) }}
        </v-btn>
      </v-card-actions>
    </v-card>

    <v-card
      v-if="result"
      variant="outlined"
      max-width="640"
      class="mt-4"
    >
      <v-card-title class="text-subtitle-1">
        {{ $formatMessage(resultTitle) }}
      </v-card-title>
      <v-card-text>
        <v-alert
          :type="result.success ? 'success' : 'warning'"
          variant="tonal"
          density="compact"
          class="mb-3"
        >
          {{ result.message }}
        </v-alert>
        <div class="d-flex flex-wrap ga-4 text-body-2 mb-2">
          <div>
            <div class="text-caption text-medium-emphasis">
              {{ $formatMessage(statTotal) }}
            </div>
            {{ result.totalInBackup }}
          </div>
          <div>
            <div class="text-caption text-medium-emphasis">
              {{ $formatMessage(statMangaDex) }}
            </div>
            {{ result.mangaDexCount }}
          </div>
          <div>
            <div class="text-caption text-medium-emphasis">
              {{ $formatMessage(statImported) }}
            </div>
            <span class="text-success">{{ result.importedCount }}</span>
          </div>
          <div>
            <div class="text-caption text-medium-emphasis">
              {{ $formatMessage(statSkipped) }}
            </div>
            {{ result.skippedCount }}
          </div>
          <div>
            <div class="text-caption text-medium-emphasis">
              {{ $formatMessage(statErrors) }}
            </div>
            <span :class="result.errorCount ? 'text-error' : ''">{{ result.errorCount }}</span>
          </div>
        </div>

        <v-expansion-panels
          v-if="result.imported.length > 0 || result.errors.length > 0"
          multiple
          variant="accordion"
        >
          <v-expansion-panel v-if="result.imported.length > 0">
            <v-expansion-panel-title>
              {{ $formatMessage(importedTitle, { count: result.imported.length }) }}
            </v-expansion-panel-title>
            <v-expansion-panel-text>
              <div
                v-for="(item, i) in result.imported"
                :key="`imp-${i}`"
                class="text-caption"
              >
                {{ item }}
              </div>
            </v-expansion-panel-text>
          </v-expansion-panel>
          <v-expansion-panel v-if="result.errors.length > 0">
            <v-expansion-panel-title class="text-error">
              {{ $formatMessage(errorsTitle, { count: result.errors.length }) }}
            </v-expansion-panel-title>
            <v-expansion-panel-text>
              <div
                v-for="(err, i) in result.errors"
                :key="`err-${i}`"
                class="text-caption text-error"
              >
                {{ err }}
              </div>
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>
      </v-card-text>
    </v-card>
  </v-container>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useImportTachiyomi } from '@/colada/import'
import { useLibraries } from '@/colada/libraries'
import { useMessagesStore } from '@/stores/messages'
import type { TachiyomiImportResultDto } from '@/generated/openapi'

const messagesStore = useMessagesStore()
const { data: libraries } = useLibraries()

const libraryItems = computed(() =>
  (libraries.value ?? []).map((l) => ({ title: l.name, value: l.id })),
)

const libraryId = ref<string | undefined>(undefined)
const file = ref<File | undefined>(undefined)
const result = ref<TachiyomiImportResultDto | null>(null)

const { mutateAsync: importTachiyomi, isLoading: importing } = useImportTachiyomi()

watchEffect(() => {
  if (!libraryId.value && libraryItems.value.length > 0) {
    libraryId.value = libraryItems.value[0]?.value
  }
})

async function doImport() {
  if (!file.value || !libraryId.value) return
  try {
    result.value = await importTachiyomi({ file: file.value, libraryId: libraryId.value })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const libraryLabel = defineMessage({ description: 'Tachiyomi import: library', defaultMessage: 'Target library', id: 'fork/import/tachiyomi/library' })
const fileLabel = defineMessage({ description: 'Tachiyomi import: file', defaultMessage: 'Backup file (.proto.gz, .tachibk, .json)', id: 'fork/import/tachiyomi/file' })
const importLabel = defineMessage({ description: 'Tachiyomi import: import button', defaultMessage: 'Import', id: 'fork/import/tachiyomi/import' })
const resultTitle = defineMessage({ description: 'Tachiyomi import: result title', defaultMessage: 'Import result', id: 'fork/import/tachiyomi/result' })
const statTotal = defineMessage({ description: 'Tachiyomi import: total', defaultMessage: 'In backup', id: 'fork/import/tachiyomi/stat/total' })
const statMangaDex = defineMessage({ description: 'Tachiyomi import: mangadex', defaultMessage: 'MangaDex', id: 'fork/import/tachiyomi/stat/mangadex' })
const statImported = defineMessage({ description: 'Tachiyomi import: imported', defaultMessage: 'Imported', id: 'fork/import/tachiyomi/stat/imported' })
const statSkipped = defineMessage({ description: 'Tachiyomi import: skipped', defaultMessage: 'Skipped', id: 'fork/import/tachiyomi/stat/skipped' })
const statErrors = defineMessage({ description: 'Tachiyomi import: errors', defaultMessage: 'Errors', id: 'fork/import/tachiyomi/stat/errors' })
const importedTitle = defineMessage({ description: 'Tachiyomi import: imported list', defaultMessage: 'Imported ({count})', id: 'fork/import/tachiyomi/importedList' })
const errorsTitle = defineMessage({ description: 'Tachiyomi import: errors list', defaultMessage: 'Errors ({count})', id: 'fork/import/tachiyomi/errorsList' })
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
