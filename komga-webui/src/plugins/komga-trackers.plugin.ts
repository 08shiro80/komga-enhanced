import KomgaTrackersService from '@/services/komga-trackers.service'
import {AxiosInstance} from 'axios'
import _Vue from 'vue'

let service: KomgaTrackersService

export default {
  install(Vue: typeof _Vue, {http}: { http: AxiosInstance }) {
    service = new KomgaTrackersService(http)
    Vue.prototype.$komgaTrackers = service
  },
}

declare module 'vue/types/vue' {
  interface Vue {
    $komgaTrackers: KomgaTrackersService;
  }
}
