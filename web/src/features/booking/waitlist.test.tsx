import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { session } from '../../api/session'
import { authResponse, fakeApi, problem, student } from '../../test/fakeApi'
import { renderApp } from '../../test/render'
import type { Booking, RoomAvailability, WaitlistEntry } from './bookingApi'
import { waitlistEndOptions } from './endOptions'

const TODAY = '2026-11-04'
const FACILITIES = [
  { id: 'f1', name: 'Library', timeZone: 'Asia/Singapore', openingHours: [], version: 0 },
]
const AVAILABILITY = `GET /facilities/f1/availability?date=${TODAY}`
const UPCOMING = 'GET /bookings/mine?scope=upcoming&page=0&size=10'
const PAST = 'GET /bookings/mine?scope=past&page=0&size=10'
const EMPTY_PAGE = {
  body: { items: [], page: { number: 0, size: 10, totalElements: 0, totalPages: 0 } },
}

function cell(time: string, state: RoomAvailability['cells'][number]['state']) {
  return { startAt: `${TODAY}T${time}:00+08:00`, state }
}

const ROOM: RoomAvailability = {
  roomId: 'r1',
  roomName: 'Room A',
  roomType: 'STUDY_ROOM',
  capacity: 6,
  maxBookingMinutes: 120,
  cells: [
    cell('10:30', 'FREE'),
    cell('11:00', 'FREE'),
    cell('11:30', 'BOOKED'),
    cell('12:00', 'BOOKED'),
    cell('12:30', 'FREE'),
    cell('13:00', 'MINE'),
    cell('13:30', 'BOOKED'),
    cell('14:00', 'BLACKOUT'),
  ],
}

const SCREEN_ROUTES = {
  'GET /facilities': { body: FACILITIES },
  [AVAILABILITY]: {
    body: {
      facilityId: 'f1',
      date: TODAY,
      opensAt: `${TODAY}T10:00:00+08:00`,
      closesAt: `${TODAY}T14:30:00+08:00`,
      rooms: [ROOM],
    },
  },
}

function entry(overrides: Partial<WaitlistEntry> = {}): WaitlistEntry {
  return {
    id: 'e1',
    roomId: 'r1',
    roomName: 'Room A',
    startAt: `${TODAY}T11:30:00+08:00`,
    endAt: `${TODAY}T12:30:00+08:00`,
    status: 'WAITING',
    position: 2,
    createdAt: `${TODAY}T09:00:00+08:00`,
    ...overrides,
  }
}

beforeEach(() => {
  // Only Date is faked, so promises and user events still run normally. 10:12 in Singapore.
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(`${TODAY}T10:12:00+08:00`))
  session.signedIn(authResponse())
})

afterEach(() => {
  vi.useRealTimers()
})

describe('how long a slot can be waited for', () => {
  it('runs through taken and free time, up to the room limit', () => {
    expect(waitlistEndOptions(ROOM, 2).map((end) => new Date(end).toISOString())).toEqual([
      '2026-11-04T04:00:00.000Z',
      '2026-11-04T04:30:00.000Z',
      '2026-11-04T05:00:00.000Z',
    ])
  })

  it('stops before closed time', () => {
    expect(waitlistEndOptions(ROOM, 6)).toEqual(['2026-11-04T06:00:00.000Z'])
  })

  it('stops before the student’s own booking', () => {
    const shortRoom = { ...ROOM, maxBookingMinutes: 240 }
    expect(waitlistEndOptions(shortRoom, 4)).toEqual(['2026-11-04T05:00:00.000Z'])
  })
})

