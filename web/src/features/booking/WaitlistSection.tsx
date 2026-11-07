import { useState } from 'react'
import { errorMessage } from '../../api/errors'
import { Modal } from '../../components/Modal'
import { formatDate, formatTime } from '../../lib/time'
import {
  useLeaveWaitlist,
  useMyWaitlist,
  type BookingScope,
  type WaitlistEntry,
} from './bookingApi'

const STATUS_LABEL: Record<WaitlistEntry['status'], string> = {
  WAITING: 'Waiting',
  PROMOTED: 'Got the slot',
  EXPIRED: 'Expired',
  CANCELLED: 'Left',
}

/** A student's waitlist entries: those still waiting with upcoming bookings, the rest with past. */
export function WaitlistSection({ scope }: { scope: BookingScope }) {
  const entries = useMyWaitlist()
  const [leaving, setLeaving] = useState<WaitlistEntry | null>(null)
  const [left, setLeft] = useState<string | null>(null)

  const shown = entries.data?.filter(
    (entry) => (entry.status === 'WAITING') === (scope === 'upcoming'),
  )

  if (entries.isError) {
    return (
      <p className="notice notice-error" role="alert">
        {errorMessage(entries.error)}
      </p>
    )
  }
  if (!left && (!shown || shown.length === 0)) {
    return null
  }

  return (
    <section className="waitlist" aria-labelledby="waitlist-heading">
      <h2 id="waitlist-heading">
        {scope === 'upcoming' ? 'Waiting for a slot' : 'Past waitlists'}
      </h2>
      {left && (
        <p className="notice notice-ok" role="status">
          {left}
        </p>
      )}
      <ul className="booking-list">
        {shown?.map((entry) => (
          <li key={entry.id} className="booking-card">
            <div className="booking-main">
              <h3>{entry.roomName}</h3>
              <p>
                {formatDate(entry.startAt)}, {formatTime(entry.startAt)} to{' '}
                {formatTime(entry.endAt)}
              </p>
              {entry.position != null && (
                <p className="booking-detail">Number {entry.position} in the queue</p>
              )}
            </div>
            <div className="booking-side">
              <span className={`badge badge-${entry.status.toLowerCase()}`}>
                {STATUS_LABEL[entry.status]}
              </span>
              {entry.status === 'WAITING' && (
                <button
                  type="button"
                  className="button"
                  aria-label={`Leave the waitlist for ${entry.roomName}, ${formatDate(entry.startAt)}, ${formatTime(entry.startAt)}`}
                  onClick={() => {
                    setLeft(null)
                    setLeaving(entry)
                  }}
                >
                  Leave
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
      {leaving && (
        <LeaveDialog
          entry={leaving}
          onClose={() => {
            setLeaving(null)
          }}
          onLeft={(message) => {
            setLeaving(null)
            setLeft(message)
          }}
        />
      )}
    </section>
  )
}

function LeaveDialog({
  entry,
  onClose,
  onLeft,
}: {
  entry: WaitlistEntry
  onClose: () => void
  onLeft: (message: string) => void
}) {
  const leave = useLeaveWaitlist()
  const when = `${entry.roomName}, ${formatDate(entry.startAt)}, ${formatTime(entry.startAt)}`

  return (
    <Modal title="Leave this waitlist?" onClose={onClose}>
      <p className="modal-when">{when}</p>
      <p>You will lose your place in the queue. If you join again, you go to the back.</p>
      {leave.error && (
        <p className="form-error" role="alert">
          {errorMessage(leave.error)}
        </p>
      )}
      <div className="modal-actions">
        <button type="button" className="button" onClick={onClose}>
          Keep waiting
        </button>
        <button
          type="button"
          className="button button-danger"
          disabled={leave.isPending}
          onClick={() => {
            leave.mutate(entry.id, {
              onSuccess: () => {
                onLeft(`You left the waitlist for ${when}.`)
              },
            })
          }}
        >
          {leave.isPending ? 'Leaving…' : 'Leave the waitlist'}
        </button>
      </div>
    </Modal>
  )
}
