import { useQueryClient } from '@tanstack/react-query'
import { Link, NavLink, Outlet } from 'react-router'
import { useServerWaking } from '../api/client'
import { useSession } from '../api/session'
import { signOut } from '../features/auth/authApi'
import { ThemeSetting, ThemeToggle } from '../features/theme/ThemeControls'

/** The frame around every page: header, the "waking the server" notice, content and footer. */
export function AppShell() {
  const current = useSession()
  const waking = useServerWaking()
  const queryClient = useQueryClient()

  async function handleSignOut() {
    await signOut()
    queryClient.clear()
  }

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="header">
        <div className="header-inner">
          <Link to="/" className="brand">
            Slotted
          </Link>
          {current.status === 'signedIn' && (
            <nav aria-label="Main" className="nav">
              <NavLink to="/" end>
                Book a room
              </NavLink>
              <NavLink to="/bookings">My bookings</NavLink>
            </nav>
          )}
          <div className="header-actions">
            <ThemeToggle />
            {current.status === 'signedIn' && (
              <>
                <span className="user-name">{current.user.fullName}</span>
                <button type="button" className="button" onClick={() => void handleSignOut()}>
                  Sign out
                </button>
              </>
            )}
          </div>
        </div>
      </header>
      {waking && (
        <p className="waking" role="status">
          Waking the server. This free service sleeps when idle and can take up to a minute.
        </p>
      )}
      <main id="main" className="main">
        <Outlet />
      </main>
      <footer className="footer">
        <ThemeSetting />
      </footer>
    </div>
  )
}
