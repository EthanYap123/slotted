import { useState, type SyntheticEvent } from 'react'
import { errorMessage } from '../../api/errors'
import { Field } from '../../components/Field'
import { formErrors } from '../../components/formErrors'
import { Modal } from '../../components/Modal'
import { Pager } from '../../components/Pager'
import { addDays, formatDate, formatTime, isInFuture, todayInSingapore } from '../../lib/time'
import { useCancelBooking, type Booking } from '../booking/bookingApi'
import { useFacilityBookings, type BookingStatus } from './manageApi'

const STATUS_LABEL: Record<BookingStatus, string> = {
  CONFIRMED: 'Confirmed',
  CHECKED_IN: 'Checked in',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  NO_SHOW: 'No-show',
}

/** PRD MG-4: every booking in a facility for a date range, with cancelling by reason. */
export function BookingsTab({ facilityId }: { facilityId: string }) {
  const today = todayInSingapore()
  const [fromDate, setFromDate] = useState(today)
  const [toDate, setToDate] = useState(addDays(today, 6))
  const [status, setStatus] = useState<BookingStatus | ''>('')
  const [page, setPage] = useState(0)
  const [cancelling, setCancelling] = useState<Booking | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const badRange = !fromDate || !toDate || toDate < fromDate
  const bookings = useFacilityBookings({
    facilityId,
    from: `${fromDate}T00:00:00+08:00`,
    // The end date is included, so the period runs to the start of the next day.
    to: `${badRange ? fromDate : addDays(toDate, 1)}T00:00:00+08:00`,
    status,
    page,
  })

  return (
    <section aria-labelledby="bookings-heading">
      <h2 id="bookings-heading">Bookings</h2>
      <div className="toolbar">
        <Field label="From">
          {(props) => (
            <input
              {...props}
              type="date"
              value={fromDate}
              onChange={(event) => {
                setFromDate(event.target.value)
                setPage(0)
              }}
            />
          )}
        </Field>
        <Field label="To" error={badRange ? 'Must be on or after the start' : undefined}>
          {(props) => (
            <input
              {...props}
              type="date"
              value={toDate}
              onChange={(event) => {
                setToDate(event.target.value)
                setPage(0)
              }}
            />
          )}
        </Field>
        <Field label="Status">
          {(props) => (
            <select
              {...props}
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as BookingStatus | '')
                setPage(0)
              }}
            >
              <option value="">Any</option>
              {Object.entries(STATUS_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      {message && (
        <p className="notice notice-ok" role="status">
          {message}
        </p>
      )}
      {bookings.isPending && <p role="status">Loading bookings…</p>}
      {bookings.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(bookings.error)}
        </p>
      )}
      {bookings.data?.items.length === 0 && (
        <p className="notice">No bookings match these filters.</p>
      )}
      <ul className="booking-list">
        {bookings.data?.items.map((booking) => (
          <li key={booking.id} className="booking-card">
            <div className="booking-main">
              <h3>{booking.roomName}</h3>
              <p>
                {formatDate(booking.startAt)}, {formatTime(booking.startAt)} to{' '}
                {formatTime(booking.endAt)}
              </p>
              <p className="booking-detail">
                {booking.userName ?? 'Unknown person'}, {booking.attendees}{' '}
                {booking.attendees === 1 ? 'person' : 'people'}
                {booking.purpose ? `, ${booking.purpose}` : ''}
              </p>
              {booking.checkedInAt && (
                <p className="booking-detail">Checked in at {formatTime(booking.checkedInAt)}</p>
              )}
              {booking.cancelReason && (
                <p className="booking-detail">Reason: {booking.cancelReason}</p>
              )}
            </div>
            <div className="booking-side">
              <span className={`badge badge-${booking.status.toLowerCase()}`}>
                {STATUS_LABEL[booking.status]}
              </span>
              {booking.status === 'CONFIRMED' && isInFuture(booking.startAt) && (
                <button
                  type="button"
                  className="button"
                  aria-label={`Cancel ${booking.roomName}, ${formatDate(booking.startAt)}, ${formatTime(booking.startAt)}, booked by ${booking.userName ?? 'unknown'}`}
                  onClick={() => {
                    setMessage(null)
                    setCancelling(booking)
                  }}
                >
                  Cancel
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
      <Pager meta={bookings.data?.page} label="Pages of bookings" onPage={setPage} />
      {cancelling && (
        <CancelForSomeoneDialog
          booking={cancelling}
          onClose={() => {
            setCancelling(null)
          }}
          onCancelled={() => {
            setCancelling(null)
            setMessage(
              `Cancelled ${cancelling.roomName}, ${formatDate(cancelling.startAt)}, ${formatTime(cancelling.startAt)}. ${cancelling.userName ?? 'The person who booked it'} will be emailed the reason.`,
            )
          }}
        />
      )}
    </section>
  )
}

/** A manager must give a reason, which is emailed to the person (MG-4, `REASON_REQUIRED`). */
function CancelForSomeoneDialog({
  booking,
  onClose,
  onCancelled,
}: {
  booking: Booking
  onClose: () => void
  onCancelled: () => void
}) {
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const cancel = useCancelBooking()
  const errors = formErrors(cancel.error, ['reason'] as const, { REASON_REQUIRED: 'reason' })
  const reasonError = missing ? 'Enter a reason. It is sent to the person.' : errors.fields.reason

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    if (!reason.trim()) {
      setMissing(true)
      return
    }
    cancel.mutate({ id: booking.id, reason }, { onSuccess: onCancelled })
  }

  return (
    <Modal title="Cancel this booking?" onClose={onClose}>
      <p className="modal-when">
        {booking.roomName}, {formatDate(booking.startAt)}, {formatTime(booking.startAt)} to{' '}
        {formatTime(booking.endAt)}
        {booking.userName && `, booked by ${booking.userName}`}
      </p>
      <form onSubmit={submit} noValidate>
        {errors.general != null && (
          <p className="form-error" role="alert">
            {errorMessage(errors.general)}
          </p>
        )}
        <Field label="Reason" error={reasonError}>
          {(props) => (
            <input
              {...props}
              required
              maxLength={500}
              value={reason}
              onChange={(event) => {
                setReason(event.target.value)
                setMissing(false)
              }}
            />
          )}
        </Field>
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Keep booking
          </button>
          <button type="submit" className="button button-danger" disabled={cancel.isPending}>
            {cancel.isPending ? 'Cancelling…' : 'Cancel booking'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
