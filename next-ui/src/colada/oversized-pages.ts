import { defineMutation, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaDeleteOversizedPage,
  komgaDeleteOversizedPagesBatch,
  komgaGetOversizedPages,
  komgaIgnoreOversizedPage,
  komgaIgnoreOversizedPagesBatch,
  komgaSplitAllStatus,
  komgaSplitAllTallPages,
  komgaSplitTallPages,
  type DeleteOversizedPageRequestDto,
  type IgnoredPageKeyDto,
  type IgnoreOversizedPageRequestDto,
  type SplitRequestDto,
  type SplitResultDto,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

export interface OversizedQuery {
  mode?: string
  minRatio?: number
  minHeight?: number
  minWidth?: number
  includeIgnored?: boolean
  search?: string
  page?: number
  size?: number
  sort?: Array<string>
}

export const QUERY_KEYS_OVERSIZED = {
  root: ['oversized-pages'] as const,
  splitAllStatus: ['oversized-pages', 'split-all-status'] as const,
}

export function useOversizedPages(params: Ref<OversizedQuery>) {
  return useQuery({
    key: () => [...QUERY_KEYS_OVERSIZED.root, JSON.stringify(params.value)],
    query: () => komgaGetOversizedPages({ query: params.value }),
    staleTime: STALE_TIME.DEFAULT,
    placeholderData: (previous) => previous,
  })
}

export function useSplitAllStatus() {
  return useQuery({
    key: () => QUERY_KEYS_OVERSIZED.splitAllStatus,
    query: () => komgaSplitAllStatus(),
    staleTime: STALE_TIME.INSTANT,
  })
}

export const useDeleteOversizedPage = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (body: DeleteOversizedPageRequestDto) => komgaDeleteOversizedPage({ body }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_OVERSIZED.root }),
  })
})

export const useDeleteOversizedPagesBatch = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ mode, pages }: { mode: string; pages: Array<IgnoredPageKeyDto> }) =>
      komgaDeleteOversizedPagesBatch({ body: { mode, pages } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_OVERSIZED.root }),
  })
})

export const useIgnoreOversizedPage = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (body: IgnoreOversizedPageRequestDto) => komgaIgnoreOversizedPage({ body }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_OVERSIZED.root }),
  })
})

export const useIgnoreOversizedPagesBatch = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ mode, pages }: { mode: string; pages: Array<IgnoredPageKeyDto> }) =>
      komgaIgnoreOversizedPagesBatch({ body: { mode, pages } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_OVERSIZED.root }),
  })
})

export const useSplitPages = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({
      bookId,
      pageNumbers,
      maxRatio,
      mode,
    }: {
      bookId: string
      pageNumbers: Array<number>
      maxRatio?: number
      mode?: string
    }): Promise<SplitResultDto> =>
      komgaSplitTallPages({ path: { bookId }, query: { pageNumbers, maxRatio, mode } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_OVERSIZED.root }),
  })
})

export const useSplitAll = defineMutation(() =>
  useMutation({
    mutation: (body: SplitRequestDto) => komgaSplitAllTallPages({ body }),
  }),
)
