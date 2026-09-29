import { defineStore } from 'pinia'
import { useDisplay } from 'vuetify'
import type { PresentationMode } from '@/types/libraries'
import type { PageSize, Paging } from '@/types/page'
import type { Sort } from '@/types/PageRequest'

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
        get: () => presentationMode.value[key] ?? (presentationMode.value[key] = defaultValue),
        set: (value) => {
          presentationMode.value[key] = value
        },
      })
    }

    /**
     * Store the sort order per view.
     * Use the getter to ensure a default value is always set.
     */
    const sortActive = ref<Record<string, Sort[]>>({})
    const getSortActive = (key: string, defaultValue: Sort[]) => {
      return computed({
        get: () => sortActive.value[key] ?? (sortActive.value[key] = defaultValue),
        set: (value) => {
          sortActive.value[key] = value
        },
      })
    }
    // Whether a sort was already persisted for this view (vs. still on its default).
    const hasSortActive = (key: string) => key in sortActive.value

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
      getPresentationMode,
      getSortActive,
      hasSortActive,
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
        'sortActive',
        'filterQuery',
        'gridCardWidth',
      ],
    },
  },
)
