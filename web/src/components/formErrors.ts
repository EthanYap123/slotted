import { ApiError } from '../api/errors'

/**
 * Splits an API error into messages for the named fields and one for the top of the form. Field
 * names are the API's (for example `capacity`); `byCode` sends whole errors, such as `NAME_TAKEN`,
 * to a field.
 */
export function formErrors<F extends string>(
  error: unknown,
  fields: readonly F[],
  byCode: Partial<Record<string, F>> = {},
): { fields: Partial<Record<F, string>>; general: unknown } {
  if (!(error instanceof ApiError)) {
    return { fields: {}, general: error ?? null }
  }
  const result: Partial<Record<F, string>> = {}
  for (const field of fields) {
    const message = error.fieldErrors[field]
    if (message) {
      result[field] = message
    }
  }
  const codeField = byCode[error.code]
  if (codeField) {
    result[codeField] = error.message
  }
  return { fields: result, general: Object.keys(result).length > 0 ? null : error }
}
