import { useState, type SyntheticEvent } from 'react'
import { errorMessage } from '../../api/errors'
import { Modal } from '../../components/Modal'
import { formatDate, formatTime } from '../../lib/time'
import { useJoinWaitlist, type RoomAvailability, type WaitlistEntry } from './bookingApi'
import { waitlistEndOptions } from './endOptions'

/** Choosing how long a slot to wait for, starting from a booked cell (BR-24). */
export function WaitlistDialog({
  room,
  startIndex,
  onJoined,
  onClose,
}: {
  room: RoomAvailability
  startIndex: number
  onJoined: (entry: WaitlistEntry) => void
  onClose: () => void
}) {
  const startAt = room.cells[startIndex]?.startAt ?? ''
  const options = waitlistEndOptions(room, startIndex)
  // One hour when that fits, otherwise the longest the room allows from here.
  const [endAt, setEndAt] = useState(options[1] ?? options[0] ?? '')
  const join = useJoinWaitlist()

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    join.mutate({ roomId: room.roomId, startAt, endAt }, { onSuccess: onJoined })
  }

  return (
    <Modal title={`Join the waitlist for ${room.roomName}`} onClose={onClose}>
      <p className="modal-when">
        {formatDate(startAt)}, from {formatTime(startAt)} (Singapore time)
      </p>
      <p>
        This time is taken. If all of it frees up, the booking is yours automatically, unless you
        have reached your booking limit by then.
      </p>
      <form onSubmit={submit}>
        {join.error && (
          <p className="form-error" role="alert">
            {errorMessage(join.error)}
          </p>
        )}
        <label className="field">
          Until
          <select
            value={endAt}
            onChange={(event) => {
              setEndAt(event.target.value)
            }}
          >
            {options.map((option) => (
              <option key={option} value={option}>
                {formatTime(option)}
              </option>
            ))}
          </select>
        </label>
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Keep looking
          </button>
          <button type="submit" className="button button-primary" disabled={join.isPending}>
            {join.isPending ? 'Joining…' : 'Join the waitlist'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
