export const LibraryAction = {
  Scan: 'SCAN',
  ScanDeep: 'SCAN_DEEP',
  Edit: 'EDIT',
  RefreshMetadata: 'REFRESH_METADATA',
  Analyze: 'ANALYZE',
  Delete: 'DELETE',
  EmptyTrash: 'EMPTY_TRASH',
  ScanDeletedChapters: 'SCAN_DELETED_CHAPTERS',
} as const

export type LibraryAction = (typeof LibraryAction)[keyof typeof LibraryAction]
