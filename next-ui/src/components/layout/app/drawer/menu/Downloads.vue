<template>
  <v-list-group value="Downloads">
    <template #activator="{ props }">
      <v-list-item
        v-bind="props"
        prepend-icon="i-mdi:download"
        :title="
          $formatMessage({
            description: 'Drawer menu for Downloads',
            defaultMessage: 'Downloads',
            id: 'fork/drawer/downloadsGroup',
          })
        "
      />
    </template>

    <v-list-item
      to="/downloads/dashboard"
      :title="
        $formatMessage({
          description: 'Drawer menu for Downloads > Dashboard',
          defaultMessage: 'Dashboard',
          id: 'fork/drawer/downloadsDashboard',
        })
      "
    />
    <v-list-item
      to="/downloads/queue"
      :title="
        $formatMessage({
          description: 'Drawer menu for Downloads > Queue',
          defaultMessage: 'Queue',
          id: 'fork/drawer/downloadsQueue',
        })
      "
    />
    <v-list-item
      to="/downloads/followed"
      :title="
        $formatMessage({
          description: 'Drawer menu for Downloads > Followed',
          defaultMessage: 'Followed series',
          id: 'fork/drawer/downloadsFollowed',
        })
      "
    />
    <v-list-item
      to="/downloads/discover"
      :title="
        $formatMessage({
          description: 'Drawer menu for Downloads > Discover',
          defaultMessage: 'Discover',
          id: 'fork/drawer/downloadsDiscover',
        })
      "
    />
    <v-list-item
      to="/downloads/blacklisted"
      :title="
        $formatMessage({
          description: 'Drawer menu for Downloads > Blacklisted',
          defaultMessage: 'Blacklisted chapters',
          id: 'fork/drawer/downloadsBlacklisted',
        })
      "
    />
  </v-list-group>
</template>

<script setup lang="ts"></script>
