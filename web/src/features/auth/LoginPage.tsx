import { useMutation } from '@tanstack/react-query'
import { useState, type SyntheticEvent } from 'react'
import { Link, Navigate, useLocation } from 'react-router'
import { errorMessage } from '../../api/errors'
import { useSession } from '../../api/session'
import { signIn } from './authApi'

/** Where to go after signing in: the page that sent the user here, or the home page. */
function useReturnPath(): string {
  const state = useLocation().state as { from?: string } | null
  return state?.from ?? '/'
}

export function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const returnPath = useReturnPath()
  const login = useMutation({ mutationFn: () => signIn(email, password) })

  if (useSession().status === 'signedIn') {
    return <Navigate to={returnPath} replace />
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    login.mutate()
  }

  return (
    <div className="auth-card">
      <h1>Sign in</h1>
      <form onSubmit={submit}>
        {login.isError && (
          <p className="form-error" role="alert">
            {errorMessage(login.error)}
          </p>
        )}
        <label className="field">
          Email
          <input
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => {
              setEmail(event.target.value)
            }}
          />
        </label>
        <label className="field">
          Password
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => {
              setPassword(event.target.value)
            }}
          />
        </label>
        <button type="submit" className="button button-primary" disabled={login.isPending}>
          {login.isPending ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p className="auth-switch">
        New here? <Link to="/register">Create an account</Link>
      </p>
    </div>
  )
}
