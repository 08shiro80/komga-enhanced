import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaClearCancelledDownloads,
  komgaClearCompletedDownloads,
  komgaClearFailedDownloads,
  komgaClearPendingDownloads,
  komgaCreateDownload,
  komgaDeleteDownload,
  komgaGetAllDownloads,
  komgaPerformAction,
  type DownloadCreateDto,
} from '@/generated/openapi'
import { useCurrentUser } from '@/colada/users'
import { STALE_TIME } from '@/types/time'

export const QUERY_KEYS_DOWNLOADS = {
  root: ['downloads'] as const,
}

export type ClearableStatus = 'completed' | 'failed' | 'cancelled' | 'pending'

export const useDownloads = defineQuery(() => {
  const { isAdmin } = useCurrentUser()

  const { data, ...rest } = useQuery({
    key: () => QUERY_KEYS_DOWNLOADS.root,
    query: () => komgaGetAllDownloads(),
    staleTime: STALE_TIME.DYNAMIC,
    enabled: () => isAdmin.value,
  })

  return { data, ...rest }
})

export const useCreateDownload = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (body: DownloadCreateDto) => komgaCreateDownload({ body }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_DOWNLOADS.root }),
  })
})

export const useDownloadAction = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ id, action }: { id: string; action: string }) =>
      komgaPerformAction({ path: { id }, body: { action } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_DOWNLOADS.root }),
  })
})

export const useDeleteDownload = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (id: string) => komgaDeleteDownload({ path: { id } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_DOWNLOADS.root }),
  })
})

export const useClearDownloads = defineMutation(() => {
  const queryCache = useQueryCache()
  const clearFns: Record<ClearableStatus, () => Promise<unknown>> = {
    completed: () => komgaClearCompletedDownloads(),
    failed: () => komgaClearFailedDownloads(),
    cancelled: () => komgaClearCancelledDownloads(),
    pending: () => komgaClearPendingDownloads(),
  }
  return useMutation({
    mutation: (status: ClearableStatus) => clearFns[status](),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_DOWNLOADS.root }),
  })
})
