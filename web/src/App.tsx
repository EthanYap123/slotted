import { Route, Routes } from 'react-router'
import { ApiStatus } from './features/status/ApiStatus'

function HomePage() {
  return (
    <main className="page">
      <h1>Slotted</h1>
      <p>Book study rooms, sports courts and meeting rooms without double bookings.</p>
      <ApiStatus />
    </main>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
    </Routes>
  )
}
