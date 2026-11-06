import { useState, type SyntheticEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { errorMessage } from '../../api/errors'
import { Modal } from '../../components/Modal'
import { dateInSingapore, formatDate, formatTime, isInFuture, isWithinHours } from '../../lib/time'
import {
  useCancelBooking,
  useCancelSeries,
  useMyBookings,
  type Booking,
  type BookingScope,
} from './bookingApi'

/** Closer to the start than this counts as a late cancellation (BR-13); the API records it. */
const LATE_CANCELLATION_HOURS = 2

const STATUS_LABEL: Record<Booking['status'], string> = {
  CONFIRMED: 'Confirmed',
  CHECKED_IN: 'Checked in',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  NO_SHOW: 'No-show',
}

interface Cancelling {
  booking: Booking
  /** Also cancel the later dates of its series. */
  andLater: boolean
}

export function MyBookingsPage() {
  const [params, setParams] = useSearchParams()
  const scope: BookingScope = params.get('scope') === 'past' ? 'past' : 'upcoming'
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const bookings = useMyBookings(scope, page)
  const [cancelling, setCancelling] = useState<Cancelling | null>(null)
  const [cancelled, setCancelled] = useState<string | null>(null)

  function show(nextScope: BookingScope, nextPage = 0) {
    setCancelled(null)
    setParams({ scope: nextScope, page: String(nextPage) }, { replace: true })
  }

  const meta = bookings.data?.page

  return (
    <>
      <h1>My bookings</h1>
      <p className="page-note">All times are Singapore time.</p>

      <div className="segmented" role="group" aria-label="Which bookings to show">
        <button
          type="button"
          aria-pressed={scope === 'upcoming'}
          onClick={() => {
            show('upcoming')
          }}
        >
          Upcoming
        </button>
        <button
          type="button"
          aria-pressed={scope === 'past'}
          onClick={() => {
            show('past')
          }}
        >
          Past
        </button>
      </div>

      {cancelled && (
        <p className="notice notice-ok" role="status">
          {cancelled}
        </p>
      )}

      {bookings.isPending && <p role="status">Loading your bookings…</p>}
      {bookings.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(bookings.error)}
        </p>
      )}
      {bookings.data?.items.length === 0 && (
        <p className="notice">
          {scope === 'upcoming' ? (
            <>
              You have no upcoming bookings. <Link to="/">Book a room</Link>
            </>
          ) : (
            'You have no past bookings.'
          )}
        </p>
      )}

      <ul className="booking-list">
        {bookings.data?.items.map((booking) => (
          <BookingCard
            key={booking.id}
            booking={booking}
            onCancel={(andLater) => {
              setCancelled(null)
              setCancelling({ booking, andLater })
            }}
          />
        ))}
      </ul>

      {meta && meta.totalPages > 1 && (
        <nav className="pager" aria-label="Pages of bookings">
          <button
            type="button"
            className="button"
            disabled={meta.number <= 0}
            onClick={() => {
              show(scope, meta.number - 1)
            }}
          >
            Previous
          </button>
          <span>
            Page {meta.number + 1} of {meta.totalPages}
          </span>
          <button
            type="button"
            className="button"
            disabled={meta.number >= meta.totalPages - 1}
            onClick={() => {
              show(scope, meta.number + 1)
            }}
          >
            Next
          </button>
        </nav>
      )}

      {cancelling && (
        <CancelDialog
          booking={cancelling.booking}
          andLater={cancelling.andLater}
          onClose={() => {
            setCancelling(null)
          }}
          onCancelled={(message) => {
            setCancelling(null)
            setCancelled(message)
          }}
        />
      )}
    </>
  )
}

