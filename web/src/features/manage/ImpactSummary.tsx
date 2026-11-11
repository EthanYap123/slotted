import { formatDate, formatTime } from '../../lib/time'
import type { ImpactPreview } from './manageApi'

/** What a change to opening hours or a blackout would cancel (BR-31), shown before confirming. */
export function ImpactSummary({ impact }: { impact: ImpactPreview }) {
  const bookings = impact.affectedBookings
  const waiting = impact.affectedWaitlistEntries
  if (bookings.length === 0 && waiting === 0) {
    return (
      <p className="notice notice-ok" role="status">
        No bookings or waitlist entries are affected.
      </p>
    )
  }
  return (
    <div className="notice notice-warn" role="status">
      <p>
        {bookings.length === 1 ? '1 booking' : `${String(bookings.length)} bookings`} will be
        cancelled and the people who made them emailed.
        {waiting > 0 &&
          ` ${waiting === 1 ? '1 waitlist entry' : `${String(waiting)} waitlist entries`} will expire.`}
      </p>
      {bookings.length > 0 && (
        <ul className="impact-list">
          {bookings.map((booking) => (
            <li key={booking.id}>
              {booking.roomName}, {formatDate(booking.startAt)}, {formatTime(booking.startAt)} to{' '}
              {formatTime(booking.endAt)}
              {booking.userName && ` (${booking.userName})`}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
