import { useEffect, useRef } from 'react'
import { Link, useSearchParams } from 'react-router'
import { errorMessage } from '../../api/errors'
import { formatDate, formatTime } from '../../lib/time'
import { useCheckIn } from './bookingApi'

/**
 * Where a room's QR code leads: `/check-in?token=...` checks the person in straight away (PRD
 * ST-8). Opened without a token, for example from My bookings, it explains how to check in.
 */
export function CheckInPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const checkIn = useCheckIn()
  const { mutate } = checkIn
  // Once per visit, even when React runs effects twice in development.
  const sent = useRef(false)

  useEffect(() => {
    if (token && !sent.current) {
      sent.current = true
      mutate(token)
    }
  }, [token, mutate])

  return (
    <>
      <h1>Check in</h1>
      {!token && (
        <>
          <p>
            Scan the QR code at the room you booked, using your phone&apos;s camera. Check-in opens
            10 minutes before your booking starts and closes 15 minutes after. If you do not check
            in by then, the room is released for others.
          </p>
          <p>
            <Link to="/bookings">Back to my bookings</Link>
          </p>
        </>
      )}
      {token && checkIn.isPending && <p role="status">Checking you in…</p>}
      {checkIn.data && (
        <div className="notice notice-ok" role="status">
          <p>
            You are checked in to {checkIn.data.roomName}, {formatDate(checkIn.data.startAt)},{' '}
            {formatTime(checkIn.data.startAt)} to {formatTime(checkIn.data.endAt)}.
          </p>
          <p>
            If you leave early, end the booking in <Link to="/bookings">My bookings</Link> so
            someone else can use the room.
          </p>
        </div>
      )}
      {checkIn.error && (
        <div className="notice notice-error" role="alert">
          <p>{errorMessage(checkIn.error)}</p>
          <p>
            <Link to="/bookings">See my bookings</Link>
          </p>
        </div>
      )}
    </>
  )
}
