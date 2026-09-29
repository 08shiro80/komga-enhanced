import { defineMutation, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaAddFollow,
  komgaCheckFollowsNow,
  komgaDeleteFollow,
  komgaGetFollows,
  komgaGetFollowSchedule,
  komgaImportFollowTxt,
  komgaSyncFollowsToMangaDex,
  komgaUpdateFollow,
  komgaUpdateFollowSchedule,
  type FollowCreationDto,
  type FollowScheduleUpdateDto,
  type FollowUpdateDto,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

export const QUERY_KEYS_FOLLOWS = {
  root: ['follows'] as const,
  byLibrary: (libraryId: string) => [...QUERY_KEYS_FOLLOWS.root, libraryId] as const,
  schedule: (libraryId: string) => [...QUERY_KEYS_FOLLOWS.root, libraryId, 'schedule'] as const,
}

export function useFollows(libraryId: Ref<string | undefined>) {
  return useQuery({
    key: () => [...QUERY_KEYS_FOLLOWS.root, libraryId.value ?? ''],
    query: () => komgaGetFollows({ path: { libraryId: libraryId.value! } }),
    staleTime: STALE_TIME.DEFAULT,
    enabled: () => !!libraryId.value,
  })
}

export function useFollowSchedule(libraryId: Ref<string | undefined>) {
  return useQuery({
    key: () => [...QUERY_KEYS_FOLLOWS.root, libraryId.value ?? '', 'schedule'],
    query: () => komgaGetFollowSchedule({ path: { libraryId: libraryId.value! } }),
    staleTime: STALE_TIME.DEFAULT,
    enabled: () => !!libraryId.value,
  })
}

export const useAddFollow = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ libraryId, body }: { libraryId: string; body: FollowCreationDto }) =>
      komgaAddFollow({ path: { libraryId }, body }),
    onSuccess: (_data, { libraryId }) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_FOLLOWS.byLibrary(libraryId) }),
  })
})

export const useUpdateFollow = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ libraryId, id, body }: { libraryId: string; id: string; body: FollowUpdateDto }) =>
      komgaUpdateFollow({ path: { libraryId, id }, body }),
    onSuccess: (_data, { libraryId }) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_FOLLOWS.byLibrary(libraryId) }),
  })
})

export const useDeleteFollow = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ libraryId, id }: { libraryId: string; id: string }) =>
      komgaDeleteFollow({ path: { libraryId, id } }),
    onSuccess: (_data, { libraryId }) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_FOLLOWS.byLibrary(libraryId) }),
  })
})

export const useCheckFollowsNow = defineMutation(() =>
  useMutation({
    mutation: (libraryId: string) => komgaCheckFollowsNow({ path: { libraryId } }),
  }),
)

export const useImportFollowTxt = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (libraryId: string) => komgaImportFollowTxt({ path: { libraryId } }),
    onSuccess: (_data, libraryId) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_FOLLOWS.byLibrary(libraryId) }),
  })
})

export const useSyncFollowsToMangaDex = defineMutation(() =>
  useMutation({
    mutation: (libraryId: string) => komgaSyncFollowsToMangaDex({ path: { libraryId } }),
  }),
)

export const useUpdateFollowSchedule = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ libraryId, body }: { libraryId: string; body: FollowScheduleUpdateDto }) =>
      komgaUpdateFollowSchedule({ path: { libraryId }, body }),
    onSuccess: (_data, { libraryId }) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_FOLLOWS.schedule(libraryId) }),
  })
})
