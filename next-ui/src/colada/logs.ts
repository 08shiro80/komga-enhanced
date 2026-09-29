import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import { komgaGetLogLevel, komgaGetLogs, komgaSetLogLevel } from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'
import type { Ref } from 'vue'

export const QUERY_KEYS_LOGS = {
  root: ['logs'] as const,
  level: ['logs', 'level'] as const,
}

export function useLogs(lines: Ref<number>) {
  return useQuery({
    key: () => [...QUERY_KEYS_LOGS.root, lines.value],
    query: () => komgaGetLogs({ query: { lines: lines.value } }),
    staleTime: STALE_TIME.INSTANT,
  })
}

export const useLogLevel = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_LOGS.level,
    query: () => komgaGetLogLevel(),
    staleTime: STALE_TIME.DEFAULT,
  }),
)

export const useSetLogLevel = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (level: string) => komgaSetLogLevel({ query: { level } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_LOGS.level }),
  })
})
