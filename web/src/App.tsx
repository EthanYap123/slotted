import { Link, Route, Routes } from 'react-router'
import { useSession } from './api/session'
import { AppShell } from './components/AppShell'
import { LoginPage } from './features/auth/LoginPage'
import { RegisterPage } from './features/auth/RegisterPage'
import { RequireAuth } from './features/auth/RequireAuth'

function HomePage() {
  const current = useSession()
  return (
    <>
      <h1>Welcome{current.user ? `, ${current.user.fullName}` : ''}</h1>
      <p>Book study rooms, sports courts and meeting rooms without double bookings.</p>
    </>
  )
}

function NotFoundPage() {
  return (
    <>
      <h1>Page not found</h1>
      <p>
        <Link to="/">Go to the home page</Link>
      </p>
    </>
  )
}

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route element={<RequireAuth />}>
          <Route index element={<HomePage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
