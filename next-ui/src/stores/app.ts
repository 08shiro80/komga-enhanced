import { defineStore } from 'pinia'
import { useDisplay } from 'vuetify'
import type { PresentationMode } from '@/types/libraries'
import type { PageSize, Paging } from '@/types/page'

export const useAppStore = defineStore(
  'app',
  () => {
    // persisted
    const drawer = ref(!useDisplay().mobile.value.valueOf())
    const theme = ref('system')
    const rememberMe = ref(false)
    const importBooksPath = ref('')
    const browsingPageSize = ref<PageSize>(20)
    const dataTablePageSize = ref<number>(20)
    // Whether the user explicitly chose "Browse as guest" (persisted so a reload stays in guest mode).
    const guestMode = ref(false)

    const browsingPaging = ref<Paging>('scroll')
    const isBrowsingPaged = computed(() => browsingPaging.value === 'paged')
    const isBrowsingScroll = computed(() => browsingPaging.value === 'scroll')
    /**
     * Store the presentation mode per view.
     * Use the getter to ensure a default value is always set.
     */
    const presentationMode = ref<Record<string, PresentationMode>>({})
    const getPresentationMode = (key: string, defaultValue: PresentationMode) => {
      return computed({
        get: () => {
          if (!presentationMode.value[key])
            presentationMode.value[key] = structuredClone(defaultValue)
          return presentationMode.value[key]
        },
        set: (value) => {
          presentationMode.value[key] = value
        },
      })
    }

    /**
     * Persisted filter query strings per `${viewName}:${filterType}` key, so filters survive
     * navigating away and back to a view (URL query stays the shareable source when present).
     */
    const filterQuery = ref<Record<string, string>>({})

    const gridCardWidth = ref(150)

    // transient
    const reorderLibraries = ref(false)
    const sseUnavailable = ref(false)

    return {
      drawer,
      theme,
      rememberMe,
      importBooksPath,
      browsingPageSize,
      dataTablePageSize,
      guestMode,
      browsingPaging,
      gridCardWidth,
      reorderLibraries,
      sseUnavailable,
      isBrowsingPaged,
      isBrowsingScroll,
      presentationMode,
      getPresentationMode,
      filterQuery,
    }
  },
  {
    persist: {
      key: 'komga.nextui.app',
      // explicitly state which keys are stored
      pick: [
        'drawer',
        'theme',
        'rememberMe',
        'importBooksPath',
        'browsingPageSize',
        'dataTablePageSize',
        'guestMode',
        'browsingPaging',
        'presentationMode',
        'filterQuery',
        'gridCardWidth',
      ],
    },
  },
)
