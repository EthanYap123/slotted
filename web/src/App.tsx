import { Link, Route, Routes } from 'react-router'
import { AppShell } from './components/AppShell'
import { LoginPage } from './features/auth/LoginPage'
import { RegisterPage } from './features/auth/RegisterPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { AuditLogPage } from './features/admin/AuditLogPage'
import { FacilitiesAdminPage } from './features/admin/FacilitiesAdminPage'
import { UsersPage } from './features/admin/UsersPage'
import { AnalyticsPage } from './features/analytics/AnalyticsPage'
import { BookPage } from './features/booking/BookPage'
import { CheckInPage } from './features/booking/CheckInPage'
import { MyBookingsPage } from './features/booking/MyBookingsPage'
import { ManageFacilityPage, ManageHomePage } from './features/manage/ManagePage'
import { RoomQrPage } from './features/manage/RoomQrPage'

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
          <Route index element={<BookPage />} />
          <Route path="bookings" element={<MyBookingsPage />} />
          <Route path="check-in" element={<CheckInPage />} />
        </Route>
        <Route element={<RequireAuth roles={['MANAGER', 'ADMIN']} />}>
          <Route path="manage" element={<ManageHomePage />} />
          <Route path="manage/:facilityId" element={<ManageFacilityPage />} />
          <Route path="manage/rooms/:roomId/qr" element={<RoomQrPage />} />
          <Route path="analytics" element={<AnalyticsPage />} />
        </Route>
        <Route element={<RequireAuth roles={['ADMIN']} />}>
          <Route path="admin/users" element={<UsersPage />} />
          <Route path="admin/facilities" element={<FacilitiesAdminPage />} />
          <Route path="admin/audit" element={<AuditLogPage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
