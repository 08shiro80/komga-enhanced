export interface UserTrackerDto {
  tracker: string,
  linked: boolean,
  enabled: boolean,
  settings: Record<string, string>,
  secretsSet: string[],
}

export interface UserTrackerUpdateDto {
  enabled?: boolean,
  settings?: Record<string, string | null>,
}

export interface TrackerCheckResultDto {
  success: boolean,
  message: string,
}
