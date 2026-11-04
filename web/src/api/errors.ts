import type { components } from './schema'

export type ErrorCode = components['schemas']['ErrorCode']
export type Problem = components['schemas']['Problem']

/** A failed API call, carrying the stable error code from the problem response. */
export class ApiError extends Error {
  readonly status: number
  readonly code: ErrorCode | 'NETWORK'
  readonly requestId: string | undefined
  readonly fieldErrors: Readonly<Record<string, string>>

  constructor(
    status: number,
    problem: Partial<Omit<Problem, 'code'>> & { code: ErrorCode | 'NETWORK' },
  ) {
    super(problem.detail ?? problem.title ?? 'Something went wrong.')
    this.name = 'ApiError'
    this.status = status
    this.code = problem.code
    this.requestId = problem.requestId
    this.fieldErrors = Object.fromEntries(
      (problem.errors ?? []).map((error) => [error.field, error.message]),
    )
  }
}

/** The message to show a person for any error thrown while calling the API. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.code === 'INTERNAL_ERROR' && error.requestId
      ? `${error.message} (Request ID: ${error.requestId})`
      : error.message
  }
  return 'Something went wrong. Please try again.'
}
