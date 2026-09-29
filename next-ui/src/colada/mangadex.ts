import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaFollowMangaOnMangaDex,
  komgaGetMangaDexFollows,
  komgaListMangaDexTags,
  komgaSearchMangaDexAdvanced,
  komgaUnfollowMangaOnMangaDex,
  type MangaDexAdvancedSearchRequest,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

export const QUERY_KEYS_MANGADEX = {
  root: ['mangadex'] as const,
  tags: ['mangadex', 'tags'] as const,
  follows: ['mangadex', 'follows'] as const,
}

export const useMangaDexFollows = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_MANGADEX.follows,
    query: () => komgaGetMangaDexFollows(),
    staleTime: STALE_TIME.DEFAULT,
  }),
)

export const useToggleMangaDexFollow = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ mangaId, unfollow }: { mangaId: string; unfollow: boolean }) =>
      unfollow
        ? komgaUnfollowMangaOnMangaDex({ path: { mangaId } })
        : komgaFollowMangaOnMangaDex({ path: { mangaId } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_MANGADEX.follows }),
  })
})

export const useMangaDexTags = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_MANGADEX.tags,
    query: () => komgaListMangaDexTags(),
    staleTime: STALE_TIME.LONG,
  }),
)

export function useMangaDexSearch(request: Ref<MangaDexAdvancedSearchRequest | undefined>) {
  return useQuery({
    key: () => [...QUERY_KEYS_MANGADEX.root, 'search', JSON.stringify(request.value ?? {})],
    query: () => komgaSearchMangaDexAdvanced({ body: request.value! }),
    enabled: () => !!request.value,
    staleTime: STALE_TIME.DEFAULT,
    placeholderData: (previous) => previous,
  })
}
