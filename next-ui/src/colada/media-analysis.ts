import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaDeleteBookFile,
  komgaGetBooks,
  komgaGetSinglePageBooks,
  komgaIgnore,
  komgaRepair,
  komgaRepairAll,
  komgaRescanFlagged,
  komgaStatus,
  komgaUnignore,
  komgaVerifyAll,
  type SearchConditionBook,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

export interface CorruptBooksQuery {
  statuses: Array<string>
  libraryIds: Array<string>
  page: number
  size: number
}

export interface IntegrityStatus {
  inProgress?: boolean
  processed?: number
  total?: number
  flagged?: number
  repairInProgress?: boolean
  repairProcessed?: number
  repairTotal?: number
  repairFixed?: number
  repairPartial?: number
  repairFailed?: number
}

export const QUERY_KEYS_MEDIA = {
  integrity: ['media', 'integrity'] as const,
  singlePage: ['media', 'single-page'] as const,
  corruptBooks: ['media', 'corrupt-books'] as const,
}

export function useCorruptBooks(params: Ref<CorruptBooksQuery>) {
  return useQuery({
    key: () => [...QUERY_KEYS_MEDIA.corruptBooks, JSON.stringify(params.value)],
    query: () => {
      const allOf: Array<SearchConditionBook> = []
      if (params.value.statuses.length > 0)
        allOf.push({
          anyOf: params.value.statuses.map((s) => ({ mediaStatus: { operator: 'is', value: s } })),
        })
      if (params.value.libraryIds.length > 0)
        allOf.push({
          anyOf: params.value.libraryIds.map((id) => ({ libraryId: { operator: 'is', value: id } })),
        })
      return komgaGetBooks({
        body: { condition: { allOf } },
        query: { page: params.value.page, size: params.value.size },
      })
    },
    staleTime: STALE_TIME.DEFAULT,
    placeholderData: (previous) => previous,
    enabled: () => params.value.statuses.length > 0,
  })
}

export const useDeleteSinglePageBook = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (bookId: string) => komgaDeleteBookFile({ path: { bookId } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_MEDIA.singlePage }),
  })
})

export const useIntegrityStatus = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_MEDIA.integrity,
    query: () => komgaStatus() as Promise<IntegrityStatus>,
    staleTime: STALE_TIME.INSTANT,
  }),
)

export function useSinglePageBooks(includeIgnored: Ref<boolean>) {
  return useQuery({
    key: () => [...QUERY_KEYS_MEDIA.singlePage, includeIgnored.value],
    query: () => komgaGetSinglePageBooks({ query: { includeIgnored: includeIgnored.value } }),
    staleTime: STALE_TIME.DEFAULT,
    placeholderData: (previous) => previous,
  })
}

export const useVerifyIntegrity = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: () => komgaVerifyAll(),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_MEDIA.integrity }),
  })
})

export const useRepairIntegrity = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: () => komgaRepairAll(),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_MEDIA.integrity }),
  })
})

export const useRescanIntegrity = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: () => komgaRescanFlagged(),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_MEDIA.integrity }),
  })
})

export const useRepairSinglePage = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (bookId: string) => komgaRepair({ path: { bookId } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_MEDIA.singlePage }),
  })
})

export const useToggleSinglePageIgnore = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ bookId, ignored }: { bookId: string; ignored: boolean }) =>
      ignored ? komgaUnignore({ path: { bookId } }) : komgaIgnore({ path: { bookId } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_MEDIA.singlePage }),
  })
})
