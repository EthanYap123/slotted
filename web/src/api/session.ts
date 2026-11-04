import { useSyncExternalStore } from 'react'
import type { components } from './schema'

export type CurrentUser = components['schemas']['CurrentUser']
export type AuthResponse = components['schemas']['AuthResponse']

/**
 * - `restoring`: a refresh token exists and is being exchanged
 * - `offline`: a refresh token exists but the server could not be reached
 */
export type SessionState =
  | { status: 'restoring'; user: null }
  | { status: 'offline'; user: null }
  | { status: 'signedOut'; user: null }
  | { status: 'signedIn'; user: CurrentUser }

const REFRESH_TOKEN_KEY = 'slotted.refreshToken'

/** Kept in memory only, so a script injected later cannot read a stored token (ADR-0002). */
let accessToken: string | null = null

function readRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_TOKEN_KEY)
  } catch {
    return null
  }
}

function initialState(): SessionState {
  return readRefreshToken()
    ? { status: 'restoring', user: null }
    : { status: 'signedOut', user: null }
}

let state = initialState()
const listeners = new Set<() => void>()

function setState(next: SessionState) {
  state = next
  listeners.forEach((listener) => {
    listener()
  })
}

export const session = {
  accessToken: () => accessToken,
  refreshToken: readRefreshToken,
  current: () => state,

  /** Stores the tokens from a sign-in or refresh and marks the user as signed in. */
  signedIn(auth: AuthResponse) {
    accessToken = auth.accessToken
    try {
      localStorage.setItem(REFRESH_TOKEN_KEY, auth.refreshToken)
    } catch {
      // Without storage the session simply ends when the page is closed.
    }
    setState({ status: 'signedIn', user: auth.user })
  },

  signedOut() {
    accessToken = null
    try {
      localStorage.removeItem(REFRESH_TOKEN_KEY)
    } catch {
      // Nothing was stored.
    }
    setState({ status: 'signedOut', user: null })
  },

  restoring() {
    setState({ status: 'restoring', user: null })
  },

  offline() {
    setState({ status: 'offline', user: null })
  },

  /** Re-reads storage, for tests. */
  reset() {
    accessToken = null
    setState(initialState())
  },
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function currentState() {
  return state
}

export function useSession(): SessionState {
  return useSyncExternalStore(subscribe, currentState)
}
