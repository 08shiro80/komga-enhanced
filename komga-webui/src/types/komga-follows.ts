export interface FollowDto {
  id: string
  libraryId: string
  url: string
  title: string | null
  seriesId: string | null
  enabled: boolean
  addedAt: string
  lastCheckedAt: string | null
}

export interface FollowCreationDto {
  url: string
  title?: string
  seriesId?: string
}

export interface FollowUpdateDto {
  title?: string
  enabled?: boolean
}

export interface FollowBatchResultDto {
  added: number
  skipped: number
}

export interface FollowCheckResultDto {
  queued: number
}

export interface FollowScheduleDto {
  libraryId: string
  enabled: boolean
  scheduleMode: string
  intervalHours: number
  checkTime: string | null
  lastCheckTime: string | null
}

export interface FollowScheduleUpdateDto {
  enabled: boolean
  scheduleMode?: string
  intervalHours?: number
  checkTime?: string | null
}
