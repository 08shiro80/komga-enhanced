import { storeToRefs } from 'pinia'
import { useDialogsStore } from '@/stores/dialogs'
import { useIntl } from 'vue-intl'
import { useDisplay } from 'vuetify/framework'
import AddChapterDownloadManager from '@/components/series/AddChapterDownloadManager.vue'
import type { SeriesDto } from '@/generated/openapi'

export function useAddChapterDownloadDialog() {
  const { simple: dialogSimple } = storeToRefs(useDialogsStore())
  const intl = useIntl()
  const display = useDisplay()

  const prepareDialog = (series: SeriesDto, callback: () => void = () => {}) => {
    dialogSimple.value.dialogProps = {
      title: intl.formatMessage({
        description: 'Add chapter download dialog title',
        defaultMessage: 'Add chapter download',
        id: 'fork/addchapter/title',
      }),
      maxWidth: 640,
      scrollable: true,
      fullscreen: display.xs.value,
    }
    dialogSimple.value.callback = () => callback()
    dialogSimple.value.slot = {
      component: markRaw(AddChapterDownloadManager),
      props: {
        series: series,
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