describe('joining from the timeline', () => {
  async function openDialog(routes: Parameters<typeof fakeApi>[0] = {}) {
    const api = fakeApi({ ...SCREEN_ROUTES, ...routes })
    const user = userEvent.setup()
    renderApp('/')
    await user.click(
      await screen.findByRole('button', { name: 'Room A, 11:30, booked, join the waitlist' }),
    )
    return {
      api,
      user,
      dialog: screen.getByRole('dialog', { name: 'Join the waitlist for Room A' }),
    }
  }

  it('tells students they can wait for a booked time', async () => {
    fakeApi(SCREEN_ROUTES)
    renderApp('/')

    expect(
      await screen.findByText('Select a booked time to join its waitlist.'),
    ).toBeInTheDocument()
  })

  it('joins for an hour by default and shows the place in the queue', async () => {
    const { api, user, dialog } = await openDialog({
      'POST /waitlist': { status: 201, body: entry() },
    })

    await user.click(within(dialog).getByRole('button', { name: 'Join the waitlist' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'You are number 2 in the queue for Room A',
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.calls.find((call) => call.method === 'POST')?.body).toEqual({
      roomId: 'r1',
      startAt: `${TODAY}T11:30:00+08:00`,
      endAt: '2026-11-04T04:30:00.000Z',
    })
    expect(screen.getByRole('link', { name: 'See my bookings' })).toHaveAttribute(
      'href',
      '/bookings',
    )
  })

  it('keeps the dialog open and explains a refusal', async () => {
    const { user, dialog } = await openDialog({
      'POST /waitlist': problem(422, 'WAITLIST_LIMIT', 'You can wait for at most 3 slots.'),
    })

    await user.click(within(dialog).getByRole('button', { name: 'Join the waitlist' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'You can wait for at most 3 slots.',
    )
  })

  it('closes without joining', async () => {
    const { api, user, dialog } = await openDialog()

    await user.click(within(dialog).getByRole('button', { name: 'Keep looking' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.calls.some((call) => call.method === 'POST')).toBe(false)
  })

  it('does not offer the waitlist to staff', async () => {
    session.signedIn({ ...authResponse(), user: { ...student, role: 'MANAGER' } })
    fakeApi(SCREEN_ROUTES)
    const user = userEvent.setup()
    renderApp('/')

    const booked = await screen.findByRole('button', { name: 'Room A, 11:30, booked' })
    expect(booked).toHaveAttribute('aria-disabled', 'true')
    expect(screen.queryByText('Select a booked time to join its waitlist.')).not.toBeInTheDocument()
    await user.click(booked)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('when a slot was just taken', () => {
  async function bookAndLose(waitlistable: boolean, routes: Parameters<typeof fakeApi>[0] = {}) {
    const taken = problem(409, 'SLOT_TAKEN', 'Someone else has booked this room.')
    const api = fakeApi({
      ...SCREEN_ROUTES,
      'POST /bookings': { ...taken, body: { ...taken.body, waitlistable } },
      ...routes,
    })
    const user = userEvent.setup()
    renderApp('/')
    await user.click(await screen.findByRole('button', { name: 'Room A, 10:30, free' }))
    const dialog = screen.getByRole('dialog', { name: 'Book Room A' })
    await user.click(within(dialog).getByRole('button', { name: 'Book' }))
    return { api, user, alert: await screen.findByRole('alert') }
  }

  it('offers to join the waitlist for the same slot', async () => {
    const { api, user, alert } = await bookAndLose(true, {
      'POST /waitlist': { status: 201, body: entry({ position: 1 }) },
    })

    expect(alert).toHaveTextContent('Someone else has booked this room.')
    await user.click(within(alert).getByRole('button', { name: 'Join the waitlist' }))

    expect(await screen.findByRole('status')).toHaveTextContent('You are number 1 in the queue')
    const join = api.calls.find((call) => call.path === '/waitlist')
    expect(join?.body).toEqual({
      roomId: 'r1',
      startAt: `${TODAY}T10:30:00+08:00`,
      endAt: '2026-11-04T03:30:00.000Z',
    })
  })

  it('does not offer it when the person cannot wait', async () => {
    const { alert } = await bookAndLose(false)

    expect(within(alert).queryByRole('button')).not.toBeInTheDocument()
  })

  it('says why joining failed', async () => {
    const { user, alert } = await bookAndLose(true, {
      'POST /waitlist': problem(422, 'SLOT_AVAILABLE', 'This slot is free, so you can book it.'),
    })

    await user.click(within(alert).getByRole('button', { name: 'Join the waitlist' }))

    expect(await screen.findByText('This slot is free, so you can book it.')).toBeInTheDocument()
  })
})

describe('entries in my bookings', () => {
  const PAGE_ROUTES = { [UPCOMING]: EMPTY_PAGE, [PAST]: EMPTY_PAGE }

  it('lists waiting entries with upcoming bookings, with their place in the queue', async () => {
    fakeApi({
      ...PAGE_ROUTES,
      'GET /waitlist/mine': {
        body: [entry(), entry({ id: 'e2', status: 'EXPIRED', position: null })],
      },
    })
    renderApp('/bookings')

    const section = await screen.findByRole('region', { name: 'Waiting for a slot' })
    expect(within(section).getByText('Number 2 in the queue')).toBeInTheDocument()
    expect(within(section).getByText('Waiting')).toBeInTheDocument()
    expect(within(section).queryByText('Expired')).not.toBeInTheDocument()
  })

  it('lists the others with past bookings and no way to leave', async () => {
    fakeApi({
      ...PAGE_ROUTES,
      'GET /waitlist/mine': {
        body: [
          entry(),
          entry({ id: 'e2', status: 'EXPIRED', position: null }),
          entry({ id: 'e3', status: 'PROMOTED', position: null, promotedBookingId: 'b9' }),
          entry({ id: 'e4', status: 'CANCELLED', position: null }),
        ],
      },
    })
    renderApp('/bookings?scope=past')

    const section = await screen.findByRole('region', { name: 'Past waitlists' })
    expect(within(section).getByText('Expired')).toBeInTheDocument()
    expect(within(section).getByText('Got the slot')).toBeInTheDocument()
    expect(within(section).getByText('Left')).toBeInTheDocument()
    expect(within(section).queryByText('Waiting')).not.toBeInTheDocument()
    expect(within(section).queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows nothing when there are no entries', async () => {
    fakeApi({ ...PAGE_ROUTES, 'GET /waitlist/mine': { body: [] } })
    renderApp('/bookings')

    expect(await screen.findByText(/You have no upcoming bookings/)).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('region')).not.toBeInTheDocument()
    })
  })

  it('does not ask staff for entries', async () => {
    session.signedIn({ ...authResponse(), user: { ...student, role: 'MANAGER' } })
    const api = fakeApi(PAGE_ROUTES)
    renderApp('/bookings')

    await screen.findByText(/You have no upcoming bookings/)
    expect(api.calls.some((call) => call.path.startsWith('/waitlist'))).toBe(false)
  })

  it('marks a booking that came from the waitlist', async () => {
    const promoted: Booking = {
      id: 'b1',
      roomId: 'r1',
      roomName: 'Room A',
      startAt: `${TODAY}T15:00:00+08:00`,
      endAt: `${TODAY}T16:00:00+08:00`,
      status: 'CONFIRMED',
      facilityId: 'f1',
      facilityName: 'Library',
      userId: student.id,
      attendees: 1,
      source: 'WAITLIST',
      lateCancellation: false,
      createdAt: `${TODAY}T09:00:00+08:00`,
    }
    fakeApi({
      [UPCOMING]: {
        body: { items: [promoted], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 } },
      },
      'GET /waitlist/mine': { body: [] },
    })
    renderApp('/bookings')

    expect(await screen.findByText('From the waitlist')).toBeInTheDocument()
  })

  it('leaves after confirming, and refreshes the list', async () => {
    const api = fakeApi({
      ...PAGE_ROUTES,
      'GET /waitlist/mine': [{ body: [entry()] }, { body: [] }],
      'DELETE /waitlist/e1': { status: 204 },
    })
    const user = userEvent.setup()
    renderApp('/bookings')

    await user.click(
      await screen.findByRole('button', {
        name: /Leave the waitlist for Room A/,
      }),
    )
    const dialog = screen.getByRole('dialog', { name: 'Leave this waitlist?' })
    await user.click(within(dialog).getByRole('button', { name: 'Leave the waitlist' }))

    expect(await screen.findByRole('status')).toHaveTextContent('You left the waitlist for Room A')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.calls.some((call) => call.method === 'DELETE')).toBe(true)
    await waitFor(() => {
      expect(api.calls.filter((call) => call.path === '/waitlist/mine')).toHaveLength(2)
    })
  })

  it('can keep waiting', async () => {
    const api = fakeApi({ ...PAGE_ROUTES, 'GET /waitlist/mine': { body: [entry()] } })
    const user = userEvent.setup()
    renderApp('/bookings')

    await user.click(await screen.findByRole('button', { name: /Leave the waitlist for Room A/ }))
    const dialog = screen.getByRole('dialog', { name: 'Leave this waitlist?' })
    await user.click(within(dialog).getByRole('button', { name: 'Keep waiting' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.calls.some((call) => call.method === 'DELETE')).toBe(false)
  })

  it('shows why leaving failed', async () => {
    fakeApi({
      ...PAGE_ROUTES,
      'GET /waitlist/mine': { body: [entry()] },
      'DELETE /waitlist/e1': problem(404, 'NOT_FOUND', 'No waiting entry with this ID.'),
    })
    const user = userEvent.setup()
    renderApp('/bookings')

    await user.click(await screen.findByRole('button', { name: /Leave the waitlist for Room A/ }))
    const dialog = screen.getByRole('dialog', { name: 'Leave this waitlist?' })
    await user.click(within(dialog).getByRole('button', { name: 'Leave the waitlist' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'No waiting entry with this ID.',
    )
  })

  it('reports when entries cannot be loaded', async () => {
    fakeApi({
      ...PAGE_ROUTES,
      'GET /waitlist/mine': problem(500, 'INTERNAL_ERROR', 'Something went wrong.'),
    })
    renderApp('/bookings')

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong.')
  })
})
