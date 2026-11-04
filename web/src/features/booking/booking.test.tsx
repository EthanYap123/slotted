import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { session } from '../../api/session'
import { authResponse, fakeApi, problem, student } from '../../test/fakeApi'
import { renderApp } from '../../test/render'
import { addDays, formatDay, todayInSingapore } from '../../lib/time'
import type { Booking, RoomAvailability } from './bookingApi'
import { endOptions } from './endOptions'

const TODAY = '2026-11-04'
const FACILITIES = [
  { id: 'f1', name: 'Library', timeZone: 'Asia/Singapore', openingHours: [], version: 0 },
  { id: 'f2', name: 'Sports Hall', timeZone: 'Asia/Singapore', openingHours: [], version: 0 },
]
const AVAILABILITY = `GET /facilities/f1/availability?date=${TODAY}`

function cell(time: string, state: RoomAvailability['cells'][number]['state']) {
  return { startAt: `${TODAY}T${time}:00+08:00`, state }
}

function room(name = 'Room A'): RoomAvailability {
  return {
    roomId: 'r1',
    roomName: name,
    roomType: 'STUDY_ROOM',
    capacity: 6,
    maxBookingMinutes: 120,
    cells: [
      cell('10:00', 'PAST'),
      cell('10:30', 'FREE'),
      cell('11:00', 'FREE'),
      cell('11:30', 'FREE'),
      cell('12:00', 'FREE'),
      cell('12:30', 'FREE'),
      cell('13:00', 'BOOKED'),
      cell('13:30', 'MINE'),
    ],
  }
}

function availability(rooms: RoomAvailability[] = [room()]) {
  return {
    body: {
      facilityId: 'f1',
      date: TODAY,
      opensAt: `${TODAY}T10:00:00+08:00`,
      closesAt: `${TODAY}T14:00:00+08:00`,
      rooms,
    },
  }
}

function booking(overrides: Partial<Booking> = {}): Booking {
  return {
    id: 'b1',
    roomId: 'r1',
    roomName: 'Room A',
    startAt: `${TODAY}T15:00:00+08:00`,
    endAt: `${TODAY}T16:00:00+08:00`,
    status: 'CONFIRMED',
    facilityId: 'f1',
    facilityName: 'Library',
    userId: student.id,
    attendees: 2,
    source: 'DIRECT',
    lateCancellation: false,
    createdAt: `${TODAY}T09:00:00+08:00`,
    ...overrides,
  }
}

