<template>
  <div>
    <v-container fluid>
      <v-row>
        <v-col>
          <h1 class="text-h4 mb-4">
            <v-icon large class="mr-2">mdi-download</v-icon>
            Downloads
            <v-chip
              small
              color="success"
              class="ml-2"
            >
              <v-icon x-small left>mdi-broadcast</v-icon>
              Live
            </v-chip>
          </h1>
        </v-col>
      </v-row>

      <!-- Stats Cards -->
      <v-row dense>
        <v-col cols="6" sm="3">
          <v-card>
            <v-card-text class="pa-3">
              <div class="text-h5 text-sm-h4">{{ activeDownloads.length }}</div>
              <div class="text-caption text-sm-subtitle-2">Active</div>
            </v-card-text>
          </v-card>
        </v-col>
        <v-col cols="6" sm="3">
          <v-card>
            <v-card-text class="pa-3">
              <div class="text-h5 text-sm-h4">{{ pendingDownloads.length }}</div>
              <div class="text-caption text-sm-subtitle-2">Pending</div>
            </v-card-text>
          </v-card>
        </v-col>
        <v-col cols="6" sm="3">
          <v-card>
            <v-card-text class="pa-3">
              <div class="text-h5 text-sm-h4 success--text">{{ completedDownloads.length }}</div>
              <div class="text-caption text-sm-subtitle-2">Completed</div>
            </v-card-text>
          </v-card>
        </v-col>
        <v-col cols="6" sm="3">
          <v-card>
            <v-card-text class="pa-3">
              <div class="text-h5 text-sm-h4 error--text">{{ failedDownloads.length }}</div>
              <div class="text-caption text-sm-subtitle-2">Failed</div>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- Active Downloads -->
      <v-row v-if="activeDownloads.length > 0">
        <v-col cols="12">
          <v-card>
            <v-card-title>
              <v-icon left>mdi-download-circle</v-icon>
              Active Downloads
            </v-card-title>
            <v-card-text>
              <v-list>
                <v-list-item v-for="download in activeDownloads" :key="download.id">
                  <v-list-item-content>
                    <v-list-item-title>{{ download.title || download.sourceUrl }}</v-list-item-title>
                    <v-list-item-subtitle>
                      {{ download.currentChapter }}/{{ download.totalChapters }} chapters
                    </v-list-item-subtitle>
                    <v-progress-linear
                      :value="download.progressPercent"
                      height="25"
                      class="mt-2"
                    >
                      <strong>{{ download.progressPercent }}%</strong>
                    </v-progress-linear>
                  </v-list-item-content>
                  <v-list-item-action>
                    <div>
                      <v-btn icon @click="pauseDownload(download)" title="Pause">
                        <v-icon>mdi-pause</v-icon>
                      </v-btn>
                      <v-btn icon @click="cancelDownload(download)" title="Cancel">
                        <v-icon color="error">mdi-close</v-icon>
                      </v-btn>
                    </div>
                  </v-list-item-action>
                </v-list-item>
              </v-list>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- MangaDex Search -->
      <v-row class="mb-2">
        <v-col cols="12">
          <v-card outlined>
            <v-card-title class="flex-wrap pb-2">
              <v-icon left color="primary">mdi-magnify</v-icon>
              <span class="text-subtitle-1 text-sm-h6">Find Manga on MangaDex</span>
            </v-card-title>
            <v-card-text class="pb-2">
              <v-row align="center" dense>
                <v-col cols="12" sm="5" md="5">
                  <v-text-field
                    v-model="searchQuery"
                    label="Search title..."
                    outlined
                    dense
                    hide-details
                    clearable
                    prepend-inner-icon="mdi-magnify"
                    :loading="searchLoading"
                    @keyup.enter="searchMangaDex"
                  />
                </v-col>
                <v-col cols="12" sm="4" md="4">
                  <v-select
                    v-model="searchLibraryId"
                    :items="libraries"
                    item-text="name"
                    item-value="id"
                    label="Target library"
                    outlined
                    dense
                    hide-details
                  />
                </v-col>
                <v-col cols="12" sm="3" md="3">
                  <v-btn color="primary" depressed block @click="searchMangaDex" :loading="searchLoading" :disabled="!searchQuery && !hasFilters">
                    <v-icon left>mdi-magnify</v-icon>
                    {{ searchQuery ? 'Search' : 'Browse' }}
                  </v-btn>
                </v-col>
              </v-row>

              <v-expansion-panels flat tile v-model="advancedPanel" class="mt-2">
                <v-expansion-panel>
                  <v-expansion-panel-header class="px-0 py-1 text--secondary">
                    <span>
                      <v-icon small left>mdi-filter-variant</v-icon>
                      Advanced filters (random browse when title is empty)
                      <v-chip v-if="hasFilters" x-small color="primary" class="ml-2">{{ activeFilterCount }} active</v-chip>
                    </span>
                  </v-expansion-panel-header>
                  <v-expansion-panel-content class="pa-0">
                    <v-row dense>
                      <v-col cols="12" md="6">
                        <v-autocomplete
                          v-model="filterTags"
                          :items="tagOptions"
                          item-text="name"
                          item-value="id"
                          label="Include tags / genres"
                          multiple chips small-chips deletable-chips
                          outlined dense hide-details
                          :loading="loadingTags"
                          @focus="loadTagsIfNeeded"
                        >
                          <template v-slot:item="{ item }">
                            <v-list-item-content>
                              <v-list-item-title>{{ item.name }}</v-list-item-title>
                              <v-list-item-subtitle class="caption">{{ item.group }}</v-list-item-subtitle>
                            </v-list-item-content>
                          </template>
                        </v-autocomplete>
                      </v-col>
                      <v-col cols="12" md="6">
                        <v-autocomplete
                          v-model="filterExcludedTags"
                          :items="tagOptions"
                          item-text="name"
                          item-value="id"
                          label="Blacklist tags / genres"
                          multiple chips small-chips deletable-chips
                          outlined dense hide-details
                          :loading="loadingTags"
                          @focus="loadTagsIfNeeded"
                          color="error"
                          item-color="error"
                        >
                          <template v-slot:item="{ item }">
                            <v-list-item-content>
                              <v-list-item-title>{{ item.name }}</v-list-item-title>
                              <v-list-item-subtitle class="caption">{{ item.group }}</v-list-item-subtitle>
                            </v-list-item-content>
                          </template>
                        </v-autocomplete>
                      </v-col>
                      <v-col cols="12" md="6">
                        <v-select
                          v-model="filterStatus"
                          :items="['ongoing','completed','hiatus','cancelled']"
                          label="Status" multiple chips small-chips deletable-chips
                          outlined dense hide-details
                        />
                      </v-col>
                      <v-col cols="12" md="6">
                        <v-select
                          v-model="filterRating"
                          :items="['safe','suggestive','erotica','pornographic']"
                          label="Content rating" multiple chips small-chips deletable-chips
                          outlined dense hide-details
                          hint="Empty = all"
                          persistent-hint
                        />
                      </v-col>
                      <v-col cols="12" md="6">
                        <v-select
                          v-model="filterDemographic"
                          :items="['shounen','shoujo','seinen','josei','none']"
                          label="Publication demographic" multiple chips small-chips deletable-chips
                          outlined dense hide-details
                        />
                      </v-col>
                      <v-col cols="12" md="6" class="d-flex align-center">
                        <v-switch
                          v-model="filterAvailableOnly"
                          label="Only titles with downloadable chapters"
                          hint="1 extra MangaDex API call per result (24h cache). Hides external-link / 0-page chapters."
                          persistent-hint
                          dense
                          class="mt-0"
                        />
                      </v-col>
                      <v-col cols="12" md="6" class="d-flex align-center">
                        <v-switch
                          v-model="filterHideFollowed"
                          label="Hide titles already in any follow list"
                          hide-details
                          dense
                          class="mt-0"
                        />
                      </v-col>
                      <v-col cols="12" md="6" class="d-flex align-center">
                        <v-switch
                          v-model="filterHideMangaDexFollowed"
                          label="Hide titles already on MangaDex follow list"
                          hint="Needs the MangaDex Subscription plugin (uses its credentials)."
                          persistent-hint
                          dense
                          class="mt-0"
                          :disabled="!mangaDexPluginEnabled"
                        />
                      </v-col>
                      <v-col cols="12" md="6" class="d-flex align-center">
                        <v-select
                          v-model="searchOrder"
                          :items="sortOptions"
                          label="Sort by"
                          dense
                          hide-details
                          outlined
                          class="mt-0 me-2"
                          @change="onSortChange"
                        />
                        <v-select
                          v-model="searchOrderDir"
                          :items="[{text: 'Desc', value: 'desc'}, {text: 'Asc', value: 'asc'}]"
                          label="Direction"
                          dense
                          hide-details
                          outlined
                          style="max-width:120px"
                          @change="onSortChange"
                        />
                      </v-col>
                      <v-col cols="12" class="d-flex align-center pt-2">
                        <v-btn small text @click="saveFilterDefaults" :color="filtersDirty ? 'primary' : ''">
                          <v-icon small left>mdi-content-save</v-icon>
                          {{ filtersDirty ? 'Save as default' : 'Saved as default' }}
                        </v-btn>
                        <v-btn small text @click="clearFilters">
                          <v-icon small left>mdi-close</v-icon>
                          Clear all
                        </v-btn>
                        <v-spacer />
                        <span class="caption text--secondary">Defaults are stored in your account</span>
                      </v-col>
                    </v-row>
                  </v-expansion-panel-content>
                </v-expansion-panel>
              </v-expansion-panels>

              <v-row v-if="searchResults.length > 0" class="mt-2" dense>
                <v-col
                  v-for="manga in searchResults"
                  :key="manga.externalId"
                  cols="4" sm="3" md="2" lg="2"
                  style="min-width:130px;max-width:180px;"
                >
                  <v-card outlined height="100%" class="d-flex flex-column">
                    <div class="grey lighten-3 d-flex align-center justify-center" style="width:100%;padding-top:150%;position:relative;cursor:pointer;" @click="showMangaDetails(manga)" title="Show description">
                      <img
                        v-if="manga.coverUrl"
                        :src="manga.coverUrl"
                        referrerpolicy="no-referrer"
                        alt=""
                        style="position:absolute;top:0;left:0;width:100%;height:100%;object-fit:cover;"
                      />
                      <v-icon v-else color="grey lighten-1" style="position:absolute;">mdi-book-open-page-variant</v-icon>
                    </div>
                    <v-card-text class="pa-1 flex-grow-1">
                      <div class="text-caption font-weight-bold" style="line-height:1.2;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;cursor:pointer;" @click="showMangaDetails(manga)">{{ manga.title }}</div>
                      <v-chip v-if="manga.status" x-small class="mt-1" :color="statusColor(manga.status)">{{ manga.status }}</v-chip>
                    </v-card-text>
                    <v-card-actions class="pa-1 pt-0 flex-column">
                      <v-btn
                        x-small block depressed
                        :color="searchAction[manga.externalId] === 'downloaded' ? 'success' : searchAction[manga.externalId] === 'error' ? 'error' : 'primary'"
                        :loading="searchBusy[manga.externalId + ':dl']"
                        :disabled="!!searchAction[manga.externalId]"
                        @click="downloadFromSearch(manga)"
                      >
                        <v-icon x-small left>{{ searchAction[manga.externalId] === 'downloaded' ? 'mdi-check' : 'mdi-download' }}</v-icon>
                        {{ searchAction[manga.externalId] === 'downloaded' ? 'Queued' : 'Download' }}
                      </v-btn>
                      <v-btn
                        x-small block depressed outlined
                        class="mt-1 mx-0"
                        :color="isFollowed(manga) ? 'success' : (searchFollow[manga.externalId] === 'error' ? 'error' : '')"
                        :loading="searchBusy[manga.externalId + ':fl']"
                        @click="toggleFollow(manga)"
                      >
                        <v-icon x-small left>{{ isFollowed(manga) ? 'mdi-check' : 'mdi-playlist-plus' }}</v-icon>
                        Follow
                      </v-btn>
                      <v-btn
                        v-if="mangaDexPluginEnabled"
                        x-small block depressed outlined
                        class="mt-1 mx-0"
                        :color="isMangaDexFollowed(manga) ? 'success' : (mangaDexFollowError[manga.externalId] ? 'error' : '')"
                        :loading="!!mangaDexFollowBusy[manga.externalId]"
                        @click="toggleMangaDexFollow(manga)"
                      >
                        <v-icon x-small left>{{ isMangaDexFollowed(manga) ? 'mdi-check' : 'mdi-bookmark-plus-outline' }}</v-icon>
                        MangaDex
                      </v-btn>
                    </v-card-actions>
                  </v-card>
                </v-col>
              </v-row>

              <v-dialog v-model="detailsDialog" max-width="700" scrollable>
                <v-card v-if="detailsManga">
                  <v-card-title class="text-h6" style="word-break:normal;">{{ detailsManga.title }}</v-card-title>
                  <v-card-text>
                    <v-row dense>
                      <v-col cols="12" sm="4">
                        <img
                          v-if="detailsManga.coverUrl"
                          :src="detailsManga.coverUrl"
                          referrerpolicy="no-referrer"
                          alt=""
                          style="width:100%;border-radius:4px;"
                        />
                      </v-col>
                      <v-col cols="12" sm="8">
                        <div class="mb-2">
                          <v-chip v-if="detailsManga.status" x-small :color="statusColor(detailsManga.status)" class="me-1">{{ detailsManga.status }}</v-chip>
                          <v-chip v-if="detailsManga.year" x-small class="me-1">{{ detailsManga.year }}</v-chip>
                          <span v-if="detailsManga.author" class="text-caption">{{ detailsManga.author }}</span>
                        </div>
                        <div v-if="detailsManga.tags && detailsManga.tags.length" class="mb-2">
                          <v-chip v-for="t in detailsManga.tags" :key="t" x-small outlined class="me-1 mb-1">{{ t }}</v-chip>
                        </div>
                        <div class="body-2" style="white-space:pre-line;">{{ detailsManga.description || 'No description available.' }}</div>
                      </v-col>
                    </v-row>
                  </v-card-text>
                  <v-card-actions>
                    <v-btn
                      text
                      color="primary"
                      :disabled="!!searchAction[detailsManga.externalId]"
                      @click="downloadFromSearch(detailsManga)"
                    >
                      <v-icon left>mdi-download</v-icon>
                      {{ searchAction[detailsManga.externalId] === 'downloaded' ? 'Queued' : 'Download' }}
                    </v-btn>
                    <v-spacer />
                    <v-btn text @click="detailsDialog = false">Close</v-btn>
                  </v-card-actions>
                </v-card>
              </v-dialog>

              <div v-if="searchDone && searchResults.length === 0 && !searchLoading" class="text-center py-2 text--secondary">
                No results found{{ lastSearchSkippedNote }}
              </div>

              <v-alert v-if="followedUuidsLoadError" type="warning" dense text class="mt-2 mb-0">
                Could not read follow list from: {{ followedUuidsLoadError }}
              </v-alert>

              <div v-if="searchDone && searchPageCount > 1" class="d-flex align-center justify-center mt-2">
                <v-pagination
                  :value="searchPage"
                  :length="searchPageCount"
                  :total-visible="7"
                  @input="onSearchPageChange"
                  :disabled="searchLoading"
                />
                <span class="caption text--secondary ml-3">{{ searchTotal }} total</span>
              </div>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- Download Queue (All) -->
      <v-row>
        <v-col cols="12">
          <v-card>
            <v-card-title class="flex-wrap">
              <span class="text-subtitle-1 text-sm-h6">Download Queue</span>
              <v-spacer></v-spacer>
              <v-btn color="primary" @click="newDownloadDialog = true">
                <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-plus</v-icon>
                <span class="d-none d-sm-inline">New Download</span>
              </v-btn>
              <v-menu offset-y>
                <template v-slot:activator="{ on, attrs }">
                  <v-btn text v-bind="attrs" v-on="on" class="ml-2">
                    <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-broom</v-icon>
                    <span class="d-none d-sm-inline">Clear</span>
                    <v-icon right>mdi-menu-down</v-icon>
                  </v-btn>
                </template>
                <v-list dense>
                  <v-list-item @click="clearByStatus('completed')" :disabled="completedDownloads.length === 0">
                    <v-list-item-icon><v-icon color="success">mdi-check-circle</v-icon></v-list-item-icon>
                    <v-list-item-content>Clear Completed ({{ completedDownloads.length }})</v-list-item-content>
                  </v-list-item>
                  <v-list-item @click="clearByStatus('failed')" :disabled="failedDownloads.length === 0">
                    <v-list-item-icon><v-icon color="error">mdi-alert-circle</v-icon></v-list-item-icon>
                    <v-list-item-content>Clear Failed ({{ failedDownloads.length }})</v-list-item-content>
                  </v-list-item>
                  <v-list-item @click="clearByStatus('cancelled')" :disabled="cancelledDownloads.length === 0">
                    <v-list-item-icon><v-icon color="warning">mdi-cancel</v-icon></v-list-item-icon>
                    <v-list-item-content>Clear Cancelled ({{ cancelledDownloads.length }})</v-list-item-content>
                  </v-list-item>
                  <v-divider></v-divider>
                  <v-list-item @click="clearByStatus('pending')" :disabled="pendingDownloads.length === 0">
                    <v-list-item-icon><v-icon color="grey">mdi-clock-outline</v-icon></v-list-item-icon>
                    <v-list-item-content>Clear Pending ({{ pendingDownloads.length }})</v-list-item-content>
                  </v-list-item>
                </v-list>
              </v-menu>
              <v-btn icon @click="loadDownloads" :loading="loading" class="ml-2">
                <v-icon>mdi-refresh</v-icon>
              </v-btn>
            </v-card-title>

            <v-card-text>
              <v-tabs v-model="tab">
                <v-tab>All</v-tab>
                <v-tab>Pending</v-tab>
                <v-tab>Downloading</v-tab>
                <v-tab>Completed</v-tab>
                <v-tab>Failed</v-tab>
                <v-tab>
                  <v-icon left>mdi-cog</v-icon>
                  Configuration
                </v-tab>
                <v-tab>
                  <v-icon left>mdi-import</v-icon>
                  Tachiyomi Import
                </v-tab>
              </v-tabs>

              <v-tabs-items v-model="tab" class="mt-4">
                <v-tab-item>
                  <download-table :downloads="allDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <download-table :downloads="pendingDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <download-table :downloads="activeDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <download-table :downloads="completedDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <download-table :downloads="failedDownloads" @action="handleAction" />
                </v-tab-item>
                <v-tab-item>
                  <!-- Follow Configuration Tab - DB-backed follow list -->
                  <v-row>
                    <v-col cols="12" md="4">
                      <v-card outlined>
                        <v-card-title>
                          <v-icon left>mdi-bookshelf</v-icon>
                          Libraries
                        </v-card-title>
                        <v-list>
                          <v-list-item-group v-model="selectedLibraryIndex" color="primary">
                            <v-list-item
                              v-for="(lib, index) in libraries"
                              :key="lib.id"
                              @click="selectLibrary(index)"
                            >
                              <v-list-item-content>
                                <v-list-item-title>{{ lib.name }}</v-list-item-title>
                              </v-list-item-content>
                            </v-list-item>
                          </v-list-item-group>
                        </v-list>
                      </v-card>
                    </v-col>
                    <v-col cols="12" md="8">
                      <v-card outlined v-if="selectedLibrary">
                        <v-card-title>
                          <v-icon left>mdi-rss</v-icon>
                          Follow List - {{ selectedLibrary.name }}
                          <v-spacer></v-spacer>
                          <v-btn
                            v-if="selectedFollowIds.length > 0"
                            small
                            color="error"
                            class="mr-2"
                            :loading="followBusy"
                            @click="removeSelectedFollows"
                          >
                            <v-icon left small>mdi-delete-outline</v-icon>
                            Delete ({{ selectedFollowIds.length }})
                          </v-btn>
                          <v-btn small color="primary" @click="openAddFollowDialog">
                            <v-icon left small>mdi-plus</v-icon>
                            Add
                          </v-btn>
                        </v-card-title>
                        <v-card-subtitle>
                          Manga URLs to auto-download into this library. New chapters are fetched on the schedule below or via Check now.
                        </v-card-subtitle>
                        <v-card-text>
                          <div v-if="followItems.length === 0" class="caption text--secondary">
                            No follows yet for this library.
                          </div>
                          <v-list v-else dense class="py-0">
                            <v-list-item v-for="f in followItems" :key="f.id" class="px-0">
                              <v-list-item-action class="my-0 mr-1">
                                <v-checkbox v-model="selectedFollowIds" :value="f.id" dense hide-details class="mt-0"></v-checkbox>
                              </v-list-item-action>
                              <v-list-item-action class="my-0 mr-2">
                                <v-switch v-model="f.enabled" dense hide-details class="mt-0" @change="toggleFollowEnabled(f)"></v-switch>
                              </v-list-item-action>
                              <v-list-item-content class="py-1">
                                <v-list-item-title v-if="f.title">{{ f.title }}</v-list-item-title>
                                <v-list-item-title v-else class="text--secondary font-italic">(no name)</v-list-item-title>
                                <v-list-item-subtitle style="white-space: normal; word-break: break-all">{{ f.url }}</v-list-item-subtitle>
                              </v-list-item-content>
                              <v-list-item-action class="my-0 flex-row align-center">
                                <v-btn icon small @click="openEditFollowDialog(f)">
                                  <v-icon small>mdi-pencil-outline</v-icon>
                                </v-btn>
                                <v-btn icon small @click="removeFollow(f)">
                                  <v-icon small>mdi-delete-outline</v-icon>
                                </v-btn>
                              </v-list-item-action>
                            </v-list-item>
                          </v-list>
                        </v-card-text>
                        <v-card-actions class="flex-wrap">
                          <v-btn
                            text
                            @click="checkFollowsNow"
                            :loading="checkingNow"
                          >
                            <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-update</v-icon>
                            <span class="d-none d-sm-inline">Check now</span>
                          </v-btn>
                          <v-btn
                            v-if="mangaDexPluginEnabled"
                            text
                            @click="syncToMangaDex"
                            :loading="syncingToMangaDex"
                          >
                            <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-cloud-upload</v-icon>
                            <span class="d-none d-sm-inline">Sync to MangaDex</span>
                          </v-btn>
                          <v-spacer></v-spacer>
                          <v-btn text @click="loadFollows">
                            <v-icon :left="$vuetify.breakpoint.smAndUp">mdi-refresh</v-icon>
                            <span class="d-none d-sm-inline">Reload</span>
                          </v-btn>
                        </v-card-actions>
                      </v-card>
                      <v-card outlined v-else>
                        <v-card-text class="text-center pa-8">
                          <v-icon size="64" color="grey">mdi-arrow-left</v-icon>
                          <p class="mt-4">Select a library to view its follow list</p>
                        </v-card-text>
                      </v-card>

                      <!-- Per-library Schedule -->
                      <v-card outlined class="mt-4" v-if="selectedLibrary">
                        <v-card-title>
                          <v-icon left>mdi-clock-outline</v-icon>
                          Auto-Check Schedule - {{ selectedLibrary.name }}
                        </v-card-title>
                        <v-card-text>
                          <v-row>
                            <v-col cols="12" sm="6">
                              <v-switch
                                v-model="schedule.enabled"
                                label="Enable Auto-Check"
                                hint="Automatically check and download new chapters for this library"
                                persistent-hint
                              ></v-switch>
                            </v-col>
                          </v-row>
                          <v-row>
                            <v-col cols="12">
                              <v-radio-group
                                v-model="schedule.scheduleMode"
                                row
                                label="Schedule Mode"
                              >
                                <v-radio label="Interval" value="interval"></v-radio>
                                <v-radio label="Fixed Time" value="fixed_time"></v-radio>
                              </v-radio-group>
                            </v-col>
                          </v-row>
                          <v-row>
                            <v-col cols="12" sm="6" v-if="schedule.scheduleMode === 'interval'">
                              <v-text-field
                                v-model.number="schedule.intervalHours"
                                label="Check Interval (hours)"
                                type="number"
                                outlined
                                dense
                                min="1"
                                hint="How often to check this library for new chapters"
                                persistent-hint
                              ></v-text-field>
                            </v-col>
                            <v-col cols="12" sm="6" v-if="schedule.scheduleMode === 'fixed_time'">
                              <v-text-field
                                v-model="schedule.checkTime"
                                label="Check Time (HH:mm)"
                                placeholder="03:00"
                                outlined
                                dense
                                hint="Daily time to check for new chapters (24h format)"
                                persistent-hint
                              ></v-text-field>
                            </v-col>
                          </v-row>
                        </v-card-text>
                        <v-card-actions>
                          <v-spacer></v-spacer>
                          <v-btn
                            color="primary"
                            @click="saveSchedule"
                            :loading="savingSchedule"
                          >
                            <v-icon left>mdi-content-save</v-icon>
                            Save Schedule
                          </v-btn>
                        </v-card-actions>
                      </v-card>
                    </v-col>
                  </v-row>
                </v-tab-item>
                <v-tab-item>
                  <!-- Tachiyomi Import Tab -->
                  <v-row>
                    <v-col cols="12" md="6">
                      <v-card outlined>
                        <v-card-title>
                          <v-icon left>mdi-import</v-icon>
                          Import from Tachiyomi/Mihon Backup
                        </v-card-title>
                        <v-card-subtitle>
                          Import MangaDex URLs from a Tachiyomi or Mihon backup file into a library's follow list
                        </v-card-subtitle>
                        <v-card-text>
                          <v-file-input
                            v-model="tachiyomiFile"
                            label="Backup File"
                            accept=".proto.gz,.tachibk,.json,.json.gz"
                            prepend-icon="mdi-file-upload"
                            outlined
                            show-size
                            hint="Supports .proto.gz, .tachibk, .json, .json.gz formats"
                            persistent-hint
                          ></v-file-input>

                          <v-select
                            v-model="tachiyomiLibraryId"
                            :items="libraries"
                            item-text="name"
                            item-value="id"
                            label="Target Library"
                            outlined
                            prepend-icon="mdi-bookshelf"
                            hint="MangaDex URLs will be added to this library's follow list"
                            persistent-hint
                            class="mt-4"
                          />
                        </v-card-text>
                        <v-card-actions>
                          <v-spacer></v-spacer>
                          <v-btn
                            color="primary"
                            @click="importTachiyomi"
                            :loading="importingTachiyomi"
                            :disabled="!tachiyomiFile || !tachiyomiLibraryId"
                          >
                            <v-icon left>mdi-import</v-icon>
                            Import
                          </v-btn>
                        </v-card-actions>
                      </v-card>
                    </v-col>
                    <v-col cols="12" md="6">
                      <!-- Import Result -->
                      <v-card outlined v-if="tachiyomiResult">
                        <v-card-title>
                          <v-icon left :color="tachiyomiResult.success ? 'success' : 'warning'">
                            {{ tachiyomiResult.success ? 'mdi-check-circle' : 'mdi-alert-circle' }}
                          </v-icon>
                          Import Result
                        </v-card-title>
                        <v-card-text>
                          <v-alert :type="tachiyomiResult.success ? 'success' : 'warning'" dense>
                            {{ tachiyomiResult.message }}
                          </v-alert>
                          <v-row class="mt-2">
                            <v-col cols="6" sm="3">
                              <div class="text-h5">{{ tachiyomiResult.totalInBackup }}</div>
                              <div class="text-caption">Total in Backup</div>
                            </v-col>
                            <v-col cols="6" sm="3">
                              <div class="text-h5">{{ tachiyomiResult.mangaDexCount }}</div>
                              <div class="text-caption">MangaDex</div>
                            </v-col>
                            <v-col cols="6" sm="3">
                              <div class="text-h5 success--text">{{ tachiyomiResult.importedCount }}</div>
                              <div class="text-caption">Imported</div>
                            </v-col>
                            <v-col cols="6" sm="3">
                              <div class="text-h5 grey--text">{{ tachiyomiResult.skippedCount }}</div>
                              <div class="text-caption">Skipped</div>
                            </v-col>
                          </v-row>
                          <v-expansion-panels class="mt-4" v-if="tachiyomiResult.imported.length > 0 || tachiyomiResult.errors.length > 0">
                            <v-expansion-panel v-if="tachiyomiResult.imported.length > 0">
                              <v-expansion-panel-header>
                                <v-icon left color="success" small>mdi-check</v-icon>
                                Imported ({{ tachiyomiResult.imported.length }})
                              </v-expansion-panel-header>
                              <v-expansion-panel-content>
                                <v-list dense>
                                  <v-list-item v-for="(title, i) in tachiyomiResult.imported" :key="'imp-'+i">
                                    <v-list-item-content>{{ title }}</v-list-item-content>
                                  </v-list-item>
                                </v-list>
                              </v-expansion-panel-content>
                            </v-expansion-panel>
                            <v-expansion-panel v-if="tachiyomiResult.errors.length > 0">
                              <v-expansion-panel-header>
                                <v-icon left color="error" small>mdi-alert</v-icon>
                                Errors ({{ tachiyomiResult.errors.length }})
                              </v-expansion-panel-header>
                              <v-expansion-panel-content>
                                <v-list dense>
                                  <v-list-item v-for="(err, i) in tachiyomiResult.errors" :key="'err-'+i">
                                    <v-list-item-content class="error--text">{{ err }}</v-list-item-content>
                                  </v-list-item>
                                </v-list>
                              </v-expansion-panel-content>
                            </v-expansion-panel>
                          </v-expansion-panels>
                        </v-card-text>
                      </v-card>
                      <v-card outlined v-else>
                        <v-card-text class="text-center pa-8">
                          <v-icon size="64" color="grey">mdi-file-upload-outline</v-icon>
                          <p class="mt-4">Select a backup file and target library to import</p>
                          <p class="text-caption grey--text">
                            Only MangaDex entries will be imported from the backup.
                            Other sources are not supported.
                          </p>
                        </v-card-text>
                      </v-card>
                    </v-col>
                  </v-row>
                </v-tab-item>
              </v-tabs-items>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>
    </v-container>

    <!-- New Download Dialog -->
    <v-dialog v-model="newDownloadDialog" max-width="600" :fullscreen="$vuetify.breakpoint.xsOnly">
      <v-card>
        <v-card-title>Add Download</v-card-title>
        <v-card-text>
          <v-text-field
            v-model="newDownload.sourceUrl"
            label="Source URL"
            placeholder="https://mangadex.org/title/..."
            outlined
            prepend-icon="mdi-link"
          ></v-text-field>

          <v-select
            v-model="newDownload.libraryId"
            :items="libraries"
            item-text="name"
            item-value="id"
            label="Target Library"
            outlined
            prepend-icon="mdi-bookshelf"
            hint="Downloads will go directly into this library folder"
            persistent-hint
          />

          <v-slider
            v-model="newDownload.priority"
            :min="1"
            :max="10"
            label="Priority"
            thumb-label
            prepend-icon="mdi-flag"
            class="mt-4"
          ></v-slider>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="newDownloadDialog = false">Cancel</v-btn>
          <v-btn color="primary" @click="addDownload" :loading="adding">
            Add to Queue
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Add Follow Dialog -->
    <v-dialog v-model="addFollowDialog" max-width="640" :fullscreen="$vuetify.breakpoint.xsOnly">
      <v-card>
        <v-card-title>Add to follow list<span v-if="selectedLibrary" class="ml-1">— {{ selectedLibrary.name }}</span></v-card-title>
        <v-card-text>
          <v-text-field
            v-model="followForm.url"
            label="Manga URL *"
            placeholder="https://mangadex.org/title/..."
            outlined
            prepend-icon="mdi-link"
            @keyup.enter="submitAddFollow"
          ></v-text-field>
          <v-text-field
            v-model="followForm.title"
            label="Name (optional)"
            outlined
            prepend-icon="mdi-book"
            hint="Shown in the follow list; auto-filled from the download when left empty"
            persistent-hint
          ></v-text-field>

          <v-divider class="my-4"></v-divider>
          <p class="caption text--secondary mb-1">Or add many at once (one URL per line):</p>
          <v-textarea
            v-model="followBatchText"
            label="Batch URLs"
            outlined
            dense
            rows="4"
            hide-details
            prepend-icon="mdi-playlist-plus"
          ></v-textarea>
        </v-card-text>
        <v-card-actions>
          <v-btn
            text
            :disabled="!followBatchText.trim() || followBusy"
            @click="submitAddFollowsBatch"
          >
            <v-icon left small>mdi-playlist-plus</v-icon>
            Import list
          </v-btn>
          <v-spacer></v-spacer>
          <v-btn text @click="addFollowDialog = false">Cancel</v-btn>
          <v-btn color="primary" :disabled="!followForm.url.trim() || followBusy" :loading="followBusy" @click="submitAddFollow">
            <v-icon left small>mdi-plus</v-icon>
            Add
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Edit Follow Name Dialog -->
    <v-dialog v-model="editFollowDialog" max-width="560" :fullscreen="$vuetify.breakpoint.xsOnly">
      <v-card>
        <v-card-title>Edit name</v-card-title>
        <v-card-text>
          <p class="caption text--secondary mb-2" style="word-break: break-all">{{ editFollowItem && editFollowItem.url }}</p>
          <v-text-field
            v-model="editFollowTitle"
            label="Name"
            outlined
            prepend-icon="mdi-book"
            @keyup.enter="submitEditFollow"
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="editFollowDialog = false">Cancel</v-btn>
          <v-btn color="primary" :loading="followBusy" @click="submitEditFollow">Save</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Snackbar -->
    <v-snackbar v-model="snackbar" :color="snackbarColor" :timeout="3000" bottom>
      {{ snackbarText }}
      <template v-slot:action="{ attrs }">
        <v-btn text v-bind="attrs" @click="snackbar = false">Close</v-btn>
      </template>
    </v-snackbar>
  </div>
