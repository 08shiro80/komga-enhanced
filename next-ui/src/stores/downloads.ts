import { defineStore } from 'pinia'

export interface DownloadLiveProgress {
  status: string
  progressPercent: number
  currentChapter: number | null
  totalChapters: number | null
  message: string | null
}

export const useDownloadsStore = defineStore('downloads', () => {
  const progress = ref<Record<string, DownloadLiveProgress>>({})

  function update(id: string, live: DownloadLiveProgress) {
    progress.value[id] = live
  }

  function remove(id: string) {
    delete progress.value[id]
  }

  return {
    progress,
    update,
    remove,
  }
})
