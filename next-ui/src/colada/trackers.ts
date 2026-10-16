import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaAuthorizeTracker,
  komgaDeleteTracker,
  komgaGetTrackers,
  komgaSyncTracker,
  komgaTestTracker,
  komgaUpdateTracker,
  type UserTrackerUpdateDto,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'

export const QUERY_KEYS_TRACKERS = {
  root: ['trackers'] as const,
}

export const useTrackers = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_TRACKERS.root,
    query: () => komgaGetTrackers(),
    staleTime: STALE_TIME.DEFAULT,
  }),
)

export const useUpdateTracker = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ tracker, update }: { tracker: string; update: UserTrackerUpdateDto }) =>
      komgaUpdateTracker({ path: { tracker }, body: update }),
    onSettled: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_TRACKERS.root }),
  })
})

export const useDeleteTracker = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (tracker: string) => komgaDeleteTracker({ path: { tracker } }),
    onSettled: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_TRACKERS.root }),
  })
})

export const useTestTracker = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (tracker: string) => komgaTestTracker({ path: { tracker } }),
    onSettled: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_TRACKERS.root }),
  })
})

export const useSyncTracker = defineMutation(() =>
  useMutation({
    mutation: (tracker: string) => komgaSyncTracker({ path: { tracker } }),
  }),
)

export const useAuthorizeTracker = defineMutation(() =>
  useMutation({
    mutation: ({ tracker, returnTo }: { tracker: string; returnTo: string }) =>
      komgaAuthorizeTracker({ path: { tracker }, body: { returnTo } }),
  }),
)
