<template>
  <div>
    <p class="text-body-2 mb-3">
      {{ $formatMessage(seriesLabel) }} <strong>{{ series.metadata.title }}</strong>
    </p>

    <v-btn-toggle
      v-model="mode"
      mandatory
      density="compact"
      variant="outlined"
      divided
      class="mb-3"
    >
      <v-btn
        value="single"
        size="small"
      >
        {{ $formatMessage(modeSingleLabel) }}
      </v-btn>
      <v-btn
        value="range"
        size="small"
      >
        {{ $formatMessage(modeRangeLabel) }}
      </v-btn>
    </v-btn-toggle>

    <v-text-field
      v-model="url"
      :label="$formatMessage(urlLabel)"
      :placeholder="mode === 'single' ? 'https://mangadex.org/chapter/…' : 'https://mangadex.org/title/…'"
      variant="outlined"
      density="comfortable"
      autofocus
    />

    <v-text-field
      v-if="mode === 'range'"
      v-model="chapterRange"
      :label="$formatMessage(rangeLabel)"
      placeholder="1-10, 15, 20-"
      variant="outlined"
      density="comfortable"
    />

    <v-checkbox
      v-model="skipIfChapterExists"
      :label="$formatMessage(skipLabel)"
      density="compact"
      hide-details
      class="mb-2"
    />

    <template v-if="mode === 'single'">
      <v-checkbox
        v-model="useCustomNaming"
        :label="$formatMessage(customNamingLabel)"
        density="compact"
        hide-details
      />
      <v-row
        v-if="useCustomNaming"
        dense
        class="mt-1"
      >
        <v-col cols="12">
          <v-text-field
            v-model="filename"
            :label="$formatMessage(filenameLabel)"
            variant="outlined"
            density="compact"
            hide-details
          />
        </v-col>
        <v-col
          cols="12"
          sm="6"
        >
          <v-text-field
            v-model="chapterNumber"
            :label="$formatMessage(chapterNumberLabel)"
            variant="outlined"
            density="compact"
            hide-details
          />
        </v-col>
        <v-col
          cols="12"
          sm="6"
        >
          <v-text-field
            v-model="volume"
            :label="$formatMessage(volumeLabel)"
            variant="outlined"
            density="compact"
            hide-details
          />
        </v-col>
        <v-col cols="12">
          <v-text-field
            v-model="chapterTitle"
            :label="$formatMessage(chapterTitleLabel)"
            variant="outlined"
            density="compact"
            hide-details
          />
        </v-col>
      </v-row>
    </template>

    <div class="d-flex justify-end mt-4">
      <v-btn
        color="primary"
        prepend-icon="i-mdi:download"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="submit()"
      >
        {{ $formatMessage(addLabel) }}
      </v-btn>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { defineMessage } from 'vue-intl'
import { useCreateDownload } from '@/colada/downloads'
import { useMessagesStore } from '@/stores/messages'
import type { DownloadCreateDto, SeriesDto } from '@/generated/openapi'

const props = defineProps<{ series: SeriesDto }>()

const messagesStore = useMessagesStore()
const { mutateAsync: createDownload, isLoading: submitting } = useCreateDownload()

const mode = ref<'single' | 'range'>('single')
const url = ref('')
const chapterRange = ref('')
const skipIfChapterExists = ref(true)
const useCustomNaming = ref(false)
const filename = ref('')
const chapterNumber = ref('')
const volume = ref('')
const chapterTitle = ref('')

const canSubmit = computed(() => {
  if (!url.value) return false
  if (mode.value === 'range') return !!chapterRange.value
  if (useCustomNaming.value) return !!filename.value && !!chapterNumber.value
  return true
})

function reset() {
  url.value = ''
  chapterRange.value = ''
  useCustomNaming.value = false
  filename.value = ''
  chapterNumber.value = ''
  volume.value = ''
  chapterTitle.value = ''
}

async function submit() {
  if (!canSubmit.value) return
  const body: DownloadCreateDto = {
    sourceUrl: url.value,
    title: props.series.metadata.title,
    libraryId: props.series.libraryId,
    seriesId: props.series.id,
    priority: 5,
    skipIfChapterExists: skipIfChapterExists.value,
  }
  if (mode.value === 'range') {
    body.chapterRange = chapterRange.value
  } else if (useCustomNaming.value) {
    body.customFilename = filename.value
    body.customChapterNumber = chapterNumber.value
    if (volume.value) body.customVolume = volume.value
    if (chapterTitle.value) body.customChapterTitle = chapterTitle.value
  }
  try {
    await createDownload(body)
    messagesStore.messages.push({ message: queuedMessage })
    reset()
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const seriesLabel = defineMessage({ description: 'Add chapter download: series', defaultMessage: 'Series:', id: 'fork/addchapter/series' })
const modeSingleLabel = defineMessage({ description: 'Add chapter download: single mode', defaultMessage: 'Single chapter', id: 'fork/addchapter/single' })
const modeRangeLabel = defineMessage({ description: 'Add chapter download: range mode', defaultMessage: 'Range (from series URL)', id: 'fork/addchapter/range' })
const urlLabel = defineMessage({ description: 'Add chapter download: url', defaultMessage: 'Source URL', id: 'fork/addchapter/url' })
const rangeLabel = defineMessage({ description: 'Add chapter download: range field', defaultMessage: 'Chapter range', id: 'fork/addchapter/rangeField' })
const skipLabel = defineMessage({ description: 'Add chapter download: skip', defaultMessage: 'Skip if chapter already exists', id: 'fork/addchapter/skip' })
const customNamingLabel = defineMessage({ description: 'Add chapter download: custom naming', defaultMessage: 'Custom naming', id: 'fork/addchapter/customNaming' })
const filenameLabel = defineMessage({ description: 'Add chapter download: filename', defaultMessage: 'Filename', id: 'fork/addchapter/filename' })
const chapterNumberLabel = defineMessage({ description: 'Add chapter download: chapter number', defaultMessage: 'Chapter number', id: 'fork/addchapter/chapterNumber' })
const volumeLabel = defineMessage({ description: 'Add chapter download: volume', defaultMessage: 'Volume (optional)', id: 'fork/addchapter/volume' })
const chapterTitleLabel = defineMessage({ description: 'Add chapter download: chapter title', defaultMessage: 'Chapter title (optional)', id: 'fork/addchapter/chapterTitle' })
const addLabel = defineMessage({ description: 'Add chapter download: add button', defaultMessage: 'Add download', id: 'fork/addchapter/add' })
const queuedMessage = defineMessage({ description: 'Add chapter download: queued', defaultMessage: 'Download queued', id: 'fork/addchapter/queued' })
</script>