function page(items: Booking[], number = 0, totalPages = 1) {
  return { body: { items, page: { number, size: 10, totalElements: items.length, totalPages } } }
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

describe('the timeline', () => {
  it('shows each cell state in words, not only colour', async () => {
    fakeApi({ 'GET /facilities': { body: FACILITIES }, [AVAILABILITY]: availability() })
    renderApp('/')

    expect(await screen.findByRole('button', { name: 'Room A, 10:30, free' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Room A, 10:00, in the past' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(screen.getByRole('button', { name: 'Room A, 13:00, booked' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(screen.getByRole('button', { name: 'Room A, 13:30, your booking' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '11:00' })).toBeInTheDocument()
    expect(screen.getByText('All times are Singapore time.')).toBeInTheDocument()
  })

  it('has one tab stop and moves with the arrow keys', async () => {
    fakeApi({ 'GET /facilities': { body: FACILITIES }, [AVAILABILITY]: availability() })
    const user = userEvent.setup()
    renderApp('/')
    const first = await screen.findByRole('button', { name: 'Room A, 10:30, free' })

    expect(first).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('button', { name: 'Room A, 11:00, free' })).toHaveAttribute(
      'tabindex',
      '-1',
    )

    first.focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('button', { name: 'Room A, 11:00, free' })).toHaveFocus()

    await user.keyboard('{End}')
    expect(screen.getByRole('button', { name: 'Room A, 13:30, your booking' })).toHaveFocus()

    await user.keyboard('{Home}')
    expect(screen.getByRole('button', { name: 'Room A, 10:00, in the past' })).toHaveFocus()
  })

  it('does not open the dialog for cells that are not free', async () => {
    fakeApi({ 'GET /facilities': { body: FACILITIES }, [AVAILABILITY]: availability() })
    const user = userEvent.setup()
    renderApp('/')

    await user.click(await screen.findByRole('button', { name: 'Room A, 13:00, booked' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('changes facility and day, and stays within the booking window', async () => {
    const lastDay = addDays(TODAY, 14)
    const api = fakeApi({
      'GET /facilities': { body: FACILITIES },
      [AVAILABILITY]: availability(),
      [`GET /facilities/f1/availability?date=${addDays(TODAY, 1)}`]: availability(),
      [`GET /facilities/f2/availability?date=${addDays(TODAY, 1)}`]: availability(),
      [`GET /facilities/f1/availability?date=${lastDay}`]: availability(),
    })
    const user = userEvent.setup()
    renderApp('/')

    expect(await screen.findByRole('button', { name: 'Previous day' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next day' }))
    expect(
      await screen.findByRole('heading', { name: formatDay(addDays(TODAY, 1)) }),
    ).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Facility'), 'Sports Hall')
    await waitFor(() => {
      expect(api.calls.map((call) => call.path)).toContain(
        `/facilities/f2/availability?date=${addDays(TODAY, 1)}`,
      )
    })
  })

  it('stops at the last bookable day', async () => {
    const lastDay = addDays(TODAY, 14)
    fakeApi({
      'GET /facilities': { body: FACILITIES },
      [`GET /facilities/f1/availability?date=${lastDay}`]: availability(),
    })
    renderApp(`/?date=${lastDay}`)

    expect(await screen.findByRole('button', { name: 'Next day' })).toBeDisabled()
  })

  it('explains closed days, facilities without rooms and having no facilities', async () => {
    fakeApi({
      'GET /facilities': { body: FACILITIES },
      [AVAILABILITY]: {
        body: { facilityId: 'f1', date: TODAY, opensAt: null, closesAt: null, rooms: [room()] },
      },
    })
    const closed = renderApp('/')
    expect(await screen.findByText('This facility is closed on this day.')).toBeInTheDocument()
    closed.unmount()

    fakeApi({ 'GET /facilities': { body: FACILITIES }, [AVAILABILITY]: availability([]) })
    const empty = renderApp('/')
    expect(await screen.findByText('This facility has no rooms to book yet.')).toBeInTheDocument()
    empty.unmount()

    fakeApi({ 'GET /facilities': { body: [] } })
    renderApp('/')
    expect(await screen.findByText('There are no facilities to book yet.')).toBeInTheDocument()
  })

  it('warns a suspended student', async () => {
    session.signedIn({
      ...authResponse(),
      user: { ...student, suspendedUntil: `${TODAY}T18:00:00+08:00` },
    })
    fakeApi({ 'GET /facilities': { body: [] } })
    renderApp('/')

    expect(await screen.findByText(/cannot make new bookings/)).toHaveTextContent('18:00')
  })

  it('uses Singapore time for today, whatever the browser zone', () => {
    vi.setSystemTime(new Date('2026-11-04T16:30:00Z'))

    expect(todayInSingapore()).toBe('2026-11-05')
  })
})

describe('booking a slot', () => {
  it('offers end times while the room stays free, up to the room maximum', () => {
    const fromFirstFree = endOptions(room(), 1)
    const beforeBooked = endOptions(room(), 5)

    expect(fromFirstFree).toHaveLength(4)
    expect(new Date(fromFirstFree.at(-1) ?? '').toISOString()).toBe('2026-11-04T04:30:00.000Z')
    expect(beforeBooked).toHaveLength(1)
  })

  async function openDialog(routes: Parameters<typeof fakeApi>[0]) {
    const api = fakeApi({
      'GET /facilities': { body: FACILITIES },
      [AVAILABILITY]: availability(),
      ...routes,
    })
    const user = userEvent.setup()
    renderApp('/')
    await user.click(await screen.findByRole('button', { name: 'Room A, 10:30, free' }))
    return { api, user, dialog: screen.getByRole('dialog', { name: 'Book Room A' }) }
  }

  it('books the slot and shows what was booked', async () => {
    const { api, user, dialog } = await openDialog({
      'POST /bookings': {
        status: 201,
        body: booking({
          startAt: `${TODAY}T10:30:00+08:00`,
          endAt: `${TODAY}T11:30:00+08:00`,
        }),
      },
    })

    await user.clear(within(dialog).getByLabelText('Number of people'))
    await user.type(within(dialog).getByLabelText('Number of people'), '3')
    await user.type(within(dialog).getByLabelText('Purpose (optional)'), 'Project')
    await user.click(within(dialog).getByRole('button', { name: 'Book' }))

    expect(await screen.findByText(/Booked Room A/)).toHaveTextContent('10:30 to 11:30')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.calls.find((call) => call.method === 'POST')?.body).toEqual({
      roomId: 'r1',
      startAt: `${TODAY}T10:30:00+08:00`,
      endAt: '2026-11-04T03:30:00.000Z',
      attendees: 3,
      purpose: 'Project',
    })
    // The timeline is fetched again so it shows the new booking.
    await waitFor(() => {
      expect(api.calls.filter((call) => call.path.includes('availability'))).toHaveLength(2)
    })
  })

  it('when the slot was just taken, says so and refreshes the timeline', async () => {
    const { api, user, dialog } = await openDialog({
      'POST /bookings': problem(409, 'SLOT_TAKEN', 'Someone else has booked this room.'),
    })

    await user.click(within(dialog).getByRole('button', { name: 'Book' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Someone else has booked this room.')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(api.calls.filter((call) => call.path.includes('availability'))).toHaveLength(2)
    })
  })

  it('keeps the dialog open and shows other rule errors in plain language', async () => {
    const { user, dialog } = await openDialog({
      'POST /bookings': problem(422, 'QUOTA_UPCOMING_EXCEEDED', 'You can hold at most 3.'),
    })

    await user.click(within(dialog).getByRole('button', { name: 'Book' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('You can hold at most 3.')
  })

  it('closes without booking', async () => {
    const { api, user, dialog } = await openDialog({})

    await user.click(within(dialog).getByRole('button', { name: 'Keep looking' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.calls.some((call) => call.method === 'POST')).toBe(false)
  })
})

describe('my bookings', () => {
  const UPCOMING = 'GET /bookings/mine?scope=upcoming&page=0&size=10'
  const PAST = 'GET /bookings/mine?scope=past&page=0&size=10'

  it('lists upcoming bookings and switches to past ones', async () => {
    fakeApi({
      [UPCOMING]: page([booking()]),
      [PAST]: page([
        booking({ id: 'b2', roomName: 'Room B', status: 'NO_SHOW' }),
        booking({
          id: 'b3',
          roomName: 'Room C',
          status: 'CANCELLED',
          cancelReason: 'Room flooded',
          lateCancellation: true,
        }),
      ]),
    })
    const user = userEvent.setup()
    renderApp('/bookings')

    expect(await screen.findByRole('heading', { name: 'Room A' })).toBeInTheDocument()
    expect(screen.getByText('Confirmed')).toBeInTheDocument()
    expect(screen.getByText(/15:00 to 16:00/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Past' }))

    expect(await screen.findByText('No-show')).toBeInTheDocument()
    expect(screen.getByText('Reason: Room flooded')).toBeInTheDocument()
    expect(screen.getByText('Late cancellation')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Cancel / })).not.toBeInTheDocument()
  })

  it('invites people with no bookings to make one', async () => {
    fakeApi({ [UPCOMING]: page([]) })
    renderApp('/bookings')

    expect(await screen.findByRole('link', { name: 'Book a room' })).toHaveAttribute('href', '/')
  })

  it('does not offer to cancel a booking that has started', async () => {
    fakeApi({
      [UPCOMING]: page([booking({ startAt: `${TODAY}T10:00:00+08:00` })]),
    })
    renderApp('/bookings')

    await screen.findByRole('heading', { name: 'Room A' })
    expect(screen.queryByRole('button', { name: /^Cancel / })).not.toBeInTheDocument()
  })

  async function openCancel(item: Booking, cancelReply: Parameters<typeof fakeApi>[0][string]) {
    const api = fakeApi({
      [UPCOMING]: page([item]),
      'POST /bookings/b1/cancel': cancelReply,
    })
    const user = userEvent.setup()
    renderApp('/bookings')
    await user.click(await screen.findByRole('button', { name: /^Cancel Room A/ }))
    return { api, user, dialog: screen.getByRole('dialog') }
  }

  it('cancels after confirming, without a late warning when there is time', async () => {
    const { api, user, dialog } = await openCancel(booking(), {
      body: booking({ status: 'CANCELLED' }),
    })

    expect(within(dialog).queryByText(/late cancellation/)).not.toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason (optional)'), 'Plans changed')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))

    expect(await screen.findByText(/^Cancelled Room A/)).toBeInTheDocument()
    expect(api.calls.find((call) => call.method === 'POST')?.body).toEqual({
      reason: 'Plans changed',
    })
    await waitFor(() => {
      expect(api.calls.filter((call) => call.path.includes('/mine'))).toHaveLength(2)
    })
  })

  it('warns before a late cancellation and reports it afterwards', async () => {
    const soon = booking({ startAt: `${TODAY}T11:00:00+08:00`, endAt: `${TODAY}T12:00:00+08:00` })
    const { user, dialog } = await openCancel(soon, {
      body: { ...soon, status: 'CANCELLED', lateCancellation: true },
    })

    expect(within(dialog).getByText(/recorded as a late cancellation/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))

    expect(await screen.findByText(/This was recorded as a late cancellation/)).toBeInTheDocument()
  })

  it('shows an error if cancelling fails, and can back out', async () => {
    const { user, dialog } = await openCancel(
      booking(),
      problem(422, 'ALREADY_STARTED', 'This booking has already started.'),
    )

    await user.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('already started')

    await user.click(within(dialog).getByRole('button', { name: 'Keep booking' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('pages through long lists', async () => {
    const api = fakeApi({
      [UPCOMING]: page([booking()], 0, 2),
      'GET /bookings/mine?scope=upcoming&page=1&size=10': page([booking({ id: 'b9' })], 1, 2),
    })
    const user = userEvent.setup()
    renderApp('/bookings')

    await user.click(await screen.findByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(api.calls.at(-1)?.path).toContain('page=1')
  })
})
