import { useState, type SyntheticEvent } from 'react'
import { ApiError, errorMessage } from '../../api/errors'
import { useSession } from '../../api/session'
import { Modal } from '../../components/Modal'
import { formatDate, formatTime } from '../../lib/time'
import {
  useCreateBooking,
  useCreateSeries,
  usePreviewSeries,
  type Booking,
  type RoomAvailability,
  type Series,
  type SeriesInput,
  type SeriesPreview,
  type SlotRequest,
} from './bookingApi'
import { endOptions } from './endOptions'

type Repeat = 'NONE' | SeriesInput['frequency']

const COUNTS = [2, 3, 4, 5, 6, 7, 8]

export function BookingDialog({
  room,
  startIndex,
  onBooked,
  onSeriesBooked,
  onSlotTaken,
  onClose,
}: {
  room: RoomAvailability
  startIndex: number
  onBooked: (booking: Booking) => void
  onSeriesBooked: (series: Series) => void
  /** `slot` is set when the person could wait for it instead. */
  onSlotTaken: (message: string, slot: SlotRequest | null) => void
  onClose: () => void
}) {
  const startAt = room.cells[startIndex]?.startAt ?? ''
  const options = endOptions(room, startIndex)
  // One hour when that fits, otherwise the longest the room allows from here.
  const [endAt, setEndAt] = useState(options[1] ?? options[0] ?? '')
  const [attendees, setAttendees] = useState(1)
  const [purpose, setPurpose] = useState('')
  const [repeat, setRepeat] = useState<Repeat>('NONE')
  const [count, setCount] = useState(4)
  const create = useCreateBooking()
  const preview = usePreviewSeries()
  const createSeries = useCreateSeries()
  const staff = useSession().user?.role !== 'STUDENT'

  const optional = purpose.trim() ? { purpose: purpose.trim() } : {}

  function seriesInput(frequency: SeriesInput['frequency']): SeriesInput {
    return {
      roomId: room.roomId,
      firstStartAt: startAt,
      firstEndAt: endAt,
      frequency,
      occurrences: count,
      attendees,
      ...optional,
    }
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    if (repeat !== 'NONE') {
      preview.mutate(seriesInput(repeat))
      return
    }
    create.mutate(
      { roomId: room.roomId, startAt, endAt, attendees, ...optional },
      {
        onSuccess: onBooked,
        onError: (error) => {
          if (error instanceof ApiError && error.code === 'SLOT_TAKEN') {
            onSlotTaken(
              error.message,
              error.waitlistable ? { roomId: room.roomId, startAt, endAt } : null,
            )
          }
        },
      },
    )
  }

  const title = `Book ${room.roomName}`

  if (preview.data && repeat !== 'NONE') {
    return (
      <Modal title={title} onClose={onClose}>
        <SeriesPreviewStep
          preview={preview.data}
          error={createSeries.isError ? errorMessage(createSeries.error) : null}
          pending={createSeries.isPending}
          onBack={() => {
            preview.reset()
            createSeries.reset()
          }}
          onConfirm={() => {
            createSeries.mutate(seriesInput(repeat), { onSuccess: onSeriesBooked })
          }}
        />
      </Modal>
    )
  }

  const failure = create.error ?? preview.error

  return (
    <Modal title={title} onClose={onClose}>
      <p className="modal-when">
        {formatDate(startAt)}, from {formatTime(startAt)} (Singapore time)
      </p>
      <form onSubmit={submit}>
        {failure && (
          <p className="form-error" role="alert">
            {errorMessage(failure)}
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
        <label className="field">
          Repeat
          <select
            value={repeat}
            onChange={(event) => {
              setRepeat(event.target.value as Repeat)
            }}
          >
            <option value="NONE">Does not repeat</option>
            <option value="WEEKLY">Every week</option>
            {staff && <option value="DAILY">Every day</option>}
          </select>
        </label>
        {repeat !== 'NONE' && (
          <label className="field">
            Number of dates
            <select
              value={count}
              onChange={(event) => {
                setCount(Number(event.target.value))
              }}
            >
              {COUNTS.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
        )}
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Keep looking
          </button>
          {repeat === 'NONE' ? (
            <button type="submit" className="button button-primary" disabled={create.isPending}>
              {create.isPending ? 'Booking…' : 'Book'}
            </button>
          ) : (
            <button type="submit" className="button button-primary" disabled={preview.isPending}>
              {preview.isPending ? 'Checking…' : 'Check dates'}
            </button>
          )}
        </div>
      </form>
    </Modal>
  )
}

/** Each date of a repeating booking, so people choose knowing which ones cannot be booked. */
function SeriesPreviewStep({
  preview,
  error,
  pending,
  onBack,
  onConfirm,
}: {
  preview: SeriesPreview
  error: string | null
  pending: boolean
  onBack: () => void
  onConfirm: () => void
}) {
  const count = preview.bookableCount
  return (
    <>
      <p className="modal-when">
        {count} of {preview.occurrences.length} dates can be booked.
      </p>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <ul className="occurrences">
        {preview.occurrences.map((occurrence) => (
          <li key={occurrence.startAt}>
            <span aria-hidden="true">{occurrence.bookable ? '✓' : '×'}</span>{' '}
            {formatDate(occurrence.startAt)}, {formatTime(occurrence.startAt)} to{' '}
            {formatTime(occurrence.endAt)}
            {occurrence.bookable ? (
              <span className="visually-hidden">, can be booked</span>
            ) : (
              <span className="occurrence-problem">
                Cannot be booked: {occurrence.detail ?? 'not available'}
              </span>
            )}
          </li>
        ))}
      </ul>
      <div className="modal-actions">
        <button type="button" className="button" onClick={onBack}>
          Back
        </button>
        <button
          type="button"
          className="button button-primary"
          disabled={count === 0 || pending}
          onClick={onConfirm}
        >
          {pending ? 'Booking…' : `Book ${String(count)} ${count === 1 ? 'date' : 'dates'}`}
        </button>
      </div>
    </>
  )
}
