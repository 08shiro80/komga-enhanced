import { defineStore } from 'pinia'

export const useTaskQueueStore = defineStore('task-queue', () => {
  const count = ref(0)
  const countByType = ref<Record<string, number>>({})
  const backgroundJobs = ref<string[]>([])

  return {
    count,
    countByType,
    backgroundJobs,
  }
})
