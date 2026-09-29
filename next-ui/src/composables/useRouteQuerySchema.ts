import * as v from 'valibot'
import { useRouteQuery } from '@vueuse/router'
import { syncRef } from '@vueuse/core'
import { useAppStore } from '@/stores/app'

/**
 * Reactive `route.query` with schema validation.
 * If value is not valid, the `schema` default values are used.
 *
 * @param queryName the query parameter name
 * @param schema valibot schema to validate against
 * @param updateQueryFn custom function to update the query param. The default function compares the JSON.stringify'ed value against the schema's defaults.
 * @param persistKey when set, the value is also persisted in the app store under this key, so it
 *        survives navigating away and back to a view (an explicit URL query still takes precedence).
 */
export function useRouteQuerySchema<T extends v.GenericSchema>(
  queryName: string,
  schema: T,
  updateQueryFn?: (data: v.InferOutput<T>) => string | undefined,
  persistKey?: string,
) {
  const queryString = useRouteQuery(queryName, '{}')

  const defaults = v.getDefaults(schema)

  function getInitialValue(stringValue: string) {
    try {
      return v.parse(schema, JSON.parse(stringValue))
    } catch {
      return defaults
    }
  }

  function defaultUpdateQueryFn(data: v.InferOutput<T>): string | undefined {
    if (JSON.stringify(data) !== JSON.stringify(defaults)) return JSON.stringify(data)
    return undefined
  }

  const updateFn = updateQueryFn ?? defaultUpdateQueryFn

  const appStore = persistKey ? useAppStore() : undefined

  // Initial value: an explicit URL query wins (shareable/deep-link); otherwise the per-view persisted
  // value (survives navigating away and back); otherwise the schema defaults.
  const urlString = String(queryString.value)
  const persistedString = persistKey ? (appStore!.filterQuery[persistKey] ?? '{}') : '{}'
  const data = ref(getInitialValue(urlString !== '{}' ? urlString : persistedString))

  // data → URL. If the value was restored from the store (URL empty), this also writes it to the URL.
  syncRef(data, queryString, {
    direction: 'ltr',
    deep: true,
    transform: {
      ltr: (left) => updateFn(left),
    },
  })

  // data → store: keep the per-view persisted value in sync, removing it when back to defaults.
  if (persistKey && appStore) {
    watch(
      data,
      (value) => {
        const str = updateFn(value)
        if (str === undefined) delete appStore.filterQuery[persistKey]
        else appStore.filterQuery[persistKey] = str
      },
      { deep: true, immediate: true },
    )
  }

  return {
    data: data as Ref<v.InferOutput<T>>,
  }
}
