<template>
  <v-container fluid>
    <div class="d-flex align-center flex-wrap ga-2 mb-4">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Discover view: title',
            defaultMessage: 'Discover on MangaDex',
            id: 'fork/discover/title',
          })
        }}
      </span>
      <v-spacer />
      <v-select
        v-model="selectedLibrary"
        :items="libraryItems"
        :label="$formatMessage(libraryLabel)"
        variant="outlined"
        density="compact"
        hide-details
        :style="display.xs.value ? undefined : 'max-width: 280px'"
      />
    </div>

    <p class="text-body-2 text-medium-emphasis mb-4">
      {{
        $formatMessage({
          description: 'Discover view: subtitle',
          defaultMessage: 'Search MangaDex and download or follow series into the selected library.',
          id: 'fork/discover/subtitle',
        })
      }}
    </p>

    <MangaDexSearch
      v-if="selectedLibrary"
      :library-id="selectedLibrary"
    />
    <v-alert
      v-else
      type="info"
      variant="tonal"
    >
      {{
        $formatMessage({
          description: 'Discover view: no library',
          defaultMessage: 'Select a library to download discovered series into.',
          id: 'fork/discover/noLibrary',
        })
      }}
    </v-alert>
  </v-container>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useDisplay } from 'vuetify'
import { useLibraries } from '@/colada/libraries'
import MangaDexSearch from '@/components/downloads/MangaDexSearch.vue'

const display = useDisplay()

const { data: libraries } = useLibraries()

const libraryItems = computed(() =>
  (libraries.value ?? []).map((l) => ({ title: l.name, value: l.id })),
)

const selectedLibrary = ref<string | undefined>(undefined)

watchEffect(() => {
  if (!selectedLibrary.value && libraryItems.value.length > 0) {
    selectedLibrary.value = libraryItems.value[0]?.value
  }
})

const libraryLabel = defineMessage({
  description: 'Discover: library selector',
  defaultMessage: 'Library',
  id: 'fork/discover/library',
})
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
