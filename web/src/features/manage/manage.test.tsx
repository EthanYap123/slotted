import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { session } from '../../api/session'
import {
  authResponse,
  authResponseFor,
  emptyPage,
  fakeApi,
  manager,
  problem,
} from '../../test/fakeApi'
import { renderApp } from '../../test/render'
import type { Booking } from '../booking/bookingApi'
import type { Room } from './manageApi'

const TODAY = '2026-11-04'
const FACILITY = {
  id: 'f1',
  name: 'Library',
  timeZone: 'Asia/Singapore',
  openingHours: [{ dayOfWeek: 'MONDAY', opensAt: '08:00', closesAt: '22:00' }],
  version: 3,
}
const OTHER = { ...FACILITY, id: 'f2', name: 'Sports Hall', openingHours: [] }
const ROOM: Room = {
  id: 'r1',
  facilityId: 'f1',
  name: 'Room A',
  type: 'STUDY_ROOM',
  capacity: 6,
  maxBookingMinutes: 120,
  active: true,
  version: 7,
}

const BASE_ROUTES = {
  'GET /facilities': { body: [FACILITY, OTHER] },
  'GET /facilities/f1': { body: FACILITY },
  'GET /facilities/f1/rooms': { body: [ROOM] },
}

function booking(overrides: Partial<Booking> = {}): Booking {
  return {
    id: 'b1',
    roomId: 'r1',
    roomName: 'Room A',
    facilityId: 'f1',
    facilityName: 'Library',
    userId: 'u1',
    userName: 'Ada Tan',
    startAt: `${TODAY}T14:00:00+08:00`,
    endAt: `${TODAY}T15:00:00+08:00`,
    status: 'CONFIRMED',
    attendees: 2,
    source: 'DIRECT',
    lateCancellation: false,
    createdAt: `${TODAY}T09:00:00+08:00`,
    ...overrides,
  }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(`${TODAY}T10:12:00+08:00`))
  session.signedIn(authResponseFor(manager))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('who sees the manager pages', () => {
  it('shows Manage and Analytics to a manager, but not the admin pages', async () => {
    fakeApi(BASE_ROUTES)
    renderApp('/manage')

    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Manage' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Analytics' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Users' })).not.toBeInTheDocument()
    // A manager of one facility goes straight to it.
    expect(await screen.findByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument()
  })

  it('keeps students out of the manager pages', () => {
    session.signedIn(authResponse())
    fakeApi({})
    renderApp('/manage')

    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).queryByRole('link', { name: 'Manage' })).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('You do not have access to this page.')
  })
})

