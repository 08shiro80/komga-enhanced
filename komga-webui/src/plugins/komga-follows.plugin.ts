import KomgaFollowsService from '@/services/komga-follows.service'
import {AxiosInstance} from 'axios'
import _Vue from 'vue'

let service: KomgaFollowsService

export default {
  install(Vue: typeof _Vue, {http}: { http: AxiosInstance }) {
    service = new KomgaFollowsService(http)
    Vue.prototype.$komgaFollows = service
  },
}

declare module 'vue/types/vue' {
  interface Vue {
    $komgaFollows: KomgaFollowsService;
  }
}
