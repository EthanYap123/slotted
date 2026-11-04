import { vi } from 'vitest'
import type { AuthResponse, CurrentUser } from '../api/session'

/** One recorded call to the API. */
export interface ApiCall {
  method: string
  path: string
  body: unknown
  token: string | null
}

type Reply = { status?: number; body?: unknown } | (() => { status?: number; body?: unknown })

/**
 * Replaces fetch with a small routing table, keyed like "POST /auth/login" (query strings are part
 * of the key when a route includes one). An array of replies is used one at a time, repeating the
 * last. Anything not listed fails the test with a clear message.
 */
export function fakeApi(routes: Record<string, Reply | Reply[]>) {
  const calls: ApiCall[] = []
  const used = new Map<string, number>()

  const fetchMock = vi.fn((input: string, init?: RequestInit) => {
    const url = new URL(input)
    const method = init?.method ?? 'GET'
    const path = url.pathname.replace('/api/v1', '') + url.search
    const headers = (init?.headers ?? {}) as Record<string, string>
    calls.push({
      method,
      path,
      body: typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined,
      token: headers.Authorization?.replace('Bearer ', '') ?? null,
    })

    const key = `${method} ${path}`
    const route = routes[key] ?? routes[`${method} ${url.pathname.replace('/api/v1', '')}`]
    if (route === undefined) {
      return Promise.reject(new Error(`Unexpected API call: ${key}`))
    }
    const index = used.get(key) ?? 0
    used.set(key, index + 1)
    const chosen = Array.isArray(route) ? route[Math.min(index, route.length - 1)] : route
    const reply = typeof chosen === 'function' ? chosen() : chosen
    const status = reply?.status ?? 200
    return Promise.resolve(
      status === 204
        ? new Response(null, { status })
        : Response.json(reply?.body ?? {}, { status }),
    )
  })
  vi.stubGlobal('fetch', fetchMock)
  return { calls }
}

export function problem(status: number, code: string, detail: string, errors?: unknown[]) {
  return { status, body: { status, code, title: detail, detail, type: 'about:blank', errors } }
}

export const student: CurrentUser = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'ada@example.com',
  fullName: 'Ada Tan',
  role: 'STUDENT',
  status: 'ACTIVE',
  suspendedUntil: null,
  createdAt: '2026-11-01T10:00:00+08:00',
  managedFacilityIds: [],
}

export function authResponse(accessToken = 'access-1', refreshToken = 'refresh-1'): AuthResponse {
  return {
    accessToken,
    accessTokenExpiresAt: '2026-11-04T10:15:00+08:00',
    refreshToken,
    user: student,
  }
}