describe('rooms', () => {
  it('adds a room and shows an API field error beside its field', async () => {
    const api = fakeApi({
      ...BASE_ROUTES,
      'POST /facilities/f1/rooms': [
        problem(400, 'VALIDATION_FAILED', 'Check the highlighted fields.', [
          { field: 'capacity', message: 'must be less than or equal to 500' },
        ]),
        { status: 201, body: { ...ROOM, id: 'r2', name: 'Room B', capacity: 8 } },
      ],
    })
    const user = userEvent.setup()
    renderApp('/manage/f1')

    await user.click(await screen.findByRole('button', { name: 'Add room' }))
    const dialog = screen.getByRole('dialog', { name: 'Add a room' })
    await user.type(within(dialog).getByLabelText('Name'), 'Room B')
    const capacity = within(dialog).getByLabelText('Capacity (people)')
    await user.clear(capacity)
    await user.type(capacity, '900')
    await user.click(within(dialog).getByRole('button', { name: 'Add room' }))

    expect(await within(dialog).findByText('must be less than or equal to 500')).toBeVisible()
    expect(capacity).toHaveAttribute('aria-invalid', 'true')
    expect(capacity).toHaveAccessibleDescription('must be less than or equal to 500')

    await user.clear(capacity)
    await user.type(capacity, '8')
    await user.click(within(dialog).getByRole('button', { name: 'Add room' }))

    expect(await screen.findByText('Saved Room B.')).toBeInTheDocument()
    expect(api.calls.filter((c) => c.method === 'POST').at(-1)?.body).toEqual({
      name: 'Room B',
      type: 'STUDY_ROOM',
      capacity: 8,
      maxBookingMinutes: 120,
    })
  })

  it('sends the version it read when deactivating, so a newer edit is not overwritten', async () => {
    const api = fakeApi({
      ...BASE_ROUTES,
      'PATCH /rooms/r1': problem(
        412,
        'VERSION_CONFLICT',
        'This was changed by someone else since you loaded it. Reload and try again.',
      ),
    })
    const user = userEvent.setup()
    renderApp('/manage/f1')

    await user.click(await screen.findByRole('button', { name: 'Deactivate Room A' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('changed by someone else')
    const patch = api.calls.find((c) => c.method === 'PATCH')
    expect(patch?.headers['If-Match']).toBe('7')
    expect(patch?.body).toEqual({ active: false })
  })
})

describe('opening hours', () => {
  it('shows the bookings a change cancels before saving it', async () => {
    const affected = booking({
      startAt: '2026-11-09T20:00:00+08:00',
      endAt: '2026-11-09T21:00:00+08:00',
    })
    const api = fakeApi({
      ...BASE_ROUTES,
      'PUT /facilities/f1/opening-hours?dryRun=true': {
        body: { applied: false, affectedBookings: [affected], affectedWaitlistEntries: 2 },
      },
      'PUT /facilities/f1/opening-hours?dryRun=false': {
        body: { applied: true, affectedBookings: [affected], affectedWaitlistEntries: 2 },
      },
    })
    const user = userEvent.setup()
    renderApp('/manage/f1?tab=hours')

    await user.selectOptions(await screen.findByLabelText('Monday closes at'), '18:00')
    await user.click(screen.getByLabelText('Open on Tuesday'))
    await user.click(screen.getByRole('button', { name: 'Check and save' }))

    const summary = await screen.findByText(/1 booking will be cancelled/)
    expect(summary).toHaveTextContent('2 waitlist entries will expire')
    expect(screen.getByText(/Room A, Mon, 9 Nov 2026, 20:00 to 21:00 \(Ada Tan\)/)).toBeVisible()
    expect(api.calls.find((c) => c.method === 'PUT')?.body).toEqual([
      { dayOfWeek: 'MONDAY', opensAt: '08:00', closesAt: '18:00' },
      { dayOfWeek: 'TUESDAY', opensAt: '08:00', closesAt: '22:00' },
    ])
    expect(api.calls.some((c) => c.path.endsWith('dryRun=false'))).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Save and cancel these bookings' }))

    expect(await screen.findByText(/Opening hours saved. 1 booking was cancelled/)).toBeVisible()
  })

  it('does not send hours that close before they open', async () => {
    const api = fakeApi(BASE_ROUTES)
    const user = userEvent.setup()
    renderApp('/manage/f1?tab=hours')

    await user.selectOptions(await screen.findByLabelText('Monday closes at'), '07:00')

    expect(screen.getByLabelText('Monday closes at')).toHaveAccessibleDescription(
      'Must be after opening',
    )
    expect(screen.getByRole('button', { name: 'Check and save' })).toBeDisabled()
    expect(api.calls.some((c) => c.method === 'PUT')).toBe(false)
  })
})

describe('blackouts', () => {
  it('previews a blackout, then adds it', async () => {
    const created = {
      id: 'x1',
      facilityId: 'f1',
      roomId: 'r1',
      startAt: '2026-11-05T08:00:00+08:00',
      endAt: '2026-11-05T22:00:00+08:00',
      reason: 'Painting',
    }
    const api = fakeApi({
      ...BASE_ROUTES,
      'GET /facilities/f1/blackouts': { body: [] },
      'POST /facilities/f1/blackouts?dryRun=true': {
        body: { applied: false, affectedBookings: [booking()], affectedWaitlistEntries: 0 },
      },
      'POST /facilities/f1/blackouts': { status: 201, body: created },
    })
    const user = userEvent.setup()
    renderApp('/manage/f1?tab=blackouts')

    expect(await screen.findByText('There are no upcoming blackouts.')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Add blackout' }))
    const dialog = screen.getByRole('dialog', { name: 'Add a blackout' })
    await user.selectOptions(within(dialog).getByLabelText('Room'), 'Room A')
    await user.type(within(dialog).getByLabelText('Reason'), 'Painting')
    await user.click(within(dialog).getByRole('button', { name: 'Check impact' }))

    expect(await within(dialog).findByText(/1 booking will be cancelled/)).toBeVisible()
    await user.click(within(dialog).getByRole('button', { name: 'Add and cancel these bookings' }))

    expect(await screen.findByText(/Blackout added: Room A/)).toBeVisible()
    const post = api.calls.filter((c) => c.method === 'POST')
    expect(post.map((c) => c.path)).toEqual([
      '/facilities/f1/blackouts?dryRun=true',
      '/facilities/f1/blackouts',
    ])
    expect(post[1]?.body).toEqual({
      roomId: 'r1',
      startAt: '2026-11-05T08:00:00+08:00',
      endAt: '2026-11-05T22:00:00+08:00',
      reason: 'Painting',
    })
  })
})

describe('bookings', () => {
  it('lists bookings with who made them, and needs a reason to cancel one', async () => {
    const api = fakeApi({
      ...BASE_ROUTES,
      'GET /bookings': {
        body: {
          items: [
            booking(),
            booking({
              id: 'b2',
              status: 'CHECKED_IN',
              startAt: `${TODAY}T09:00:00+08:00`,
              endAt: `${TODAY}T11:00:00+08:00`,
              checkedInAt: `${TODAY}T09:04:00+08:00`,
              userName: 'Ben Ong',
            }),
          ],
          page: { number: 0, size: 20, totalElements: 2, totalPages: 1 },
        },
      },
      'POST /bookings/b1/cancel': { body: booking({ status: 'CANCELLED' }) },
    })
    const user = userEvent.setup()
    renderApp('/manage/f1?tab=bookings')

    expect(await screen.findByText('Checked in at 09:04')).toBeVisible()
    expect(screen.getByText(/Ben Ong, 2 people/)).toBeVisible()
    expect(api.calls.find((c) => c.path.startsWith('/bookings?'))?.path).toBe(
      '/bookings?facilityId=f1&from=2026-11-04T00%3A00%3A00%2B08%3A00&to=2026-11-11T00%3A00%3A00%2B08%3A00&page=0&size=20',
    )

    await user.click(screen.getByRole('button', { name: /^Cancel Room A, .* booked by Ada Tan$/ }))
    const dialog = screen.getByRole('dialog', { name: 'Cancel this booking?' })
    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))

    expect(within(dialog).getByLabelText('Reason')).toHaveAccessibleDescription(
      'Enter a reason. It is sent to the person.',
    )
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false)

    await user.type(within(dialog).getByLabelText('Reason'), 'Room needed for exams')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))

    expect(await screen.findByText(/Ada Tan will be emailed the reason/)).toBeVisible()
    expect(api.calls.find((c) => c.method === 'POST')?.body).toEqual({
      reason: 'Room needed for exams',
    })
  })

  it('shows an empty page', async () => {
    fakeApi({ ...BASE_ROUTES, 'GET /bookings': { body: emptyPage() } })
    renderApp('/manage/f1?tab=bookings')

    expect(await screen.findByText('No bookings match these filters.')).toBeVisible()
  })
})

