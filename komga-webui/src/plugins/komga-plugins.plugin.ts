import KomgaPluginsService from '@/services/komga-plugins.service'
import { AxiosInstance } from 'axios'
import _Vue from 'vue'

export default {
  install (
    Vue: typeof _Vue,
    { http }: { http: AxiosInstance }) {
    Vue.prototype.$komgaPlugins = new KomgaPluginsService(http)
  },
}

declare module 'vue/types/vue' {
  interface Vue {
    $komgaPlugins: KomgaPluginsService;
  }
}