function BookingCard({
  booking,
  onCancel,
}: {
  booking: Booking
  onCancel: (andLater: boolean) => void
}) {
  const canCancel = booking.status === 'CONFIRMED' && isInFuture(booking.startAt)
  return (
    <li className="booking-card">
      <div className="booking-main">
        <h2>{booking.roomName}</h2>
        <p className="booking-facility">{booking.facilityName}</p>
        <p>
          {formatDate(booking.startAt)}, {formatTime(booking.startAt)} to{' '}
          {formatTime(booking.endAt)}
        </p>
        <p className="booking-detail">
          {booking.attendees} {booking.attendees === 1 ? 'person' : 'people'}
          {booking.purpose ? `, ${booking.purpose}` : ''}
        </p>
        {booking.seriesId && <p className="booking-detail">Repeating booking</p>}
        {booking.cancelReason && <p className="booking-detail">Reason: {booking.cancelReason}</p>}
        {booking.lateCancellation && <p className="booking-detail">Late cancellation</p>}
      </div>
      <div className="booking-side">
        <span className={`badge badge-${booking.status.toLowerCase()}`}>
          {STATUS_LABEL[booking.status]}
        </span>
        {canCancel && (
          <button
            type="button"
            className="button"
            aria-label={`Cancel ${booking.roomName}, ${formatDate(booking.startAt)}, ${formatTime(booking.startAt)}`}
            onClick={() => {
              onCancel(false)
            }}
          >
            Cancel
          </button>
        )}
        {canCancel && booking.seriesId && (
          <button
            type="button"
            className="button"
            aria-label={`Cancel ${booking.roomName}, ${formatDate(booking.startAt)}, and later dates`}
            onClick={() => {
              onCancel(true)
            }}
          >
            Cancel this and later
          </button>
        )}
      </div>
    </li>
  )
}

function CancelDialog({
  booking,
  andLater,
  onClose,
  onCancelled,
}: {
  booking: Booking
  andLater: boolean
  onClose: () => void
  onCancelled: (message: string) => void
}) {
  const [reason, setReason] = useState('')
  const cancelOne = useCancelBooking()
  const cancelSeries = useCancelSeries()
  const failure = cancelOne.error ?? cancelSeries.error
  const pending = cancelOne.isPending || cancelSeries.isPending
  const late = isWithinHours(booking.startAt, LATE_CANCELLATION_HOURS)

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    const when = `${booking.roomName}, ${formatDate(booking.startAt)}, ${formatTime(booking.startAt)}`
    if (andLater && booking.seriesId) {
      cancelSeries.mutate(
        { id: booking.seriesId, fromDate: dateInSingapore(booking.startAt), reason },
        {
          onSuccess: () => {
            onCancelled(`Cancelled ${when}, and the later dates in this series.`)
          },
        },
      )
      return
    }
    cancelOne.mutate(
      { id: booking.id, reason },
      {
        onSuccess: (result) => {
          onCancelled(
            `Cancelled ${when}.${result.lateCancellation ? ' This was recorded as a late cancellation.' : ''}`,
          )
        },
      },
    )
  }

  return (
    <Modal
      title={andLater ? 'Cancel this and later dates?' : 'Cancel this booking?'}
      onClose={onClose}
    >
      <p className="modal-when">
        {booking.roomName}, {formatDate(booking.startAt)}, {formatTime(booking.startAt)} to{' '}
        {formatTime(booking.endAt)}
      </p>
      {late && (
        <p className="notice notice-warn">
          This starts in under {LATE_CANCELLATION_HOURS} hours, so it will be recorded as a late
          cancellation. There is no penalty, and someone else can use the room.
        </p>
      )}
      <form onSubmit={submit}>
        {failure && (
          <p className="form-error" role="alert">
            {errorMessage(failure)}
          </p>
        )}
        <label className="field">
          Reason (optional)
          <input
            maxLength={500}
            value={reason}
            onChange={(event) => {
              setReason(event.target.value)
            }}
          />
        </label>
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Keep booking
          </button>
          <button type="submit" className="button button-danger" disabled={pending}>
            {pending ? 'Cancelling…' : andLater ? 'Cancel these dates' : 'Cancel booking'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
