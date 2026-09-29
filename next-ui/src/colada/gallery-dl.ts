import { defineMutation, useMutation, useQuery } from '@pinia/colada'
import { client } from '@/generated/openapi/client.gen'
import { STALE_TIME } from '@/types/time'

// Matches the fork GalleryDlController /api/v1/gallery-dl/update endpoints. Uses the raw client
// until the generated SDK is regenerated with the new endpoint.
export interface GalleryDlUpdateResult {
  success: boolean
  message: string
  installedPath?: string
  overlaidFiles?: number
}

export interface GalleryDlUpdateStatus {
  running: boolean
  url?: string
  result?: GalleryDlUpdateResult
}

export function useGalleryDlUpdateStatus() {
  return useQuery({
    key: () => ['gallery-dl-update-status'],
    query: async () => {
      const res = await client.get({ url: '/api/v1/gallery-dl/update/status' })
      return (res.data as GalleryDlUpdateStatus) ?? { running: false }
    },
    staleTime: STALE_TIME.INSTANT,
  })
}

export const useGalleryDlUpdate = defineMutation(() =>
  useMutation({
    mutation: (url: string) =>
      client.post({ url: '/api/v1/gallery-dl/update', body: { url } }),
  }),
)
