<template>
  <v-container max-width="550px">
    <v-row class="justify-center">
      <v-col>
        <v-img
          src="@/assets/logo.svg"
          width="500"
          height="500"
        />
      </v-col>
    </v-row>
    <v-row>
      <v-col>
        <v-progress-linear
          indeterminate
          color="primary"
        />
      </v-col>
    </v-row>
  </v-container>
</template>

<script lang="ts" setup>
import { isGuestAllowed, useCurrentUser } from '@/colada/users'
import { useAppStore } from '@/stores/app'

definePage({ alias: '/next' })

async function checkAuthenticated() {
  const router = useRouter()
  const route = useRoute()
  const appStore = useAppStore()
  const { data, refresh } = useCurrentUser()

  await refresh()
  await nextTick()
  // Treat a guest user as authenticated only when Browse as guest was explicitly chosen; otherwise
  // (real user missing, or guest without that choice) send to the login page.
  if (isGuestAllowed(data.value, appStore.guestMode)) {
    if (route.query.redirect) {
      void router.push(route.query.redirect.toString())
    } else {
      void router.push('/')
    }
  } else {
    void router.push({ name: '/login', query: { redirect: route.query.redirect } })
  }
}

onMounted(() => checkAuthenticated())

// TODO: exchange header token for cookie
</script>

<route lang="yaml">
meta:
  layout: single
  noAuth: true
</route>
