import { defineMutation, defineQuery, useMutation, useQuery, useQueryCache } from '@pinia/colada'
import {
  komgaDeletedChaptersScanStatus,
  komgaLibraryScanDeletedChaptersRun,
  komgaListFixes,
  komgaRepairComicInfo,
  komgaRepairComicInfoStatus,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'

export const QUERY_KEYS_FIXES = {
  root: ['fixes'] as const,
  repairComicInfoStatus: ['fixes', 'repair-comicinfo-status'] as const,
  deletedScanStatus: ['fixes', 'deleted-scan-status'] as const,
}

export const useFixes = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_FIXES.root,
    query: () => komgaListFixes(),
    staleTime: STALE_TIME.DEFAULT,
  }),
)

export const useRepairComicInfoStatus = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_FIXES.repairComicInfoStatus,
    query: () => komgaRepairComicInfoStatus(),
    staleTime: STALE_TIME.INSTANT,
  }),
)

export const useRunRepairComicInfo = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ libraryId, force }: { libraryId: string; force: boolean }) =>
      komgaRepairComicInfo({ path: { libraryId }, query: { force } }),
    onSuccess: () =>
      void queryCache.invalidateQueries({ key: QUERY_KEYS_FIXES.repairComicInfoStatus }),
  })
})

export const useDeletedChaptersScanStatus = defineQuery(() =>
  useQuery({
    key: () => QUERY_KEYS_FIXES.deletedScanStatus,
    query: () => komgaDeletedChaptersScanStatus(),
    staleTime: STALE_TIME.INSTANT,
  }),
)

export const useRunDeletedChaptersScan = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: ({ libraryId, dryRun }: { libraryId: string; dryRun: boolean }) =>
      komgaLibraryScanDeletedChaptersRun({ path: { libraryId }, query: { dryRun } }),
    onSuccess: () => void queryCache.invalidateQueries({ key: QUERY_KEYS_FIXES.deletedScanStatus }),
  })
})