describe('QR code', () => {
  it('shows the room QR code as an image and replaces it on request', async () => {
    let version = 1
    const api = fakeApi({
      'GET /rooms/r1': { body: ROOM },
      'GET /rooms/r1/check-in-qr': () => ({ text: `<svg data-version="${String(version)}"/>` }),
      'POST /rooms/r1/check-in-token/rotate': () => {
        version = 2
        return { status: 204 }
      },
    })
    const user = userEvent.setup()
    renderApp('/manage/rooms/r1/qr')

    const image = await screen.findByRole('img', { name: 'Check-in QR code for Room A' })
    expect(image.getAttribute('src')).toContain(encodeURIComponent('data-version="1"'))
    expect(screen.getByRole('heading', { level: 1, name: 'Room A' })).toBeVisible()

    await user.click(screen.getByRole('button', { name: 'Replace QR code' }))
    const dialog = screen.getByRole('dialog', { name: 'Replace this QR code?' })
    await user.click(within(dialog).getByRole('button', { name: 'Replace QR code' }))

    expect(await screen.findByText(/The old one no longer works/)).toBeVisible()
    await waitFor(() => {
      expect(screen.getByRole('img').getAttribute('src')).toContain(
        encodeURIComponent('data-version="2"'),
      )
    })
    expect(api.calls.filter((c) => c.path === '/rooms/r1/check-in-qr')).toHaveLength(2)
  })
})
