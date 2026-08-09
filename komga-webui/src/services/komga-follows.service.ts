import {AxiosInstance} from 'axios'
import {
  FollowBatchResultDto,
  FollowCheckResultDto,
  FollowCreationDto,
  FollowDto,
  FollowScheduleDto,
  FollowScheduleUpdateDto,
  FollowUpdateDto,
} from '@/types/komga-follows'

const API_FOLLOWS = '/api/v1/downloads/follows'

export default class KomgaFollowsService {
  private http: AxiosInstance

  constructor(http: AxiosInstance) {
    this.http = http
  }

  async getAll(libraryId: string): Promise<FollowDto[]> {
    return (await this.http.get(`${API_FOLLOWS}/${libraryId}`)).data
  }

  async getBySeries(seriesId: string): Promise<FollowDto[]> {
    return (await this.http.get(`${API_FOLLOWS}/by-series/${seriesId}`)).data
  }

  async add(libraryId: string, dto: FollowCreationDto): Promise<FollowDto> {
    return (await this.http.post(`${API_FOLLOWS}/${libraryId}`, dto)).data
  }

  async addBatch(libraryId: string, urls: string[]): Promise<FollowBatchResultDto> {
    return (await this.http.post(`${API_FOLLOWS}/${libraryId}/batch`, {urls})).data
  }

  async update(libraryId: string, id: string, dto: FollowUpdateDto): Promise<FollowDto> {
    return (await this.http.patch(`${API_FOLLOWS}/${libraryId}/${id}`, dto)).data
  }

  async remove(libraryId: string, id: string): Promise<void> {
    await this.http.delete(`${API_FOLLOWS}/${libraryId}/${id}`)
  }

  async removeBatch(libraryId: string, ids: string[]): Promise<void> {
    await this.http.delete(`${API_FOLLOWS}/${libraryId}/batch`, {data: {ids}})
  }

  async checkNow(libraryId: string): Promise<FollowCheckResultDto> {
    return (await this.http.post(`${API_FOLLOWS}/${libraryId}/check-now`)).data
  }

  async getSchedule(libraryId: string): Promise<FollowScheduleDto> {
    return (await this.http.get(`${API_FOLLOWS}/${libraryId}/schedule`)).data
  }

  async updateSchedule(libraryId: string, dto: FollowScheduleUpdateDto): Promise<FollowScheduleDto> {
    return (await this.http.put(`${API_FOLLOWS}/${libraryId}/schedule`, dto)).data
  }
}
