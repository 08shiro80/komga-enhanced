import { defineMutation, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaApplyCover,
  komgaApplyMetadata,
  komgaGetMetadata,
  komgaSearchMetadata,
  type MetadataSearchResult,
  type PluginApplyMetadataRequest,
} from '@/generated/openapi'
import { QUERY_KEYS_SERIES } from '@/colada/series'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

export function useMetadataSearch(params: Ref<{ pluginId: string; query: string } | undefined>) {
  return useQuery({
    key: () => ['metadata-search', JSON.stringify(params.value ?? {})],
    query: () =>
      komgaSearchMetadata({
        path: { id: params.value!.pluginId },
        query: { query: params.value!.query },
      }),
    enabled: () => !!params.value?.pluginId && !!params.value?.query,
    staleTime: STALE_TIME.DEFAULT,
  })
}

export const useApplyMetadataFromResult = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: async ({
      seriesId,
      pluginId,
      result,
    }: {
      seriesId: string
      pluginId: string
      result: MetadataSearchResult
    }) => {
      const md = await komgaGetMetadata({ path: { id: pluginId, externalId: result.externalId } })
      const body: PluginApplyMetadataRequest = {
        provider: result.provider,
        externalId: result.externalId,
        title: md.title,
        summary: md.summary,
        publisher: md.publisher,
        ageRating: md.ageRating,
        releaseDate: md.releaseDate,
        status: md.status,
        genres: md.genres,
        tags: md.tags,
        alternativeTitles: md.alternativeTitles,
        authors: md.authors.map((a) => ({ name: a.name, role: a.role })),
      }
      await komgaApplyMetadata({ path: { seriesId }, body })
      if (md.coverUrl) await komgaApplyCover({ path: { seriesId }, body: { coverUrl: md.coverUrl } })
    },
    onSuccess: (_data, { seriesId }) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_SERIES.byId(seriesId) }),
  })
})
