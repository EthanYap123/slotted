import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { session } from '../../api/session'
import { authResponse, fakeApi, problem, student } from '../../test/fakeApi'
import { renderApp } from '../../test/render'
import type { Booking } from './bookingApi'

const TODAY = '2026-11-04'
const UPCOMING = 'GET /bookings/mine?scope=upcoming&page=0&size=10'
const TOKEN = 'a'.repeat(43)

function booking(overrides: Partial<Booking> = {}): Booking {
  return {
    id: 'b1',
    roomId: 'r1',
    roomName: 'Room A',
    startAt: `${TODAY}T10:00:00+08:00`,
    endAt: `${TODAY}T12:00:00+08:00`,
    status: 'CONFIRMED',
    facilityId: 'f1',
    facilityName: 'Library',
    userId: student.id,
    attendees: 1,
    source: 'DIRECT',
    checkInOpensAt: `${TODAY}T09:50:00+08:00`,
    checkInClosesAt: `${TODAY}T10:15:00+08:00`,
    lateCancellation: false,
    createdAt: `${TODAY}T08:00:00+08:00`,
    ...overrides,
  }
}

function page(items: Booking[]) {
  return {
    body: { items, page: { number: 0, size: 10, totalElements: items.length, totalPages: 1 } },
  }
}

function at(time: string) {
  vi.setSystemTime(new Date(`${TODAY}T${time}+08:00`))
}

beforeEach(() => {
  // Only Date is faked, so promises and user events still run normally.
  vi.useFakeTimers({ toFake: ['Date'] })
  at('10:05:00')
  session.signedIn(authResponse())
})

afterEach(() => {
  vi.useRealTimers()
})

describe('the check-in page', () => {
  it('checks in once from a QR code link and confirms it', async () => {
    const api = fakeApi({
      'POST /check-ins': {
        body: booking({ status: 'CHECKED_IN', checkedInAt: `${TODAY}T10:05:00+08:00` }),
      },
    })
    renderApp(`/check-in?token=${TOKEN}`)

    expect(await screen.findByRole('status')).toHaveTextContent(
      'You are checked in to Room A, Wed, 4 Nov 2026, 10:00 to 12:00.',
    )
    const posts = api.calls.filter((call) => call.path === '/check-ins')
    expect(posts).toHaveLength(1)
    expect(posts[0]?.body).toEqual({ token: TOKEN })
  })

  it('explains a refusal and links back to the bookings', async () => {
    fakeApi({
      'POST /check-ins': problem(
        422,
        'CHECK_IN_CLOSED',
        'Check-in closed 15 minutes after the start, so this booking was released.',
      ),
    })
    renderApp(`/check-in?token=${TOKEN}`)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('so this booking was released.')
    expect(within(alert).getByRole('link', { name: 'See my bookings' })).toHaveAttribute(
      'href',
      '/bookings',
    )
  })

  it('without a QR code, explains how to check in and sends nothing', async () => {
    const api = fakeApi({})
    renderApp('/check-in')

    expect(await screen.findByText(/Scan the QR code at the room you booked/)).toBeInTheDocument()
    expect(api.calls).toHaveLength(0)
  })
})

describe('check-in and ending early from my bookings', () => {
  function routes(item: Booking) {
    return { [UPCOMING]: page([item]), 'GET /waitlist/mine': { body: [] } }
  }

  it('offers check-in while the window is open', async () => {
    fakeApi(routes(booking()))
    renderApp('/bookings')

    expect(await screen.findByRole('link', { name: 'Check in to Room A, 10:00' })).toHaveAttribute(
      'href',
      '/check-in',
    )
  })

  it.each([
    ['before the window opens', '09:49:00'],
    ['after it closes', '10:15:00'],
  ])('does not offer check-in %s', async (_, time) => {
    at(time)
    fakeApi(routes(booking()))
    renderApp('/bookings')

    await screen.findByText('Room A')
    expect(screen.queryByRole('link', { name: /Check in/ })).not.toBeInTheDocument()
  })

  it('ends a checked-in booking after confirming, and says when the room is free', async () => {
    const checkedIn = booking({ status: 'CHECKED_IN', checkedInAt: `${TODAY}T09:58:00+08:00` })
    const api = fakeApi({
      ...routes(checkedIn),
      'POST /bookings/b1/end': {
        body: { ...checkedIn, endAt: `${TODAY}T10:30:00+08:00` },
      },
    })
    const user = userEvent.setup()
    renderApp('/bookings')

    expect(screen.queryByRole('link', { name: /Check in/ })).not.toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'End Room A, 10:00, now' }))
    const dialog = screen.getByRole('dialog', { name: 'End this booking now?' })
    await user.click(within(dialog).getByRole('button', { name: 'End booking' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Ended Room A. The room is free from 10:30.',
    )
    expect(api.calls.some((call) => call.path === '/bookings/b1/end')).toBe(true)
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('keeps the booking when not confirmed, and shows why ending failed', async () => {
    const checkedIn = booking({ status: 'CHECKED_IN', checkedInAt: `${TODAY}T09:58:00+08:00` })
    const api = fakeApi({
      ...routes(checkedIn),
      'POST /bookings/b1/end': problem(422, 'NOT_CHECKED_IN', 'This booking has ended.'),
    })
    const user = userEvent.setup()
    renderApp('/bookings')

    await user.click(await screen.findByRole('button', { name: 'End Room A, 10:00, now' }))
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Keep booking' }),
    )
    expect(api.calls.some((call) => call.path === '/bookings/b1/end')).toBe(false)

    await user.click(screen.getByRole('button', { name: 'End Room A, 10:00, now' }))
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'End booking' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('This booking has ended.')
  })

  it('does not offer to end a booking twice', async () => {
    fakeApi(
      routes(
        booking({
          status: 'CHECKED_IN',
          checkedInAt: `${TODAY}T09:58:00+08:00`,
          endAt: `${TODAY}T10:30:00+08:00`,
          releasedAt: `${TODAY}T10:04:00+08:00`,
        }),
      ),
    )
    renderApp('/bookings')

    await screen.findByText('Room A')
    expect(screen.queryByRole('button', { name: /End Room A/ })).not.toBeInTheDocument()
  })

  it('does not offer to end a booking that is over', async () => {
    at('12:00:00')
    fakeApi(routes(booking({ status: 'CHECKED_IN', checkedInAt: `${TODAY}T09:58:00+08:00` })))
    renderApp('/bookings')

    await screen.findByText('Room A')
    expect(screen.queryByRole('button', { name: /End Room A/ })).not.toBeInTheDocument()
  })
})
