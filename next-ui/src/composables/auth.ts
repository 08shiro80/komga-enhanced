import { createGlobalState } from '@vueuse/core'
import { authErrorEvent } from '@/colada/error-handling'
import { logger } from '@/services/logtape'

/**
 * Watcher for the authenticated state.
 * Redirects to the login page when an authentication error happens.
 */
export const useAuthWatcher = createGlobalState(() => {
  const router = useRouter()

  watch(authErrorEvent, (newValue) => {
    if (newValue) {
      logger.debug('Auth error detected, redirect to login')
      void router.push('/login')
      // Reset so the flag stays edge-triggered: after an SPA re-login (no full reload) a later session
      // expiry must produce a fresh false->true transition, otherwise the watcher never fires again.
      authErrorEvent.value = false
    }
  })
})
