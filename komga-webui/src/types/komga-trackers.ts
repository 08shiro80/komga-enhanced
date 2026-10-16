export interface UserTrackerDto {
  tracker: string,
  linked: boolean,
  enabled: boolean,
  settings: Record<string, string>,
  secretsSet: string[],
  authorizationRedirectUri?: string,
  scrobblerEnabled: boolean,
  status: TrackerStatusDto,
}

export interface TrackerStatusDto {
  state: string,
  message?: string,
  accountName?: string,
  lastSuccessAt?: string,
  lastErrorAt?: string,
  lastError?: string,
  tokenExpiresAt?: string,
}

export interface TrackerSyncDto {
  queued: number,
}

export interface UserTrackerUpdateDto {
  enabled?: boolean,
  settings?: Record<string, string | null>,
}

export interface TrackerCheckResultDto {
  success: boolean,
  message: string,
}

export interface TrackerAuthorizationDto {
  authorizationUrl: string,
}
