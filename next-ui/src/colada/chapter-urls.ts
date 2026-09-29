import { defineMutation, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaDeleteChapterUrl,
  komgaDeleteChapterUrlsForSeries,
  komgaGetChapterUrlsForSeries,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

export const QUERY_KEYS_CHAPTER_URLS = {
  root: ['chapter-urls'] as const,
  bySeries: (seriesId: string) => ['chapter-urls', seriesId] as const,
}

export function useSeriesChapterUrls(seriesId: Ref<string | undefined>) {
  return useQuery({
    key: () => ['chapter-urls', seriesId.value ?? ''],
    query: () => komgaGetChapterUrlsForSeries({ path: { seriesId: seriesId.value! } }),
    enabled: () => !!seriesId.value,
    staleTime: STALE_TIME.DEFAULT,
  })
}

export const useDeleteChapterUrl = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ id }: { id: string; seriesId: string }) => komgaDeleteChapterUrl({ path: { id } }),
    onSuccess: (_data, { seriesId }) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_CHAPTER_URLS.bySeries(seriesId) }),
  })
})

export const useDeleteAllChapterUrlsForSeries = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (seriesId: string) => komgaDeleteChapterUrlsForSeries({ path: { seriesId } }),
    onSuccess: (_data, seriesId) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_CHAPTER_URLS.bySeries(seriesId) }),
  })
})
