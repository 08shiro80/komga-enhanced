import KomgaImportService from '@/services/komga-import.service'
import {AxiosInstance} from 'axios'
import _Vue from 'vue'

let service: KomgaImportService

export default {
  install(
    Vue: typeof _Vue,
    {http}: { http: AxiosInstance }) {
    service = new KomgaImportService(http)
    Vue.prototype.$komgaImport = service
  },
}

declare module 'vue/types/vue' {
  interface Vue {
    $komgaImport: KomgaImportService;
  }
}
