import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { session } from '../../api/session'
import { authResponseFor, fakeApi, manager, problem } from '../../test/fakeApi'
import { renderApp } from '../../test/render'

const TODAY = '2026-11-04'
const FACILITIES = [
  { id: 'f1', name: 'Library', timeZone: 'Asia/Singapore', openingHours: [], version: 0 },
  { id: 'f2', name: 'Sports Hall', timeZone: 'Asia/Singapore', openingHours: [], version: 0 },
]
const REPORT = {
  fromDate: '2026-10-08',
  toDate: TODAY,
  rooms: [
    {
      roomId: 'r1',
      roomName: 'Room A',
      facilityId: 'f1',
      facilityName: 'Library',
      openHours: 100,
      bookedHours: 42.5,
      utilisation: 0.425,
      bookings: 30,
      checkInRate: 0.8,
      noShowRate: 0.1,
      cancellations: 4,
      lateCancellations: 1,
    },
  ],
  heatmap: [
    { dayOfWeek: 1, hour: 9, utilisation: 0.5 },
    { dayOfWeek: 1, hour: 11, utilisation: 1 },
  ],
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(`${TODAY}T10:12:00+08:00`))
  session.signedIn(authResponseFor(manager))
})

afterEach(() => {
  vi.useRealTimers()
})

it('shows utilisation per room and a heat map for the last four weeks', async () => {
  const api = fakeApi({
    'GET /facilities': { body: FACILITIES },
    'GET /analytics/utilisation': { body: REPORT },
  })
  renderApp('/analytics')

  const table = await screen.findByRole('table', { name: 'By room' })
  const row = within(table).getByRole('row', { name: /Room A/ })
  expect(row).toHaveTextContent('43%')
  expect(row).toHaveTextContent('42.5 / 100')
  expect(row).toHaveTextContent('80%')
  expect(api.calls.find((c) => c.path.startsWith('/analytics'))?.path).toBe(
    '/analytics/utilisation?fromDate=2026-10-08&toDate=2026-11-04',
  )
  // Only the manager's own facility is offered.
  expect(screen.getByRole('option', { name: 'Library' })).toBeInTheDocument()
  expect(screen.queryByRole('option', { name: 'Sports Hall' })).not.toBeInTheDocument()

  const heatmap = screen.getByRole('table', { name: 'Busiest times' })
  const monday = within(heatmap).getByRole('row', { name: /Mon/ })
  // 09:00 to 11:00, with 10:00 shown as closed.
  expect(
    within(monday)
      .getAllByRole('cell')
      .map((c) => c.textContent),
  ).toEqual(['50%', 'closed', '100%'])
})

it('does not ask for more than 92 days', async () => {
  const api = fakeApi({
    'GET /facilities': { body: FACILITIES },
    'GET /analytics/utilisation': { body: REPORT },
  })
  const u = userEvent.setup()
  renderApp('/analytics')
  await screen.findByRole('table', { name: 'By room' })

  const from = screen.getByLabelText('From')
  await u.clear(from)
  await u.type(from, '2026-01-01')

  expect(screen.getByLabelText('To')).toHaveAccessibleDescription('At most 92 days at a time')
  expect(api.calls.filter((c) => c.path.includes('fromDate=2026-01-01'))).toHaveLength(0)
})

it('shows a refusal for a facility the manager does not manage', async () => {
  fakeApi({
    'GET /facilities': { body: FACILITIES },
    'GET /analytics/utilisation': problem(403, 'FORBIDDEN', 'You do not manage this facility.'),
  })
  renderApp('/analytics')

  expect(await screen.findByRole('alert')).toHaveTextContent('You do not manage this facility.')
})
