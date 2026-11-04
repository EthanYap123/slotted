import { Navigate, Outlet, useLocation } from 'react-router'
import { restoreSession } from '../../api/client'
import { useSession } from '../../api/session'
import type { components } from '../../api/schema'

type Role = components['schemas']['Role']

/**
 * Guards a group of routes: shows them only to signed-in users, optionally only to some roles.
 * This is for navigation only; the API enforces every permission itself.
 */
export function RequireAuth({ roles }: { roles?: Role[] }) {
  const current = useSession()
  const location = useLocation()

  if (current.status === 'restoring') {
    return (
      <p className="notice" role="status">
        Signing you in…
      </p>
    )
  }
  if (current.status === 'offline') {
    return (
      <div className="notice notice-error" role="alert">
        <p>The server could not be reached.</p>
        <button type="button" className="button" onClick={() => void restoreSession()}>
          Try again
        </button>
      </div>
    )
  }
  if (current.status === 'signedOut') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  if (roles && !roles.includes(current.user.role)) {
    return (
      <div className="notice notice-error" role="alert">
        You do not have access to this page.
      </div>
    )
  }
  return <Outlet />
}
