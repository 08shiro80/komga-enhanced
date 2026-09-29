import { defineQuery, useQuery } from '@pinia/colada'
import {
  komgaGetForkReleases,
  komgaGetGalleryDlForkUpdates,
  komgaGetReleases,
} from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'

export const useAppReleases = defineQuery(() =>
  useQuery({
    key: () => ['app-releases'],
    query: () => komgaGetReleases(),
    staleTime: STALE_TIME.LONG,
    gcTime: false,
  }),
)

export const useForkReleases = defineQuery(() =>
  useQuery({
    key: () => ['fork-releases'],
    query: () => komgaGetForkReleases(),
    staleTime: STALE_TIME.LONG,
    gcTime: false,
  }),
)

export const useGalleryDlForkUpdates = defineQuery(() =>
  useQuery({
    key: () => ['gallery-dl-fork-updates'],
    query: () => komgaGetGalleryDlForkUpdates(),
    staleTime: STALE_TIME.LONG,
    gcTime: false,
  }),
)
