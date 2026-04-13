import Vue from 'vue'

export default Vue.extend({
  computed: {
    isXs(): boolean {
      return this.$vuetify.breakpoint.xsOnly
    },
    isMobile(): boolean {
      return this.$vuetify.breakpoint.smAndDown
    },
    dialogFullscreen(): boolean {
      return this.$vuetify.breakpoint.xsOnly
    },
    toolbarCompact(): boolean {
      return this.$vuetify.breakpoint.smAndDown
    },
  },
})
