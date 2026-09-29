<template>
  <v-container fluid>
    <div class="d-flex align-center flex-wrap ga-2 mb-4">
      <span class="text-h5">
        {{
          $formatMessage({
            description: 'Followed series view: title',
            defaultMessage: 'Followed series',
            id: 'fork/followed/title',
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
        style="max-width: 280px"
      />
    </div>

    <template v-if="selectedLibrary">
      <v-card
        variant="outlined"
        class="mb-4"
      >
        <v-card-text>
          <FollowSchedule :library-id="selectedLibrary" />
        </v-card-text>
      </v-card>

      <v-card variant="outlined">
        <v-card-text>
          <div class="d-flex align-center flex-wrap ga-2 mb-3">
            <v-text-field
              v-model="newFollowUrl"
              :label="$formatMessage(addFollowLabel)"
              variant="outlined"
              density="compact"
              hide-details
              class="flex-grow-1"
              style="min-width: 220px"
              @keyup.enter="addFollow()"
            />
            <v-btn
              color="primary"
              :loading="addingFollow"
              :disabled="!newFollowUrl"
              @click="addFollow()"
            >
              {{ $formatMessage(followLabel) }}
            </v-btn>
            <v-btn
              variant="text"
              prepend-icon="i-mdi:play"
              :loading="checking"
              @click="checkNow()"
            >
              {{ $formatMessage(checkNowLabel) }}
            </v-btn>
            <v-btn
              variant="text"
              prepend-icon="i-mdi:file-import"
              :loading="importing"
              @click="importTxt()"
            >
              {{ $formatMessage(importLabel) }}
            </v-btn>
          </div>

          <v-skeleton-loader
            v-if="followsPending"
            type="list-item@3"
          />
          <v-alert
            v-else-if="!follows || follows.length === 0"
            type="info"
            variant="tonal"
            density="compact"
          >
            {{ $formatMessage(emptyLabel) }}
          </v-alert>
          <v-list v-else>
            <v-list-item
              v-for="follow in follows"
              :key="follow.id"
              :title="follow.title || follow.url"
              :subtitle="follow.url"
            >
              <template #prepend>
                <v-switch
                  :model-value="follow.enabled"
                  color="primary"
                  density="compact"
                  hide-details
                  :loading="togglingId === follow.id"
                  @update:model-value="toggleFollow(follow.id, $event as boolean)"
                />
              </template>
              <template #append>
                <v-btn
                  icon="i-mdi:delete"
                  variant="text"
                  size="small"
                  :aria-label="$formatMessage(unfollowLabel)"
                  @click="deleteFollow(follow.id)"
                />
              </template>
            </v-list-item>
          </v-list>
        </v-card-text>
      </v-card>
    </template>
    <v-alert
      v-else
      type="info"
      variant="tonal"
    >
      {{ $formatMessage(noLibraryLabel) }}
    </v-alert>
  </v-container>
</template>

<script lang="ts" setup>
import { useIntl, defineMessage } from 'vue-intl'
import {
  useAddFollow,
  useCheckFollowsNow,
  useDeleteFollow,
  useFollows,
  useImportFollowTxt,
  useUpdateFollow,
} from '@/colada/follows'
import { useLibraries } from '@/colada/libraries'
import { useMessagesStore } from '@/stores/messages'
import FollowSchedule from '@/components/downloads/FollowSchedule.vue'

const intl = useIntl()
const messagesStore = useMessagesStore()
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

const { data: follows, isPending: followsPending } = useFollows(selectedLibrary)

const { mutateAsync: addFollowMutation, isLoading: addingFollow } = useAddFollow()
const { mutateAsync: updateFollowMutation } = useUpdateFollow()
const { mutateAsync: deleteFollowMutation } = useDeleteFollow()
const { mutateAsync: checkFollowsNow, isLoading: checking } = useCheckFollowsNow()
const { mutateAsync: importFollowTxt, isLoading: importing } = useImportFollowTxt()

const newFollowUrl = ref('')
const togglingId = ref<string | null>(null)

async function addFollow() {
  if (!selectedLibrary.value || !newFollowUrl.value) return
  try {
    await addFollowMutation({ libraryId: selectedLibrary.value, body: { url: newFollowUrl.value } })
    newFollowUrl.value = ''
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function toggleFollow(id: string, enabled: boolean) {
  if (!selectedLibrary.value) return
  togglingId.value = id
  try {
    await updateFollowMutation({ libraryId: selectedLibrary.value, id, body: { enabled } })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  } finally {
    togglingId.value = null
  }
}

async function deleteFollow(id: string) {
  if (!selectedLibrary.value) return
  try {
    await deleteFollowMutation({ libraryId: selectedLibrary.value, id })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function checkNow() {
  if (!selectedLibrary.value) return
  try {
    const result = await checkFollowsNow(selectedLibrary.value)
    messagesStore.messages.push({
      message: intl.formatMessage(checkResultLabel, { count: result.queued }),
    })
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

async function importTxt() {
  if (!selectedLibrary.value) return
  try {
    await importFollowTxt(selectedLibrary.value)
  } catch (e) {
    messagesStore.messages.push({ message: (e as Error).message, color: 'error' })
  }
}

const libraryLabel = defineMessage({ description: 'Followed: library selector', defaultMessage: 'Library', id: 'fork/followed/library' })
const addFollowLabel = defineMessage({ description: 'Followed: add follow url', defaultMessage: 'Series URL to follow', id: 'fork/followed/url' })
const followLabel = defineMessage({ description: 'Followed: add button', defaultMessage: 'Follow', id: 'fork/followed/add' })
const checkNowLabel = defineMessage({ description: 'Followed: check now', defaultMessage: 'Check now', id: 'fork/followed/check' })
const importLabel = defineMessage({ description: 'Followed: import follow.txt', defaultMessage: 'Import follow.txt', id: 'fork/followed/import' })
const emptyLabel = defineMessage({ description: 'Followed: empty', defaultMessage: 'No followed series in this library.', id: 'fork/followed/empty' })
const unfollowLabel = defineMessage({ description: 'Followed: unfollow', defaultMessage: 'Unfollow', id: 'fork/followed/unfollow' })
const noLibraryLabel = defineMessage({ description: 'Followed: no library', defaultMessage: 'Select a library to manage its followed series.', id: 'fork/followed/noLibrary' })
const checkResultLabel = defineMessage({ description: 'Followed: check result', defaultMessage: '{count} chapters queued for download', id: 'fork/followed/checkResult' })
</script>

<route lang="yaml">
meta:
  requiresRole: ADMIN
</route>
