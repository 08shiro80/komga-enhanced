import {
  defineMutation,
  defineQuery,
  defineQueryOptions,
  useMutation,
  useQuery,
  useQueryCache,
} from '@pinia/colada'
import { type ClientSettingUserSettings } from '@/types/ClientSettingsUser'
import {
  type ClientSettingUserUpdateDto,
  komgaGetGlobalSettings,
  komgaGetUserSettings,
  komgaSaveUserSetting,
} from '@/generated/openapi'
import { parseUserSettings } from '@/functions/user-settings'
import { STALE_TIME } from '@/types/time'

export const QUERY_KEYS_CLIENT_SETTINGS = {
  root: ['client-settings'] as const,
  global: () => [...QUERY_KEYS_CLIENT_SETTINGS.root, 'global'] as const,
  user: () => [...QUERY_KEYS_CLIENT_SETTINGS.root, 'user'] as const,
}

export const clientSettingsUserQuery = defineQueryOptions({
  key: QUERY_KEYS_CLIENT_SETTINGS.user(),
  query: () => komgaGetUserSettings(),
  staleTime: STALE_TIME.LONG,
  gcTime: false,
})

export const useClientSettingsUser = defineQuery(() => {
  const { data, ...rest } = useQuery(clientSettingsUserQuery)

  const userSettings = computed(() => parseUserSettings(data.value))

  return {
    data,
    ...rest,
    userSettings,
  }
})

export const clientSettingsGlobalQuery = defineQueryOptions({
  key: QUERY_KEYS_CLIENT_SETTINGS.global(),
  query: () => komgaGetGlobalSettings(),
  staleTime: STALE_TIME.LONG,
  gcTime: false,
})

export const useClientSettingsGlobal = defineQuery(() => {
  const { data, ...rest } = useQuery(clientSettingsGlobalQuery)

  const guestAccessEnabled = computed(() => data.value?.['webui.guest_access']?.value === 'true')

  return {
    data,
    ...rest,
    guestAccessEnabled,
  }
})

export const useUpdateClientSettingsUser = defineMutation(() => {
  const queryCache = useQueryCache()
  return useMutation({
    mutation: (settings: Partial<ClientSettingUserSettings>) => {
      const body: Record<string, ClientSettingUserUpdateDto> = {}
      for (const [key, value] of Object.entries(settings)) {
        if (value !== undefined) {
          body[key] = { value: JSON.stringify(value) }
        }
      }
      return komgaSaveUserSetting({ body })
    },
    onSuccess: () => {
      void queryCache.invalidateQueries({ key: QUERY_KEYS_CLIENT_SETTINGS.user() })
    },
  })
})
