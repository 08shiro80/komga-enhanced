import {
  useAppReleases,
  useForkReleases,
  useGalleryDlForkUpdates,
} from '@/colada/app-releases'
import { useActuatorInfo } from '@/colada/actuator-info'

/**
 * Composable that returns more detailed app releases information.
 */
export function useAppReleasesEnriched() {
  const { data, isLoading: isLoadingReleases, ...restReleases } = useAppReleases()
  const { data: forkData } = useForkReleases()
  const { data: galleryDlData } = useGalleryDlForkUpdates()
  const { buildVersion, isLoading: isLoadingActuator } = useActuatorInfo()

  const isLoading = computed(() => isLoadingReleases.value || isLoadingActuator.value)

  const latestRelease = computed(() => data.value?.find((x) => x.latest))

  const isLatestVersion = computed(() => {
    if (buildVersion.value && data.value) {
      // buildVersion is the full fork version (e.g. "1.27.1-fork-0.1.6"); base releases
      // report only the upstream part (e.g. "1.27.1"). Compare the base component only.
      const base = (buildVersion.value.split('-fork')[0] ?? '').replace(/^v/, '')
      return data.value.some((x) => x.latest && x.version.replace(/^v/, '') === base)
    }
    return undefined
  })

  // Fork releases (08shiro80/komga-enhanced) — the running build version is the fork version.
  const forkReleases = computed(() => forkData.value)
  const latestForkRelease = computed(() => forkData.value?.find((x) => x.latest))
  const isForkLatestVersion = computed(() => {
    if (buildVersion.value && forkData.value)
      return forkData.value.some((x) => x.latest && x.version == buildVersion.value)
    else return undefined
  })

  // gallery-dl fork: behindCount > 0 means updates are available; -1 means installed SHA unknown.
  const galleryDlUpdates = computed(() => galleryDlData.value)
  const galleryDlBehindCount = computed(() => galleryDlData.value?.behindCount ?? 0)
  const isGalleryDlUpToDate = computed(() => {
    const behind = galleryDlData.value?.behindCount
    if (behind === undefined) return undefined
    return behind <= 0
  })

  const anyUpdateAvailable = computed(
    () =>
      isLatestVersion.value === false ||
      isForkLatestVersion.value === false ||
      isGalleryDlUpToDate.value === false,
  )

  return {
    releases: data,
    buildVersion,
    latestRelease,
    isLatestVersion,
    forkReleases,
    latestForkRelease,
    isForkLatestVersion,
    galleryDlUpdates,
    galleryDlBehindCount,
    isGalleryDlUpToDate,
    anyUpdateAvailable,
    isLoading,
    ...restReleases,
  }
}
