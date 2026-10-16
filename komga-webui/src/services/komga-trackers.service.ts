import {AxiosInstance} from 'axios'
import {
  TrackerAuthorizationDto,
  TrackerCheckResultDto,
  TrackerSyncDto,
  UserTrackerDto,
  UserTrackerUpdateDto,
} from '@/types/komga-trackers'

const API_TRACKERS = '/api/v1/trackers'

export default class KomgaTrackersService {
  private http: AxiosInstance

  constructor(http: AxiosInstance) {
    this.http = http
  }

  async getAll(): Promise<UserTrackerDto[]> {
    return (await this.http.get(API_TRACKERS)).data
  }

  async update(tracker: string, dto: UserTrackerUpdateDto): Promise<void> {
    await this.http.put(`${API_TRACKERS}/${tracker}`, dto)
  }

  async remove(tracker: string): Promise<void> {
    await this.http.delete(`${API_TRACKERS}/${tracker}`)
  }

  async test(tracker: string): Promise<TrackerCheckResultDto> {
    return (await this.http.post(`${API_TRACKERS}/${tracker}/test`)).data
  }

  async sync(tracker: string): Promise<TrackerSyncDto> {
    try {
      return (await this.http.post(`${API_TRACKERS}/${tracker}/sync`)).data
    } catch (e) {
      throw new Error(e.response?.data?.message ?? e.message)
    }
  }

  async authorize(tracker: string, returnTo: string): Promise<TrackerAuthorizationDto> {
    try {
      return (await this.http.post(`${API_TRACKERS}/${tracker}/authorize`, {returnTo})).data
    } catch (e) {
      throw new Error(e.response?.data?.message ?? e.message)
    }
  }
}
