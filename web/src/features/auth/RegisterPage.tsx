import { useMutation } from '@tanstack/react-query'
import { useState, type SyntheticEvent } from 'react'
import { Link, Navigate } from 'react-router'
import { ApiError, errorMessage } from '../../api/errors'
import { useSession } from '../../api/session'
import { register } from './authApi'

/** Puts an API error beside the field it is about, or returns it for the top of the form. */
function fieldErrors(error: unknown): { email?: string; password?: string; fullName?: string } {
  if (!(error instanceof ApiError)) {
    return {}
  }
  return {
    email: error.code === 'EMAIL_TAKEN' ? error.message : error.fieldErrors.email,
    password: error.code === 'WEAK_PASSWORD' ? error.message : error.fieldErrors.password,
    fullName: error.fieldErrors.fullName,
  }
}

export function RegisterPage() {
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const signUp = useMutation({ mutationFn: () => register(email, password, fullName) })

  if (useSession().status === 'signedIn') {
    return <Navigate to="/" replace />
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    signUp.mutate()
  }

  const errors = fieldErrors(signUp.error)
  const showGeneralError = signUp.isError && !errors.email && !errors.password && !errors.fullName

  return (
    <div className="auth-card">
      <h1>Create an account</h1>
      <form onSubmit={submit}>
        {showGeneralError && (
          <p className="form-error" role="alert">
            {errorMessage(signUp.error)}
          </p>
        )}
        <div className="field">
          <label>
            Full name
            <input
              autoComplete="name"
              required
              maxLength={100}
              value={fullName}
              aria-invalid={Boolean(errors.fullName)}
              aria-describedby={errors.fullName ? 'name-error' : undefined}
              onChange={(event) => {
                setFullName(event.target.value)
              }}
            />
          </label>
          {errors.fullName && (
            <span id="name-error" className="field-error">
              {errors.fullName}
            </span>
          )}
        </div>
        <div className="field">
          <label>
            Email
            <input
              type="email"
              autoComplete="email"
              required
              maxLength={254}
              value={email}
              aria-invalid={Boolean(errors.email)}
              aria-describedby={errors.email ? 'email-error' : undefined}
              onChange={(event) => {
                setEmail(event.target.value)
              }}
            />
          </label>
          {errors.email && (
            <span id="email-error" className="field-error">
              {errors.email}
            </span>
          )}
        </div>
        <div className="field">
          <label>
            Password
            <input
              type="password"
              autoComplete="new-password"
              required
              maxLength={128}
              value={password}
              aria-invalid={Boolean(errors.password)}
              aria-describedby={errors.password ? 'password-error' : 'password-hint'}
              onChange={(event) => {
                setPassword(event.target.value)
              }}
            />
          </label>
          {errors.password ? (
            <span id="password-error" className="field-error">
              {errors.password}
            </span>
          ) : (
            <span id="password-hint" className="field-hint">
              At least 10 characters. A short phrase works well.
            </span>
          )}
        </div>
        <button type="submit" className="button button-primary" disabled={signUp.isPending}>
          {signUp.isPending ? 'Creating your account…' : 'Create account'}
        </button>
      </form>
      <p className="auth-switch">
        Already have an account? <Link to="/login">Sign in</Link>
      </p>
    </div>
  )
}
