import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaCleanOldBackups,
  komgaCreateBackup,
  komgaCreateFullBackup,
  komgaDeleteBackup,
  komgaListBackups,
  komgaRestoreBackup,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'

export const QUERY_KEYS_BACKUP = {
  root: ['backups'] as const,
}

export const useBackups = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_BACKUP.root,
    query: () => komgaListBackups(),
    staleTime: STALE_TIME.DEFAULT,
  }),
)

export const useCreateBackup = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: async (full: boolean) => {
      if (full) await komgaCreateFullBackup()
      else await komgaCreateBackup()
    },
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_BACKUP.root }),
  })
})

export const useDeleteBackup = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (fileName: string) => komgaDeleteBackup({ path: { fileName } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_BACKUP.root }),
  })
})

export const useRestoreBackup = defineMutation(() =>
  useMutation({
    mutation: (fileName: string) => komgaRestoreBackup({ path: { fileName } }),
  }),
)

export const useCleanOldBackups = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (keep: number) => komgaCleanOldBackups({ query: { keep } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_BACKUP.root }),
  })
})
