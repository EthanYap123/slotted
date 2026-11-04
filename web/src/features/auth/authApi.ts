import { request } from '../../api/client'
import { session, type AuthResponse } from '../../api/session'

export async function signIn(email: string, password: string): Promise<void> {
  session.signedIn(
    await request<AuthResponse>('POST', '/auth/login', {
      body: { email, password },
      auth: false,
    }),
  )
}

export async function register(email: string, password: string, fullName: string): Promise<void> {
  session.signedIn(
    await request<AuthResponse>('POST', '/auth/register', {
      body: { email, password, fullName },
      auth: false,
    }),
  )
}

/** Ends the session on the server if it can be reached, and on this device regardless. */
export async function signOut(): Promise<void> {
  const refreshToken = session.refreshToken()
  try {
    if (refreshToken) {
      await request('POST', '/auth/logout', { body: { refreshToken }, auth: false })
    }
  } catch {
    // Signing out here must still work when the server is unreachable.
  } finally {
    session.signedOut()
  }
}
