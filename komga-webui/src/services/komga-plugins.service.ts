import {AxiosInstance} from 'axios'

const qs = require('qs')

export interface PluginDto {
  id: string
  name: string
  version: string
  enabled: boolean
  pluginType: string
  description: string
  author: string
  entryPoint: string
  sourceUrl: string | null
  installedDate: string
  lastUpdated: string
  configSchema: any
  dependencies: string[]
}

export interface MetadataSearchResult {
  externalId: string
  title: string
  description: string | null
  coverUrl: string | null
  author: string | null
  year: number | null
  status: string | null
  tags: string[]
  provider: string
}

export interface MetadataDetails {
  title: string
  titleSort: string | null
  summary: string | null
  publisher: string | null
  ageRating: number | null
  releaseDate: string | null
  authors: Author[]
  tags: string[]
  genres: string[]
  language: string | null
  status: string | null
  coverUrl: string | null
}

export interface Author {
  name: string
  role: string
}

export default class KomgaPluginsService {
  private http: AxiosInstance

  constructor(http: AxiosInstance) {
    this.http = http
  }

  async getPlugins(): Promise<PluginDto[]> {
    try {
      return (await this.http.get('/api/v1/plugins')).data
    } catch (e) {
      let msg = 'An error occurred while trying to retrieve plugins'
      if (e.response.data.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }

  async getPlugin(pluginId: string): Promise<PluginDto> {
    try {
      return (await this.http.get(`/api/v1/plugins/${pluginId}`)).data
    } catch (e) {
      let msg = `An error occurred while trying to retrieve plugin ${pluginId}`
      if (e.response.data.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }

  async updatePlugin(pluginId: string, update: { enabled: boolean }): Promise<void> {
    try {
      await this.http.patch(`/api/v1/plugins/${pluginId}`, update)
    } catch (e) {
      let msg = `An error occurred while trying to update plugin ${pluginId}`
      if (e.response.data.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }

  async deletePlugin(pluginId: string): Promise<void> {
    try {
      await this.http.delete(`/api/v1/plugins/${pluginId}`)
    } catch (e) {
      let msg = `An error occurred while trying to delete plugin ${pluginId}`
      if (e.response.data.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }

  async searchMetadata(pluginId: string, query: string): Promise<MetadataSearchResult[]> {
    try {
      const params = { query }
      return (await this.http.get(`/api/v1/plugins/${pluginId}/search`, {
        params: params,
        paramsSerializer: params => qs.stringify(params, {indices: false}),
      })).data
    } catch (e) {
      let msg = `An error occurred while searching metadata with plugin ${pluginId}`
      if (e.response && e.response.data && e.response.data.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }

  async getMetadata(pluginId: string, externalId: string): Promise<MetadataDetails> {
    try {
      return (await this.http.get(`/api/v1/plugins/${pluginId}/metadata/${externalId}`)).data
    } catch (e) {
      let msg = `An error occurred while fetching metadata from plugin ${pluginId}`
      if (e.response && e.response.data && e.response.data.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }
}
