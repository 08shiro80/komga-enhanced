import {AxiosInstance} from 'axios'
import {TrackerCheckResultDto, UserTrackerDto, UserTrackerUpdateDto} from '@/types/komga-trackers'

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
}
