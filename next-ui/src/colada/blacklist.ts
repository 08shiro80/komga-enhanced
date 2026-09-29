import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaAddBlacklist,
  komgaBlacklistBook,
  komgaDeleteBookFile,
  komgaGetBlacklist,
  komgaRemoveBlacklist,
} from '@/generated/openapi'
import { client } from '@/generated/openapi/client.gen'
import { QUERY_KEYS_BOOKS } from '@/colada/books'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

// Matches the fork BlacklistController /api/v1/blacklist response.
// Uses the raw client until the generated SDK is regenerated with the new endpoint.
export interface BlacklistedChapterDto {
  id: string
  chapterUrl: string
  chapterNumber?: string
  chapterTitle?: string
  createdDate: string
}

export interface BlacklistedSeriesDto {
  seriesId: string
  seriesTitle: string
  chapters: Array<BlacklistedChapterDto>
}

export const QUERY_KEYS_BLACKLIST = {
  root: ['blacklist'] as const,
  all: ['blacklist', 'all'] as const,
  bySeries: (seriesId: string) => [...QUERY_KEYS_BLACKLIST.root, seriesId] as const,
}

export const useAllBlacklisted = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_BLACKLIST.all,
    query: async () => {
      const res = await client.get({ url: '/api/v1/blacklist' })
      return (res.data as Array<BlacklistedSeriesDto>) ?? []
    },
    staleTime: STALE_TIME.DEFAULT,
  }),
)

export const useRemoveBlacklistedGlobal = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (blacklistId: string) =>
      client.delete({ url: `/api/v1/blacklist/${blacklistId}` }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_BLACKLIST.root }),
  })
})

export const useBlacklistAndDeleteBook = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: async (bookId: string) => {
      await komgaBlacklistBook({ path: { bookId } })
      await komgaDeleteBookFile({ path: { bookId } })
    },
    onSuccess: () => {
      void queryCache.invalidateQueries({ key: QUERY_KEYS_BLACKLIST.root })
      void queryCache.invalidateQueries({ key: QUERY_KEYS_BOOKS.root })
    },
  })
})

export function useSeriesBlacklist(seriesId: Ref<string | undefined>) {
  return useQuery({
    key: () => [...QUERY_KEYS_BLACKLIST.root, seriesId.value ?? ''],
    query: () => komgaGetBlacklist({ path: { seriesId: seriesId.value! } }),
    enabled: () => !!seriesId.value,
    staleTime: STALE_TIME.DEFAULT,
  })
}

export const useAddBlacklist = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({
      seriesId,
      chapterUrl,
      chapterNumber,
      chapterTitle,
    }: {
      seriesId: string
      chapterUrl: string
      chapterNumber?: string
      chapterTitle?: string
    }) => {
      const body: Record<string, string> = { chapterUrl }
      if (chapterNumber) body.chapterNumber = chapterNumber
      if (chapterTitle) body.chapterTitle = chapterTitle
      return komgaAddBlacklist({ path: { seriesId }, body })
    },
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_BLACKLIST.root }),
  })
})

export const useRemoveBlacklist = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ seriesId, blacklistId }: { seriesId: string; blacklistId: string }) =>
      komgaRemoveBlacklist({ path: { seriesId, blacklistId } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_BLACKLIST.root }),
  })
})
