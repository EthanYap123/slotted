import { useState, type SyntheticEvent } from 'react'
import { ApiError, errorMessage } from '../../api/errors'
import { Modal } from '../../components/Modal'
import { formatDate, formatTime } from '../../lib/time'
import { useCreateBooking, type Booking, type RoomAvailability } from './bookingApi'
import { endOptions } from './endOptions'

export function BookingDialog({
  room,
  startIndex,
  onBooked,
  onSlotTaken,
  onClose,
}: {
  room: RoomAvailability
  startIndex: number
  onBooked: (booking: Booking) => void
  onSlotTaken: (message: string) => void
  onClose: () => void
}) {
  const startAt = room.cells[startIndex]?.startAt ?? ''
  const options = endOptions(room, startIndex)
  // One hour when that fits, otherwise the longest the room allows from here.
  const [endAt, setEndAt] = useState(options[1] ?? options[0] ?? '')
  const [attendees, setAttendees] = useState(1)
  const [purpose, setPurpose] = useState('')
  const create = useCreateBooking()

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    create.mutate(
      {
        roomId: room.roomId,
        startAt,
        endAt,
        attendees,
        ...(purpose.trim() ? { purpose: purpose.trim() } : {}),
      },
      {
        onSuccess: onBooked,
        onError: (error) => {
          if (error instanceof ApiError && error.code === 'SLOT_TAKEN') {
            onSlotTaken(error.message)
          }
        },
      },
    )
  }

  return (
    <Modal title={`Book ${room.roomName}`} onClose={onClose}>
      <p className="modal-when">
        {formatDate(startAt)}, from {formatTime(startAt)} (Singapore time)
      </p>
      <form onSubmit={submit}>
        {create.isError && (
          <p className="form-error" role="alert">
            {errorMessage(create.error)}
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
        <label className="field">
          Number of people
          <input
            type="number"
            min={1}
            max={room.capacity}
            required
            value={attendees}
            onChange={(event) => {
              setAttendees(Number(event.target.value))
            }}
          />
        </label>
        <label className="field">
          Purpose (optional)
          <input
            maxLength={200}
            value={purpose}
            onChange={(event) => {
              setPurpose(event.target.value)
            }}
          />
        </label>
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Keep looking
          </button>
          <button type="submit" className="button button-primary" disabled={create.isPending}>
            {create.isPending ? 'Booking…' : 'Book'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
