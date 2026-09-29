<template>
  <v-list nav>
    <LayoutAppDrawerMenuLibraries />
    <LayoutAppDrawerMenuDownloads v-if="isAdmin" />
    <LayoutAppDrawerMenuImport v-if="isAdmin" />
    <LayoutAppDrawerMenuMedia v-if="isAdmin" />
    <LayoutAppDrawerMenuHistory v-if="isAdmin" />
    <LayoutAppDrawerMenuServer v-if="isAdmin" />
    <LayoutAppDrawerMenuAccount />
    <LayoutAppDrawerMenuLogout />
  </v-list>
</template>

<script setup lang="ts">
import { useCurrentUser } from '@/colada/users'

const { isAdmin } = useCurrentUser()
</script>
