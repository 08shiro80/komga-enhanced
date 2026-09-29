import { storeToRefs } from 'pinia'
import { useDialogsStore } from '@/stores/dialogs'
import { useIntl } from 'vue-intl'
import { useDisplay } from 'vuetify/framework'
import BlacklistManager from '@/components/series/BlacklistManager.vue'

export function useBlacklistDialog() {
  const { simple: dialogSimple } = storeToRefs(useDialogsStore())
  const intl = useIntl()
  const display = useDisplay()

  const prepareDialog = (seriesId: string, callback: () => void = () => {}) => {
    dialogSimple.value.dialogProps = {
      title: intl.formatMessage({
        description: 'Blacklist dialog title',
        defaultMessage: 'Chapter blacklist',
        id: 'fork/blacklist/title',
      }),
      maxWidth: 700,
      maxHeight: '80vh',
      scrollable: true,
      fullscreen: display.xs.value,
    }
    dialogSimple.value.callback = () => callback()
    dialogSimple.value.slot = {
      component: markRaw(BlacklistManager),
      props: {
        seriesId: seriesId,
      },
    }
  }

  const activatorRef = computed({
    get: () => dialogSimple.value.activator,
    set: (val) => (dialogSimple.value.activator = val),
  })

  function showDialog() {
    dialogSimple.value.dialogProps.shown = true
  }

  return {
    prepareDialog: prepareDialog,
    activator: activatorRef,
    showDialog: showDialog,
  }
}
