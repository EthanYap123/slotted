import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { errorMessage } from '../../api/errors'
import { useSession } from '../../api/session'
import { addDays, formatDate, formatDay, formatTime, todayInSingapore } from '../../lib/time'
import { BookingDialog } from './BookingDialog'
import { useAvailability, useFacilities, type Booking, type RoomAvailability } from './bookingApi'
import { Timeline, TimelineLegend } from './Timeline'

/** How far ahead the picker goes; the API enforces the real limit (BR-3). */
const BOOKING_WINDOW_DAYS = 14

type Notice = { kind: 'booked'; booking: Booking } | { kind: 'taken'; message: string }

export function BookPage() {
  const [params, setParams] = useSearchParams()
  const facilities = useFacilities()
  const current = useSession()
  const [picked, setPicked] = useState<{ room: RoomAvailability; cellIndex: number } | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)

  const today = todayInSingapore()
  const lastDay = addDays(today, BOOKING_WINDOW_DAYS)
  const date = params.get('date') ?? today
  const facilityId = params.get('facility') ?? facilities.data?.[0]?.id
  const availability = useAvailability(facilityId, date)

  function choose(next: { facility?: string; date?: string }) {
    setNotice(null)
    setParams(
      (existing) => {
        if (next.facility) existing.set('facility', next.facility)
        if (next.date) existing.set('date', next.date)
        return existing
      },
      { replace: true },
    )
  }

  const suspendedUntil = current.user?.suspendedUntil

  return (
    <>
      <h1>Book a room</h1>
      <p className="page-note">All times are Singapore time.</p>

      {suspendedUntil && (
        <p className="notice notice-warn" role="status">
          You cannot make new bookings until {formatDate(suspendedUntil)},{' '}
          {formatTime(suspendedUntil)}, after repeated no-shows.
        </p>
      )}
      {notice?.kind === 'booked' && (
        <p className="notice notice-ok" role="status">
          Booked {notice.booking.roomName}, {formatDate(notice.booking.startAt)},{' '}
          {formatTime(notice.booking.startAt)} to {formatTime(notice.booking.endAt)}.{' '}
          <Link to="/bookings">See my bookings</Link>
        </p>
      )}
      {notice?.kind === 'taken' && (
        <p className="notice notice-error" role="alert">
          {notice.message} The timeline has been updated.
        </p>
      )}

      {facilities.isPending && <p role="status">Loading facilities…</p>}
      {facilities.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(facilities.error)}
        </p>
      )}
      {facilities.data?.length === 0 && (
        <p className="notice">There are no facilities to book yet.</p>
      )}

      {facilities.data && facilities.data.length > 0 && (
        <>
          <div className="toolbar">
            <label className="field">
              Facility
              <select
                value={facilityId}
                onChange={(event) => {
                  choose({ facility: event.target.value })
                }}
              >
                {facilities.data.map((facility) => (
                  <option key={facility.id} value={facility.id}>
                    {facility.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              Date
              <input
                type="date"
                min={today}
                max={lastDay}
                value={date}
                onChange={(event) => {
                  if (event.target.value) {
                    choose({ date: event.target.value })
                  }
                }}
              />
            </label>
            <div className="day-buttons">
              <button
                type="button"
                className="button"
                disabled={date <= today}
                onClick={() => {
                  choose({ date: addDays(date, -1) })
                }}
              >
                Previous day
              </button>
              <button
                type="button"
                className="button"
                disabled={date >= lastDay}
                onClick={() => {
                  choose({ date: addDays(date, 1) })
                }}
              >
                Next day
              </button>
            </div>
          </div>

          <h2 className="day-heading">{formatDay(date)}</h2>
          {availability.isPending && <p role="status">Loading the timeline…</p>}
          {availability.isError && (
            <p className="notice notice-error" role="alert">
              {errorMessage(availability.error)}
            </p>
          )}
          {availability.data && availability.data.rooms.length === 0 && (
            <p className="notice">This facility has no rooms to book yet.</p>
          )}
          {availability.data &&
            availability.data.rooms.length > 0 &&
            availability.data.opensAt == null && (
              <p className="notice">This facility is closed on this day.</p>
            )}
          {availability.data?.opensAt != null && availability.data.rooms.length > 0 && (
            <>
              <TimelineLegend />
              <Timeline
                key={`${String(facilityId)}-${date}`}
                rooms={availability.data.rooms}
                onPick={(room, cellIndex) => {
                  setNotice(null)
                  setPicked({ room, cellIndex })
                }}
              />
            </>
          )}
        </>
      )}

      {picked && (
        <BookingDialog
          room={picked.room}
          startIndex={picked.cellIndex}
          onClose={() => {
            setPicked(null)
          }}
          onBooked={(booking) => {
            setPicked(null)
            setNotice({ kind: 'booked', booking })
          }}
          onSlotTaken={(message) => {
            setPicked(null)
            setNotice({ kind: 'taken', message })
          }}
        />
      )}
    </>
  )
}
