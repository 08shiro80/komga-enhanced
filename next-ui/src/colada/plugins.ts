import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaDeletePlugin,
  komgaGetAllPlugins,
  komgaGetPluginById,
  komgaGetPluginConfig,
  komgaInstallPlugin,
  komgaUpdatePlugin,
  komgaUpdatePluginConfig,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

export const QUERY_KEYS_PLUGINS = {
  root: ['plugins'] as const,
  byId: (id: string) => [...QUERY_KEYS_PLUGINS.root, id] as const,
  config: (id: string) => [...QUERY_KEYS_PLUGINS.root, id, 'config'] as const,
}

export const usePlugins = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_PLUGINS.root,
    query: () => komgaGetAllPlugins(),
    staleTime: STALE_TIME.DEFAULT,
  }),
)

export function usePlugin(id: string) {
  return useQuery({
    key: () => QUERY_KEYS_PLUGINS.byId(id),
    query: () => komgaGetPluginById({ path: { id } }),
    staleTime: STALE_TIME.DEFAULT,
  })
}

export function usePluginConfig(id: Ref<string | undefined>) {
  return useQuery({
    key: () => [...QUERY_KEYS_PLUGINS.root, id.value ?? '', 'config'],
    query: () => komgaGetPluginConfig({ path: { id: id.value! } }),
    enabled: () => !!id.value,
    staleTime: STALE_TIME.INSTANT,
  })
}

export const useUpdatePlugin = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ id, enabled }: { id: string; enabled: boolean }) =>
      komgaUpdatePlugin({ path: { id }, body: { enabled } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_PLUGINS.root }),
  })
})

export const useDeletePlugin = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (id: string) => komgaDeletePlugin({ path: { id } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_PLUGINS.root }),
  })
})

export const useInstallPlugin = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ file, url }: { file?: File; url?: string }) =>
      komgaInstallPlugin({ body: file ? { file } : undefined, query: url ? { url } : undefined }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_PLUGINS.root }),
  })
})

export const useUpdatePluginConfig = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ id, config }: { id: string; config: Record<string, string> }) =>
      komgaUpdatePluginConfig({ path: { id }, body: config }),
    onSuccess: (_data, { id }) =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_PLUGINS.config(id) }),
  })
})