</template>

<script>
import DownloadTable from '../components/DownloadTable.vue'
import {
  DOWNLOAD_STARTED,
  DOWNLOAD_PROGRESS,
  DOWNLOAD_COMPLETED,
  DOWNLOAD_FAILED,
} from '@/types/events'

export default {
  name: 'DownloadDashboard',
  components: {
    DownloadTable,
  },
  data() {
    return {
      downloads: [],
      libraries: [],
      loading: false,
      adding: false,
      tab: 0,
      newDownloadDialog: false,
      newDownload: {
        sourceUrl: '',
        libraryId: null,
        priority: 5,
      },
      snackbar: false,
      snackbarText: '',
      snackbarColor: 'success',
      // Library follow list (DB-backed)
      selectedLibraryIndex: null,
      followItems: [],
      selectedFollowIds: [],
      followBatchText: '',
      followBusy: false,
      addFollowDialog: false,
      followForm: { url: '', title: '' },
      editFollowDialog: false,
      editFollowItem: null,
      editFollowTitle: '',
      checkingNow: false,
      syncingToMangaDex: false,
      mangaDexPluginEnabled: false,
      // Per-library schedule
      schedule: {
        enabled: false,
        scheduleMode: 'interval',
        intervalHours: 24,
        checkTime: '03:00',
      },
      savingSchedule: false,
      // SSE connection status (using existing SSE infrastructure)
      sseConnected: true,
      // Tachiyomi import
      tachiyomiFile: null,
      tachiyomiLibraryId: null,
      importingTachiyomi: false,
      tachiyomiResult: null,
      // MangaDex search (top of page)
      searchQuery: '',
      searchLibraryId: null,
      searchResults: [],
      searchLoading: false,
      searchDone: false,
      searchBusy: {},
      searchAction: {},
      searchFollow: {},
      // Advanced filters
      advancedPanel: null,
      filterTags: [],
      filterExcludedTags: [],
      filterStatus: [],
      filterRating: [],
      filterDemographic: [],
      filterAvailableOnly: false,
      filterHideFollowed: false,
      filterHideMangaDexFollowed: false,
      mangaDexFollowedUuids: [],
      mangaDexFollowBusy: {},
      mangaDexFollowError: {},
      lastSearchSkippedMangaDexFollow: 0,
      tagOptions: [],
      loadingTags: false,
      savedFiltersHash: '',
      FILTER_DEFAULTS_KEY: 'komga.fork.mangadexsearch.defaults',
      lastSearchSkippedAvailable: 0,
      lastSearchSkippedFollow: 0,
      // Pagination
      searchPageSize: 24,
      searchPage: 1,
      searchTotal: 0,
      searchPageRawStart: {1: 0},
      searchKnownPages: 1,
      searchOrder: '',
      searchOrderDir: 'desc',
      followedUuids: [],
      followedUuidsLoadError: '',
      detailsDialog: false,
      detailsManga: null,
      TARGET_LIBRARY_KEY: 'komga.fork.mangadexsearch.targetlibrary',
    }
  },
  watch: {
    searchLibraryId(val) {
      if (val) {
        try {
          localStorage.setItem(this.TARGET_LIBRARY_KEY, val)
        } catch (_) { /* ignore */ }
      }
    },
  },
  computed: {
    sortOptions() {
      return [
        {text: 'Relevance', value: ''},
        {text: 'Popularity', value: 'followedCount'},
        {text: 'Latest chapter', value: 'latestUploadedChapter'},
        {text: 'Recently added', value: 'createdAt'},
        {text: 'Recently updated', value: 'updatedAt'},
        {text: 'Title', value: 'title'},
        {text: 'Rating', value: 'rating'},
        {text: 'Year', value: 'year'},
      ]
    },
    currentFilterPayload() {
      return {
        t: [...(this.filterTags || [])].sort(),
        x: [...(this.filterExcludedTags || [])].sort(),
        s: [...(this.filterStatus || [])].sort(),
        r: [...(this.filterRating || [])].sort(),
        d: [...(this.filterDemographic || [])].sort(),
        a: !!this.filterAvailableOnly,
        h: !!this.filterHideFollowed,
        m: !!this.filterHideMangaDexFollowed,
        o: this.searchOrder || '',
        od: this.searchOrderDir || 'desc',
      }
    },
    currentFilterHash() {
      return JSON.stringify(this.currentFilterPayload)
    },
    filtersDirty() {
      return this.currentFilterHash !== this.savedFiltersHash
    },
    hasFilters() {
      return this.activeFilterCount > 0
    },
    lastSearchSkippedNote() {
      const parts = []
      if (this.lastSearchSkippedFollow > 0) parts.push(`${this.lastSearchSkippedFollow} already in follow list`)
      if (this.lastSearchSkippedMangaDexFollow > 0) parts.push(`${this.lastSearchSkippedMangaDexFollow} already on MangaDex follow list`)
      if (this.lastSearchSkippedAvailable > 0) parts.push(`${this.lastSearchSkippedAvailable} without downloadable chapters`)
      return parts.length > 0 ? ` (${parts.join(', ')} hidden)` : ''
    },
    searchReducesResults() {
      return this.filterHideFollowed || this.filterHideMangaDexFollowed || this.filterAvailableOnly
    },
    searchPageCount() {
      if (this.searchReducesResults) return Math.max(this.searchKnownPages, 1)
      if (!this.searchTotal || this.searchTotal <= this.searchPageSize) return 1
      return Math.min(Math.ceil(this.searchTotal / this.searchPageSize), 417)
    },
    activeFilterCount() {
      let n = 0
      if (this.filterTags && this.filterTags.length > 0) n++
      if (this.filterExcludedTags && this.filterExcludedTags.length > 0) n++
      if (this.filterStatus && this.filterStatus.length > 0) n++
      if (this.filterRating && this.filterRating.length > 0) n++
      if (this.filterDemographic && this.filterDemographic.length > 0) n++
      if (this.filterAvailableOnly) n++
      if (this.filterHideFollowed) n++
      if (this.filterHideMangaDexFollowed) n++
      return n
    },
    allDownloads() {
      return this.downloads
    },
    activeDownloads() {
      return this.downloads.filter(d => d.status === 'DOWNLOADING')
    },
    pendingDownloads() {
      return this.downloads.filter(d => d.status === 'PENDING')
    },
    completedDownloads() {
      return this.downloads.filter(d => d.status === 'COMPLETED')
    },
    failedDownloads() {
      return this.downloads.filter(d => d.status === 'FAILED')
    },
    cancelledDownloads() {
      return this.downloads.filter(d => d.status === 'CANCELLED')
    },
    selectedLibrary() {
      if (this.selectedLibraryIndex === null || this.selectedLibraryIndex === undefined) return null
      return this.libraries[this.selectedLibraryIndex]
    },
  },
  mounted() {
    this.loadDownloads()
    this.loadLibraries()
    this.loadMangaDexPluginStatus()
    this.loadMangaDexFollowedUuids()
    this.loadFilterDefaults()
    // Eagerly fetch the tag catalog so chips/labels in restored defaults
    // resolve to names instead of bare UUIDs without waiting for focus.
    this.loadTagsIfNeeded()
    this.setupSseListeners()
  },
  beforeDestroy() {
    this.removeSseListeners()
  },
  methods: {
    async loadDownloads() {
      this.loading = true
      try {
        const response = await this.$http.get('/api/v1/downloads')
        this.downloads = response.data
      } catch (error) {
        this.showError('Failed to load downloads: ' + error.message)
      } finally {
        this.loading = false
      }
    },
    async loadLibraries() {
      try {
        const response = await this.$komgaLibraries.getLibraries()
        this.libraries = response
        if (this.libraries.length > 0) {
          this.selectLibrary(0)
          if (!this.searchLibraryId) {
            let stored = null
            try {
              stored = localStorage.getItem(this.TARGET_LIBRARY_KEY)
            } catch (_) { /* ignore */ }
            const match = stored && this.libraries.some(l => l.id === stored)
            this.searchLibraryId = match ? stored : this.libraries[0].id
          }
        }
        this.refreshFollowedUuids()
      } catch (error) {
        // Library loading failed
      }
    },

    // ── MangaDex Search (top of page) ─────────────────────────────────────
    statusColor(status) {
      return {
        ongoing: 'primary', releasing: 'primary',
        completed: 'success', ended: 'success', finished: 'success',
        hiatus: 'warning',
        cancelled: 'error', canceled: 'error',
      }[String(status || '').toLowerCase()] || 'grey'
    },
    extractMangaDexUuid(url) {
      const m = /mangadex\.org\/(?:title|manga)\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i.exec(String(url || ''))
      return m ? m[1].toLowerCase() : null
    },
    async refreshFollowedUuids() {
      const seen = new Set()
      const errors = []
      const fetches = (this.libraries || []).map(lib =>
        this.$komgaFollows.getAll(lib.id).then(items => {
          for (const f of items) {
            const u = this.extractMangaDexUuid(f.url)
            if (u) seen.add(u)
          }
        }).catch(e => {
          errors.push(`${lib && lib.name}: ${(e && e.message) || 'unknown'}`)
        }),
      )
      await Promise.all(fetches)
      this.followedUuids = Array.from(seen)
      this.followedUuidsLoadError = errors.length > 0 ? errors.join('; ') : ''
    },
    isFollowed(manga) {
      return this.followedUuids.indexOf(String(manga.externalId || '').toLowerCase()) >= 0
    },
    isMangaDexFollowed(manga) {
      return this.mangaDexFollowedUuids.indexOf(String(manga.externalId || '').toLowerCase()) >= 0
    },
    async loadMangaDexFollowedUuids() {
      try {
        const response = await this.$http.get('/api/v1/downloads/mangadex/follows')
        this.mangaDexFollowedUuids = (response.data && response.data.uuids ? Array.from(response.data.uuids) : []).map(u => String(u).toLowerCase())
      } catch (_) {
        this.mangaDexFollowedUuids = []
      }
    },
    async toggleMangaDexFollow(manga) {
      const id = manga.externalId
      const uuidLower = String(id || '').toLowerCase()
      this.$set(this.mangaDexFollowBusy, id, true)
      this.$set(this.mangaDexFollowError, id, null)
      try {
        const wantUnfollow = this.isMangaDexFollowed(manga)
        if (wantUnfollow) {
          await this.$http.delete(`/api/v1/downloads/mangadex/follows/${id}`)
          this.mangaDexFollowedUuids = this.mangaDexFollowedUuids.filter(u => u !== uuidLower)
          this.showSuccess(`Unfollowed on MangaDex: ${manga.title}`)
        } else {
          await this.$http.post(`/api/v1/downloads/mangadex/follows/${id}`)
          this.mangaDexFollowedUuids = this.mangaDexFollowedUuids.concat([uuidLower])
          this.showSuccess(`Followed on MangaDex: ${manga.title}`)
        }
      } catch (e) {
        this.$set(this.mangaDexFollowError, id, true)
        this.showError('MangaDex follow toggle failed: ' + (e?.response?.data?.message || e.message))
      } finally {
        this.$set(this.mangaDexFollowBusy, id, false)
      }
    },
    async toggleFollow(manga) {
      if (!this.searchLibraryId) { this.showError('Select a target library first'); return }
      const k = manga.externalId + ':fl'
      this.$set(this.searchBusy, k, true)
      this.$set(this.searchFollow, manga.externalId, null)
      try {
        const url = `https://mangadex.org/title/${manga.externalId}`
        const uuidLower = String(manga.externalId || '').toLowerCase()
        if (this.isFollowed(manga)) {
          // Remove the matching follow from every library that has it.
          let removedAnywhere = false
          for (const lib of this.libraries) {
            const items = await this.$komgaFollows.getAll(lib.id)
            const matches = items.filter(f => this.extractMangaDexUuid(f.url) === uuidLower)
            for (const f of matches) {
              await this.$komgaFollows.remove(lib.id, f.id)
              removedAnywhere = true
            }
          }
          if (removedAnywhere) {
            this.followedUuids = this.followedUuids.filter(u => u !== uuidLower)
            if (this.selectedLibrary) await this.loadFollows()
            this.showSuccess(`Unfollowed: ${manga.title}`)
          } else {
            this.showError(`Could not find ${manga.title} in any follow list`)
          }
        } else {
          await this.$komgaFollows.add(this.searchLibraryId, {url, title: manga.title})
          this.followedUuids = this.followedUuids.concat([uuidLower])
          if (this.selectedLibrary && this.selectedLibrary.id === this.searchLibraryId) await this.loadFollows()
          this.showSuccess(`Followed: ${manga.title}`)
        }
      } catch (e) {
        this.$set(this.searchFollow, manga.externalId, 'error')
        this.showError('Follow toggle failed: ' + (e?.response?.data?.message || e.message))
      } finally {
        this.$set(this.searchBusy, k, false)
      }
    },
    showMangaDetails(manga) {
      this.detailsManga = manga
      this.detailsDialog = true
    },
    onSortChange() {
      if (this.searchDone || this.searchQuery || this.hasFilters) {
        this.searchPage = 1
        this.searchMangaDex()
      }
    },
    async loadFilterDefaults() {
      let raw = null
      try {
        const settings = await this.$komgaSettings.getClientSettingsUser()
        const entry = settings && settings[this.FILTER_DEFAULTS_KEY]
        if (entry && entry.value) raw = entry.value
      } catch (_) {
        // account settings unavailable — fall through to localStorage migration
      }
      // Migration: pick up a value previously saved in this browser's localStorage
      if (!raw) {
        try {
          raw = localStorage.getItem('komga-fork.mangadex-search-defaults')
        } catch (_) { /* ignore */ }
      }
      if (raw) {
        try {
          const v = JSON.parse(raw) || {}
          this.filterTags = Array.isArray(v.t) ? v.t : []
          this.filterExcludedTags = Array.isArray(v.x) ? v.x : []
          this.filterStatus = Array.isArray(v.s) ? v.s : []
          this.filterRating = Array.isArray(v.r) ? v.r : []
          this.filterDemographic = Array.isArray(v.d) ? v.d : []
          this.filterAvailableOnly = !!v.a
          this.filterHideFollowed = !!v.h
          this.filterHideMangaDexFollowed = !!v.m
          if (typeof v.o === 'string') this.searchOrder = v.o
          if (v.od === 'asc' || v.od === 'desc') this.searchOrderDir = v.od
        } catch (_) {
          // ignore corrupt value
        }
      }
      this.savedFiltersHash = this.currentFilterHash
    },
    async saveFilterDefaults() {
      try {
        await this.$komgaSettings.updateClientSettingUser({
          [this.FILTER_DEFAULTS_KEY]: { value: JSON.stringify(this.currentFilterPayload) },
        })
        this.savedFiltersHash = this.currentFilterHash
        // Drop any stale per-browser copy now that it lives in the account
        try {
          localStorage.removeItem('komga-fork.mangadex-search-defaults')
        } catch (_) { /* ignore */ }
        this.showSuccess('Filters saved as default for your account')
      } catch (e) {
        this.showError('Failed to save defaults: ' + e.message)
      }
    },
    clearFilters() {
      this.filterTags = []
      this.filterExcludedTags = []
      this.filterStatus = []
      this.filterRating = []
      this.filterDemographic = []
      this.filterAvailableOnly = false
      this.filterHideFollowed = false
      this.filterHideMangaDexFollowed = false
    },
    async fetchPreferredLanguage() {
      // gallery-dl Downloader's default_language is the closest thing to
      // "what language do I want?" Default to 'en' if not configured.
      try {
        const r = await this.$http.get('/api/v1/plugins/gallery-dl-downloader/config')
        return (r.data && r.data.default_language) || 'en'
      } catch (_) {
        return 'en'
      }
    },
    async loadTagsIfNeeded() {
      if (this.tagOptions.length > 0 || this.loadingTags) return
      const cacheKey = 'komga-fork.mangadex-tags-cache'
      const ttlMs = 7 * 24 * 60 * 60 * 1000
      try {
        const raw = localStorage.getItem(cacheKey)
        if (raw) {
          const cached = JSON.parse(raw)
          if (cached && Array.isArray(cached.tags) && (Date.now() - cached.t) < ttlMs) {
            this.tagOptions = cached.tags
            return
          }
        }
      } catch (_) { /* ignore */ }
      this.loadingTags = true
      try {
        const r = await this.$http.get('/api/v1/plugins/mangadex-metadata/tags')
        this.tagOptions = r.data || []
        try {
          localStorage.setItem(cacheKey, JSON.stringify({ t: Date.now(), tags: this.tagOptions }))
        } catch (_) { /* ignore */ }
      } catch (e) {
        this.showError('Failed to load MangaDex tag list: ' + (e?.response?.data?.message || e.message))
      } finally {
        this.loadingTags = false
      }
    },
    async searchMangaDex(resetPage = true) {
      const q = (this.searchQuery || '').trim()
      if (!q && !this.hasFilters) return
      if (resetPage) {
        this.searchPage = 1
        this.searchPageRawStart = {1: 0}
        this.searchKnownPages = 1
      }
      this.searchLoading = true
      this.searchDone = false
      this.searchResults = []
      this.searchBusy = {}
      this.searchAction = {}
      this.searchFollow = {}
      this.lastSearchSkippedAvailable = 0
      this.lastSearchSkippedFollow = 0
      this.lastSearchSkippedMangaDexFollow = 0
      try {
        await this.refreshFollowedUuids()
        const reduces = this.searchReducesResults
        const MAX_OFFSET = 10000
        const MAX_BATCHES = 12
        let rawCursor = reduces ? (this.searchPageRawStart[this.searchPage] || 0) : (this.searchPage - 1) * this.searchPageSize
        let nextStart = rawCursor
        let lang = null
        let batches = 0
        let exhausted = false
        const collected = []
        while (collected.length < this.searchPageSize && batches < MAX_BATCHES) {
          if (rawCursor >= MAX_OFFSET) { exhausted = true; break }
          const limit = Math.min(this.searchPageSize, MAX_OFFSET - rawCursor)
          const resp = await this.$http.post('/api/v1/plugins/mangadex-metadata/search-advanced', {
            query: q || null,
            includedTagIds: this.filterTags,
            excludedTagIds: this.filterExcludedTags,
            status: this.filterStatus,
            contentRating: this.filterRating,
            publicationDemographic: this.filterDemographic,
            hasAvailableChapters: this.filterAvailableOnly || null,
            offset: rawCursor,
            limit,
            order: this.searchOrder || null,
            orderDir: this.searchOrderDir || null,
          })
          batches++
          const page = resp.data || {}
          const batch = page.data || []
          this.searchTotal = page.total || 0
          if (batch.length === 0) { exhausted = true; break }

          let availMap = null
          if (this.filterAvailableOnly) {
            let candidates = batch
            if (this.filterHideFollowed) candidates = candidates.filter(r => !this.isFollowed(r))
            if (this.filterHideMangaDexFollowed) candidates = candidates.filter(r => !this.isMangaDexFollowed(r))
            availMap = {}
            if (candidates.length > 0) {
              if (lang === null) lang = await this.fetchPreferredLanguage()
              try {
                const check = await this.$http.post('/api/v1/plugins/mangadex-metadata/downloadable-check', {
                  language: lang,
                  ids: candidates.map(r => r.externalId),
                })
                availMap = check.data || {}
              } catch (e) {
                this.showError('Downloadable-check failed: ' + (e?.response?.data?.message || e.message))
              }
            }
          }

          let consumed = 0
          for (let i = 0; i < batch.length; i++) {
            consumed = i + 1
            const r = batch[i]
            if (this.filterHideFollowed && this.isFollowed(r)) { this.lastSearchSkippedFollow++; continue }
            if (this.filterHideMangaDexFollowed && this.isMangaDexFollowed(r)) { this.lastSearchSkippedMangaDexFollow++; continue }
            if (this.filterAvailableOnly && availMap && availMap[r.externalId] !== true) { this.lastSearchSkippedAvailable++; continue }
            collected.push(r)
            if (collected.length >= this.searchPageSize) break
          }
          rawCursor += consumed
          nextStart = rawCursor
          if (rawCursor >= this.searchTotal) { exhausted = true; break }
        }

        this.searchResults = collected
        this.searchDone = true
        if (reduces) {
          const more = !exhausted && nextStart < Math.min(this.searchTotal || nextStart, MAX_OFFSET)
          if (more) {
            this.$set(this.searchPageRawStart, this.searchPage + 1, nextStart)
            this.searchKnownPages = Math.max(this.searchKnownPages, this.searchPage + 1)
          } else {
            this.searchKnownPages = Math.max(this.searchKnownPages, this.searchPage)
          }
        }
      } catch (e) {
        const msg = e?.response?.data?.message || e.message
        this.showError('Search failed: ' + msg + ' (is the MangaDex Metadata plugin enabled?)')
      } finally {
        this.searchLoading = false
      }
    },
    onSearchPageChange(page) {
      this.searchPage = page
      this.searchMangaDex(false)
    },
    async downloadFromSearch(manga) {
      if (!this.searchLibraryId) { this.showError('Select a target library first'); return }
      const k = manga.externalId + ':dl'
      this.$set(this.searchBusy, k, true)
      try {
        await this.$http.post('/api/v1/downloads', {
          sourceUrl: `https://mangadex.org/title/${manga.externalId}`,
          libraryId: this.searchLibraryId,
          priority: 5,
        })
        this.$set(this.searchAction, manga.externalId, 'downloaded')
        this.showSuccess(`Queued: ${manga.title}`)
      } catch (e) {
        this.$set(this.searchAction, manga.externalId, 'error')
        this.showError('Failed to queue: ' + (e?.response?.data?.message || e.message))
      } finally {
        this.$set(this.searchBusy, k, false)
      }
    },
    selectLibrary(index) {
      this.selectedLibraryIndex = index
      this.loadFollows()
      this.loadSchedule()
    },
    async loadFollows() {
      this.selectedFollowIds = []
      if (!this.selectedLibrary) { this.followItems = []; return }
      try {
        this.followItems = await this.$komgaFollows.getAll(this.selectedLibrary.id)
      } catch (error) {
        this.followItems = []
        this.showError('Failed to load follows: ' + error.message)
      }
    },
    async removeSelectedFollows() {
      if (!this.selectedLibrary || this.selectedFollowIds.length === 0) return
      this.followBusy = true
      try {
        await this.$komgaFollows.removeBatch(this.selectedLibrary.id, this.selectedFollowIds)
        const removed = new Set(this.selectedFollowIds)
        this.followItems = this.followItems.filter(x => !removed.has(x.id))
        this.selectedFollowIds = []
        this.refreshFollowedUuids()
      } catch (error) {
        this.showError('Failed to delete follows: ' + error.message)
      } finally {
        this.followBusy = false
      }
    },
    openAddFollowDialog() {
      this.followForm = { url: '', title: '' }
      this.followBatchText = ''
      this.addFollowDialog = true
    },
    async submitAddFollow() {
      if (!this.selectedLibrary || !this.followForm.url.trim()) return
      this.followBusy = true
      try {
        await this.$komgaFollows.add(this.selectedLibrary.id, {
          url: this.followForm.url.trim(),
          title: this.followForm.title.trim() || undefined,
        })
        this.addFollowDialog = false
        await this.loadFollows()
        this.refreshFollowedUuids()
      } catch (error) {
        this.showError('Failed to add follow: ' + (error.response?.data?.message || error.message))
      } finally {
        this.followBusy = false
      }
    },
    async submitAddFollowsBatch() {
      if (!this.selectedLibrary) return
      const urls = this.followBatchText.split(/\r?\n/).map(l => l.trim()).filter(l => l)
      if (urls.length === 0) return
      this.followBusy = true
      try {
        const result = await this.$komgaFollows.addBatch(this.selectedLibrary.id, urls)
        this.followBatchText = ''
        this.addFollowDialog = false
        await this.loadFollows()
        this.refreshFollowedUuids()
        this.showSuccess(`Imported ${result.added} follows (${result.skipped} skipped)`)
      } catch (error) {
        this.showError('Failed to import follows: ' + (error.response?.data?.message || error.message))
      } finally {
        this.followBusy = false
      }
    },
    openEditFollowDialog(f) {
      this.editFollowItem = f
      this.editFollowTitle = f.title || ''
      this.editFollowDialog = true
    },
    async submitEditFollow() {
      if (!this.editFollowItem) return
      this.followBusy = true
      try {
        const updated = await this.$komgaFollows.update(this.selectedLibrary.id, this.editFollowItem.id, {
          title: this.editFollowTitle.trim() || null,
        })
        this.editFollowItem.title = updated.title
        this.editFollowDialog = false
      } catch (error) {
        this.showError('Failed to update name: ' + (error.response?.data?.message || error.message))
      } finally {
        this.followBusy = false
      }
    },
    async toggleFollowEnabled(f) {
      try {
        await this.$komgaFollows.update(this.selectedLibrary.id, f.id, {enabled: f.enabled})
      } catch (error) {
        f.enabled = !f.enabled
        this.showError('Failed to update follow: ' + error.message)
      }
    },
    async removeFollow(f) {
      try {
        await this.$komgaFollows.remove(this.selectedLibrary.id, f.id)
        this.followItems = this.followItems.filter(x => x.id !== f.id)
        this.refreshFollowedUuids()
      } catch (error) {
        this.showError('Failed to remove follow: ' + error.message)
      }
    },
    async checkFollowsNow() {
      if (!this.selectedLibrary) return
      this.checkingNow = true
      try {
        const result = await this.$komgaFollows.checkNow(this.selectedLibrary.id)
        this.showSuccess(`Check started — ${result.queued} new chapter(s) queued.`)
      } catch (error) {
        this.showError('Failed to trigger check: ' + error.message)
      } finally {
        this.checkingNow = false
      }
    },
    async loadSchedule() {
      if (!this.selectedLibrary) return
      try {
        const dto = await this.$komgaFollows.getSchedule(this.selectedLibrary.id)
        this.schedule = {
          enabled: dto.enabled,
          scheduleMode: dto.scheduleMode || 'interval',
          intervalHours: dto.intervalHours || 24,
          checkTime: dto.checkTime || '03:00',
        }
      } catch (error) {
        this.schedule = {enabled: false, scheduleMode: 'interval', intervalHours: 24, checkTime: '03:00'}
      }
    },
    async saveSchedule() {
      if (!this.selectedLibrary) return
      this.savingSchedule = true
      try {
        await this.$komgaFollows.updateSchedule(this.selectedLibrary.id, {
          enabled: this.schedule.enabled,
          scheduleMode: this.schedule.scheduleMode,
          intervalHours: this.schedule.intervalHours,
          checkTime: this.schedule.scheduleMode === 'fixed_time' ? this.schedule.checkTime : null,
        })
        this.showSuccess('Schedule saved')
      } catch (error) {
        this.showError('Failed to save schedule: ' + error.message)
      } finally {
        this.savingSchedule = false
      }
    },
    async syncToMangaDex() {
      if (!this.selectedLibrary) return
      this.syncingToMangaDex = true
      try {
        const response = await this.$http.post(`/api/v1/downloads/follows/${this.selectedLibrary.id}/sync-to-mangadex`)
        this.showSuccess(response.data.message || `Synced ${response.data.followed}/${response.data.total} manga to MangaDex`)
      } catch (error) {
        const msg = error.response?.data?.error || error.message
        this.showError('Sync failed: ' + msg)
      } finally {
        this.syncingToMangaDex = false
      }
    },
    async loadMangaDexPluginStatus() {
      try {
        const response = await this.$http.get('/api/v1/plugins/mangadex-subscription')
        this.mangaDexPluginEnabled = response.data.enabled
      } catch (_) {
        this.mangaDexPluginEnabled = false
      }
    },
    async addDownload() {
      if (!this.newDownload.libraryId) {
        this.showError('Please select a target library')
        return
      }
      this.adding = true
      try {
        await this.$http.post('/api/v1/downloads', this.newDownload)
        this.showSuccess('Download added to queue')
        this.newDownloadDialog = false
        this.newDownload = { sourceUrl: '', libraryId: null, priority: 5 }
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to add download: ' + error.message)
      } finally {
        this.adding = false
      }
    },
    async pauseDownload(download) {
      try {
        await this.$http.post(`/api/v1/downloads/${download.id}/action`, { action: 'pause' })
        this.showSuccess('Download paused')
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to pause download: ' + error.message)
      }
    },
    async resumeDownload(download) {
      try {
        await this.$http.post(`/api/v1/downloads/${download.id}/action`, { action: 'resume' })
        this.showSuccess('Download resumed')
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to resume download: ' + error.message)
      }
    },
    async cancelDownload(download) {
      try {
        await this.$http.post(`/api/v1/downloads/${download.id}/action`, { action: 'cancel' })
        this.showSuccess('Download cancelled')
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to cancel download: ' + error.message)
      }
    },
    async deleteDownload(download) {
      try {
        await this.$http.delete(`/api/v1/downloads/${download.id}`)
        this.showSuccess('Download deleted from queue')
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to delete download: ' + error.message)
      }
    },
    handleAction({ download, action }) {
      switch (action) {
        case 'pause':
          this.pauseDownload(download)
          break
        case 'resume':
          this.resumeDownload(download)
          break
        case 'cancel':
          this.cancelDownload(download)
          break
        case 'retry':
          this.resumeDownload(download)
          break
        case 'delete':
          this.deleteDownload(download)
          break
      }
    },
    async clearByStatus(status) {
      try {
        const response = await this.$http.delete(`/api/v1/downloads/clear/${status}`)
        this.showSuccess(response.data.message || `Cleared ${status} downloads`)
        await this.loadDownloads()
      } catch (error) {
        this.showError('Failed to clear downloads: ' + error.message)
      }
    },
    showSuccess(message) {
      this.snackbarText = message
      this.snackbarColor = 'success'
      this.snackbar = true
    },
    showError(message) {
      this.snackbarText = message
      this.snackbarColor = 'error'
      this.snackbar = true
    },
    // SSE event handlers (using Komga's existing SSE infrastructure)
    setupSseListeners() {
      this.$eventHub.$on(DOWNLOAD_STARTED, this.onDownloadStarted)
      this.$eventHub.$on(DOWNLOAD_PROGRESS, this.onDownloadProgress)
      this.$eventHub.$on(DOWNLOAD_COMPLETED, this.onDownloadCompleted)
      this.$eventHub.$on(DOWNLOAD_FAILED, this.onDownloadFailed)
    },
    removeSseListeners() {
      this.$eventHub.$off(DOWNLOAD_STARTED, this.onDownloadStarted)
      this.$eventHub.$off(DOWNLOAD_PROGRESS, this.onDownloadProgress)
      this.$eventHub.$off(DOWNLOAD_COMPLETED, this.onDownloadCompleted)
      this.$eventHub.$off(DOWNLOAD_FAILED, this.onDownloadFailed)
    },
    onDownloadStarted(data) {
      this.showSuccess(`Download started: ${data.title || data.sourceUrl}`)
      this.updateDownloadFromSse(data)
    },
    onDownloadProgress(data) {
      this.updateDownloadFromSse(data)
    },
    onDownloadCompleted(data) {
      this.showSuccess(`Download completed: ${data.title}`)
      this.updateDownloadFromSse(data)
    },
    onDownloadFailed(data) {
      this.showError(`Download failed: ${data.title} - ${data.errorMessage}`)
      this.updateDownloadFromSse(data)
    },
    updateDownloadFromSse(data) {
      if (!data.downloadId) return

      const index = this.downloads.findIndex(d => d.id === data.downloadId)
      if (index !== -1) {
        // Update existing download reactively
        this.$set(this.downloads, index, {
          ...this.downloads[index],
          status: data.status,
          progressPercent: data.progressPercent ?? this.downloads[index].progressPercent,
          currentChapter: data.currentChapter ?? this.downloads[index].currentChapter,
          totalChapters: data.totalChapters ?? this.downloads[index].totalChapters,
          errorMessage: data.errorMessage ?? this.downloads[index].errorMessage,
        })
      } else {
        // New download - reload full list
        this.loadDownloads()
      }
    },
    // Tachiyomi Import
    async importTachiyomi() {
      if (!this.tachiyomiFile || !this.tachiyomiLibraryId) {
        this.showError('Please select a backup file and target library')
        return
      }
      this.importingTachiyomi = true
      this.tachiyomiResult = null
      try {
        const result = await this.$komgaImport.importTachiyomi(this.tachiyomiFile, this.tachiyomiLibraryId)
        this.tachiyomiResult = result
        if (result.importedCount > 0) {
          this.showSuccess(`Imported ${result.importedCount} manga from Tachiyomi backup`)
          // Refresh the follow list if we're on the config tab viewing the same library
          if (this.selectedLibrary && this.selectedLibrary.id === this.tachiyomiLibraryId) {
            this.loadFollows()
          }
          this.refreshFollowedUuids()
        } else if (result.skippedCount > 0) {
          this.showSuccess('All manga already exist in the follow list')
        } else {
          this.showError('No MangaDex manga found in backup')
        }
      } catch (error) {
        this.showError(error.message || 'Failed to import Tachiyomi backup')
      } finally {
        this.importingTachiyomi = false
      }
    },
  },
}
</script>
