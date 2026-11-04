import { describe, expect, it, vi } from 'vitest'
import { authResponse, fakeApi, problem } from '../test/fakeApi'
import { refreshSession, request, restoreSession } from './client'
import { ApiError, errorMessage } from './errors'
import { session } from './session'

function signedInWith(accessToken: string, refreshToken: string) {
  session.signedIn(authResponse(accessToken, refreshToken))
}

describe('request', () => {
  it('sends the access token and JSON body', async () => {
    signedInWith('access-1', 'refresh-1')
    const api = fakeApi({ 'POST /things': { status: 201, body: { id: 1 } } })

    await expect(request('POST', '/things', { body: { a: 1 } })).resolves.toEqual({ id: 1 })

    expect(api.calls[0]).toMatchObject({ token: 'access-1', body: { a: 1 } })
  })

  it('turns a problem response into an ApiError with its code and field errors', async () => {
    fakeApi({
      'POST /auth/login': problem(400, 'VALIDATION_FAILED', 'Check the fields.', [
        { field: 'email', message: 'must be an email' },
      ]),
    })

    const error: unknown = await request('POST', '/auth/login', { auth: false }).catch(
      (e: unknown) => e,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
      fieldErrors: { email: 'must be an email' },
    })
  })

  it('reports an unreachable server as a network error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(request('GET', '/me', { auth: false })).rejects.toMatchObject({ code: 'NETWORK' })
  })

  it('refreshes after a 401 and retries with the new token', async () => {
    signedInWith('expired', 'refresh-1')
    const api = fakeApi({
      'GET /me': [problem(401, 'UNAUTHENTICATED', 'Sign in'), { body: { ok: true } }],
      'POST /auth/refresh': { body: authResponse('access-2', 'refresh-2') },
    })

    await expect(request('GET', '/me')).resolves.toEqual({ ok: true })

    expect(api.calls.map((c) => c.token)).toEqual(['expired', null, 'access-2'])
    expect(api.calls[1]?.body).toEqual({ refreshToken: 'refresh-1' })
    expect(session.refreshToken()).toBe('refresh-2')
  })

  it('shares one refresh between requests that fail together', async () => {
    signedInWith('expired', 'refresh-1')
    const api = fakeApi({
      'GET /a': [problem(401, 'UNAUTHENTICATED', 'Sign in'), { body: 'a' }],
      'GET /b': [problem(401, 'UNAUTHENTICATED', 'Sign in'), { body: 'b' }],
      'POST /auth/refresh': { body: authResponse('access-2', 'refresh-2') },
    })

    await Promise.all([request('GET', '/a'), request('GET', '/b')])

    expect(api.calls.filter((c) => c.path === '/auth/refresh')).toHaveLength(1)
  })

  it('signs out when the refresh token is rejected', async () => {
    signedInWith('expired', 'refresh-1')
    fakeApi({
      'GET /me': problem(401, 'UNAUTHENTICATED', 'Sign in'),
      'POST /auth/refresh': problem(401, 'REFRESH_TOKEN_INVALID', 'Session expired'),
    })

    await expect(request('GET', '/me')).rejects.toMatchObject({ code: 'REFRESH_TOKEN_INVALID' })

    expect(session.current().status).toBe('signedOut')
    expect(session.refreshToken()).toBeNull()
  })
})

describe('restoreSession', () => {
  it('signs in from a saved refresh token', async () => {
    localStorage.setItem('slotted.refreshToken', 'saved')
    fakeApi({ 'POST /auth/refresh': { body: authResponse() } })

    await restoreSession()

    expect(session.current().status).toBe('signedIn')
    expect(session.accessToken()).toBe('access-1')
  })

  it('keeps the token and reports offline when the server cannot be reached', async () => {
    localStorage.setItem('slotted.refreshToken', 'saved')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await restoreSession()

    expect(session.current().status).toBe('offline')
    expect(session.refreshToken()).toBe('saved')
  })

  it('does nothing without a saved token', async () => {
    const api = fakeApi({})

    await restoreSession()

    expect(api.calls).toHaveLength(0)
    expect(session.current().status).toBe('signedOut')
  })
})

describe('refreshSession', () => {
  it('signs out if there is no token to exchange', async () => {
    await expect(refreshSession()).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
    expect(session.current().status).toBe('signedOut')
  })
})

describe('errorMessage', () => {
  it('quotes the request ID for server errors and hides unknown errors', () => {
    expect(
      errorMessage(new ApiError(500, { code: 'INTERNAL_ERROR', detail: 'Oops.', requestId: 'r1' })),
    ).toBe('Oops. (Request ID: r1)')
    expect(errorMessage(new Error('stack details'))).toBe('Something went wrong. Please try again.')
  })
})
