import { useSyncExternalStore } from 'react'
import { ApiError, type ErrorCode, type Problem } from './errors'
import { session, type AuthResponse } from './session'

const apiUrl = import.meta.env.VITE_API_URL.replace(/\/$/, '')
const BASE = `${apiUrl}/api/v1`

/** After this long a request is probably waiting for the free-tier server to wake up. */
const WAKING_AFTER_MS = 3000

interface RequestOptions {
  body?: unknown
  signal?: AbortSignal
  /** Set false for the sign-in endpoints, which do not use the access token. */
  auth?: boolean
}

export async function request<T>(
  method: string,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const useAuth = options.auth !== false
  if (useAuth && !session.accessToken() && session.refreshToken()) {
    await refreshSession()
  }
  let response = await send(method, path, options, useAuth)
  if (response.status === 401 && useAuth && session.refreshToken()) {
    await refreshSession()
    response = await send(method, path, options, useAuth)
  }
  if (response.status === 401 && useAuth) {
    session.signedOut()
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T)
}

async function send(
  method: string,
  path: string,
  options: RequestOptions,
  useAuth: boolean,
): Promise<Response> {
  const headers: Record<string, string> = {}
  const token = session.accessToken()
  if (useAuth && token) {
    headers.Authorization = `Bearer ${token}`
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const stopWatching = watchForSlowServer()
  try {
    return await fetch(`${BASE}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(0, {
      code: 'NETWORK',
      detail: 'The server could not be reached. Check your connection and try again.',
    })
  } finally {
    stopWatching()
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    return new ApiError(response.status, (await response.json()) as Problem)
  } catch {
    const code: ErrorCode = response.status >= 500 ? 'INTERNAL_ERROR' : 'VALIDATION_FAILED'
    return new ApiError(response.status, { code, detail: 'Something went wrong.' })
  }
}

let refreshInFlight: Promise<void> | null = null

/**
 * Swaps the refresh token for new tokens. A refresh token works only once and reusing one ends the
 * session (ADR-0002), so only one refresh may run at a time: callers in this tab share one
 * promise, and the Web Locks API makes other tabs wait, then use the token this tab stored.
 */
export function refreshSession(): Promise<void> {
  refreshInFlight ??= withCrossTabLock(exchangeRefreshToken).finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

function withCrossTabLock(work: () => Promise<void>): Promise<void> {
  // navigator.locks is missing on non-secure origins and in older browsers, whatever the types say.
  const locks = navigator.locks as LockManager | undefined
  return locks ? locks.request('slotted.refresh', work) : work()
}

async function exchangeRefreshToken(): Promise<void> {
  const refreshToken = session.refreshToken()
  if (!refreshToken) {
    session.signedOut()
    throw new ApiError(401, { code: 'UNAUTHENTICATED', detail: 'Sign in to continue.' })
  }
  try {
    session.signedIn(
      await request<AuthResponse>('POST', '/auth/refresh', {
        body: { refreshToken },
        auth: false,
      }),
    )
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      session.signedOut()
    }
    throw error
  }
}

/** Restores the session after a page load, if a refresh token was saved. */
export async function restoreSession(): Promise<void> {
  if (!session.refreshToken()) {
    return
  }
  session.restoring()
  try {
    await refreshSession()
  } catch (error) {
    // A 401 has already signed the user out; anything else may be temporary, so keep the token.
    if (!(error instanceof ApiError && error.status === 401)) {
      session.offline()
    }
  }
}

let slowRequests = 0
const slowListeners = new Set<() => void>()

function watchForSlowServer(): () => void {
  let counted = false
  const timer = setTimeout(() => {
    counted = true
    updateSlowRequests(1)
  }, WAKING_AFTER_MS)
  return () => {
    clearTimeout(timer)
    if (counted) {
      updateSlowRequests(-1)
    }
  }
}

function updateSlowRequests(change: number) {
  slowRequests += change
  slowListeners.forEach((listener) => {
    listener()
  })
}

/** True while a request has been waiting long enough that the server is probably asleep. */
export function useServerWaking(): boolean {
  return useSyncExternalStore(
    (listener) => {
      slowListeners.add(listener)
      return () => {
        slowListeners.delete(listener)
      }
    },
    () => slowRequests > 0,
  )
}
