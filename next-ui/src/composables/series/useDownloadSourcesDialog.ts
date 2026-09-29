import { storeToRefs } from 'pinia'
import { useDialogsStore } from '@/stores/dialogs'
import { useIntl } from 'vue-intl'
import { useDisplay } from 'vuetify/framework'
import DownloadSourcesManager from '@/components/series/DownloadSourcesManager.vue'

export function useDownloadSourcesDialog() {
  const { simple: dialogSimple } = storeToRefs(useDialogsStore())
  const intl = useIntl()
  const display = useDisplay()

  const prepareDialog = (seriesId: string, callback: () => void = () => {}) => {
    dialogSimple.value.dialogProps = {
      title: intl.formatMessage({
        description: 'Download sources dialog title',
        defaultMessage: 'Download sources',
        id: 'fork/sources/title',
      }),
      maxWidth: 700,
      maxHeight: '80vh',
      scrollable: true,
      fullscreen: display.xs.value,
    }
    dialogSimple.value.callback = () => callback()
    dialogSimple.value.slot = {
      component: markRaw(DownloadSourcesManager),
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
