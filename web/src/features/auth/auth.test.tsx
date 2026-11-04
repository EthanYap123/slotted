import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { session } from '../../api/session'
import { authResponse, fakeApi, problem } from '../../test/fakeApi'
import { renderApp } from '../../test/render'

async function fillSignIn(email: string, password: string) {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('signing in', () => {
  it('sends people who are signed out to the sign-in page', () => {
    renderApp('/')

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('signs in, stores only the refresh token and opens the app', async () => {
    const api = fakeApi({ 'POST /auth/login': { body: authResponse() } })
    renderApp('/')

    await fillSignIn('ada@example.com', 'a good long password')

    expect(await screen.findByRole('heading', { name: 'Welcome, Ada Tan' })).toBeInTheDocument()
    expect(api.calls[0]?.body).toEqual({
      email: 'ada@example.com',
      password: 'a good long password',
    })
    expect(localStorage.getItem('slotted.refreshToken')).toBe('refresh-1')
    expect(Object.keys(localStorage).map((key) => localStorage.getItem(key))).not.toContain(
      'access-1',
    )
  })

  it('shows the error from the API when the details are wrong', async () => {
    fakeApi({
      'POST /auth/login': problem(401, 'INVALID_CREDENTIALS', 'Email or password is incorrect'),
    })
    renderApp('/login')

    await fillSignIn('ada@example.com', 'wrong password')

    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect')
    expect(session.current().status).toBe('signedOut')
  })

  it('signs out on the server and on this device', async () => {
    session.signedIn(authResponse())
    const api = fakeApi({ 'POST /auth/logout': { status: 204 } })
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(api.calls[0]).toMatchObject({
      path: '/auth/logout',
      body: { refreshToken: 'refresh-1' },
    })
    expect(localStorage.getItem('slotted.refreshToken')).toBeNull()
  })

  it('still signs out when the server cannot be reached', async () => {
    session.signedIn(authResponse())
    fakeApi({})
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() => {
      expect(session.current().status).toBe('signedOut')
    })
  })
})

describe('registering', () => {
  async function fillRegister(password = 'a good long password') {
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Full name'), 'Ada Tan')
    await user.type(screen.getByLabelText('Email'), 'ada@example.com')
    await user.type(screen.getByLabelText('Password'), password)
    await user.click(screen.getByRole('button', { name: 'Create account' }))
  }

  it('creates the account and signs in', async () => {
    const api = fakeApi({ 'POST /auth/register': { status: 201, body: authResponse() } })
    renderApp('/register')

    await fillRegister()

    expect(await screen.findByRole('heading', { name: 'Welcome, Ada Tan' })).toBeInTheDocument()
    expect(api.calls[0]?.body).toEqual({
      fullName: 'Ada Tan',
      email: 'ada@example.com',
      password: 'a good long password',
    })
  })

  it('shows a weak password error beside the password', async () => {
    fakeApi({
      'POST /auth/register': problem(400, 'WEAK_PASSWORD', 'This password is too common.'),
    })
    renderApp('/register')

    await fillRegister('password123')

    expect(await screen.findByText('This password is too common.')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Password/)).toHaveAttribute('aria-invalid', 'true')
  })

  it('shows a taken email beside the email', async () => {
    fakeApi({
      'POST /auth/register': problem(409, 'EMAIL_TAKEN', 'An account with this email exists'),
    })
    renderApp('/register')

    await fillRegister()

    expect(await screen.findByText('An account with this email exists')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Email/)).toHaveAttribute('aria-invalid', 'true')
  })

  it('shows other errors at the top of the form', async () => {
    fakeApi({ 'POST /auth/register': problem(500, 'INTERNAL_ERROR', 'Something went wrong.') })
    renderApp('/register')

    await fillRegister()

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong.')
  })
})

describe('route guard', () => {
  it('offers a retry when the server cannot be reached', async () => {
    localStorage.setItem('slotted.refreshToken', 'saved')
    session.offline()
    const api = fakeApi({ 'POST /auth/refresh': { body: authResponse() } })
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Welcome, Ada Tan' })).toBeInTheDocument()
    expect(api.calls).toHaveLength(1)
  })

  it('shows a waiting message while the session is being restored', () => {
    localStorage.setItem('slotted.refreshToken', 'saved')
    session.restoring()

    renderApp('/')

    expect(screen.getByRole('status')).toHaveTextContent('Signing you in')
  })

  it('shows a not found page for unknown addresses', () => {
    renderApp('/nowhere')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
