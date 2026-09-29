import { defineMutation, useMutation } from '@pinia/colada'
import { komgaImportTachiyomi, type TachiyomiImportResultDto } from '@/generated/openapi'

export const useImportTachiyomi = defineMutation(() =>
  useMutation({
    mutation: ({ file, libraryId }: { file: File; libraryId: string }): Promise<TachiyomiImportResultDto> =>
      komgaImportTachiyomi({ body: { file }, query: { libraryId } }),
  }),
)
