import { storeToRefs } from 'pinia'
import { useDialogsStore } from '@/stores/dialogs'
import { useIntl } from 'vue-intl'
import { useDisplay } from 'vuetify/framework'
import MetadataSearchDialog from '@/components/series/MetadataSearchDialog.vue'

export function useMetadataSearchDialog() {
  const { simple: dialogSimple } = storeToRefs(useDialogsStore())
  const intl = useIntl()
  const display = useDisplay()

  const prepareDialog = (
    seriesId: string,
    initialQuery: string,
    callback: () => void = () => {},
  ) => {
    dialogSimple.value.dialogProps = {
      title: intl.formatMessage({
        description: 'Metadata search dialog title',
        defaultMessage: 'Search metadata online',
        id: 'fork/metasearch/title',
      }),
      maxWidth: 900,
      maxHeight: '85vh',
      scrollable: true,
      fullscreen: display.xs.value,
    }
    dialogSimple.value.callback = () => callback()
    dialogSimple.value.slot = {
      component: markRaw(MetadataSearchDialog),
      props: {
        seriesId: seriesId,
        initialQuery: initialQuery,
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
