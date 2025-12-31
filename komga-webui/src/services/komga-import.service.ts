import {AxiosInstance} from 'axios'

const API_IMPORT = '/api/v1/import'

export interface TachiyomiImportResult {
  totalInBackup: number
  mangaDexCount: number
  importedCount: number
  skippedCount: number
  errorCount: number
  imported: string[]
  skipped: string[]
  errors: string[]
  success: boolean
  message: string
}

export default class KomgaImportService {
  private http: AxiosInstance

  constructor(http: AxiosInstance) {
    this.http = http
  }

  async importTachiyomi(file: File, libraryId: string): Promise<TachiyomiImportResult> {
    try {
      const formData = new FormData()
      formData.append('file', file)
      formData.append('libraryId', libraryId)

      return (await this.http.post(`${API_IMPORT}/tachiyomi`, formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      })).data
    } catch (e) {
      let msg = 'An error occurred while importing Tachiyomi backup'
      if (e.response?.data?.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }
}
