import { useMemo, useState, type SyntheticEvent } from 'react'
import { errorMessage } from '../../api/errors'
import { Field } from '../../components/Field'
import { formErrors } from '../../components/formErrors'
import { Modal } from '../../components/Modal'
import { addDays, formatDate, formatTime, todayInSingapore } from '../../lib/time'
import { ImpactSummary } from './ImpactSummary'
import {
  useBlackouts,
  useCreateBlackout,
  useDeleteBlackout,
  usePreviewBlackout,
  useRooms,
  type Blackout,
  type BlackoutInput,
  type ImpactPreview,
} from './manageApi'

const BLACKOUT_FIELDS = ['roomId', 'startAt', 'endAt', 'reason'] as const

/** A `datetime-local` value, read as Singapore time (ADR-0003). */
function toInstant(local: string): string {
  return `${local}:00+08:00`
}

function period(blackout: Blackout) {
  const start = `${formatDate(blackout.startAt)}, ${formatTime(blackout.startAt)}`
  const sameDay = formatDate(blackout.startAt) === formatDate(blackout.endAt)
  const end = sameDay
    ? formatTime(blackout.endAt)
    : `${formatDate(blackout.endAt)}, ${formatTime(blackout.endAt)}`
  return `${start} to ${end}`
}

/** PRD MG-3: block a room or the whole facility, previewing what it cancels first. */
export function BlackoutsTab({ facilityId }: { facilityId: string }) {
  // Blackouts that have not ended; computed once so the query key stays the same.
  const from = useMemo(() => new Date().toISOString(), [])
  const blackouts = useBlackouts(facilityId, from)
  const rooms = useRooms(facilityId)
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<Blackout | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const roomName = (id: string | null | undefined) =>
    id ? (rooms.data?.find((room) => room.id === id)?.name ?? 'A room') : 'Whole facility'

  return (
    <section aria-labelledby="blackouts-heading">
      <div className="section-head">
        <h2 id="blackouts-heading">Blackouts</h2>
        <button
          type="button"
          className="button button-primary"
          onClick={() => {
            setMessage(null)
            setAdding(true)
          }}
        >
          Add blackout
        </button>
      </div>
      <p className="page-note">Current and upcoming blackouts. All times are Singapore time.</p>
      {message && (
        <p className="notice notice-ok" role="status">
          {message}
        </p>
      )}
      {blackouts.isPending && <p role="status">Loading blackouts…</p>}
      {blackouts.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(blackouts.error)}
        </p>
      )}
      {blackouts.data?.length === 0 && <p className="notice">There are no upcoming blackouts.</p>}
      <ul className="booking-list">
        {blackouts.data?.map((blackout) => (
          <li key={blackout.id} className="booking-card">
            <div className="booking-main">
              <h3>{roomName(blackout.roomId)}</h3>
              <p>{period(blackout)}</p>
              <p className="booking-detail">Reason: {blackout.reason}</p>
            </div>
            <div className="booking-side">
              <button
                type="button"
                className="button"
                aria-label={`Remove blackout: ${roomName(blackout.roomId)}, ${period(blackout)}`}
                onClick={() => {
                  setMessage(null)
                  setRemoving(blackout)
                }}
              >
                Remove
              </button>
            </div>
          </li>
        ))}
      </ul>
      {adding && (
        <BlackoutDialog
          facilityId={facilityId}
          rooms={rooms.data?.filter((room) => room.active) ?? []}
          onClose={() => {
            setAdding(false)
          }}
          onCreated={(created) => {
            setAdding(false)
            setMessage(`Blackout added: ${roomName(created.roomId)}, ${period(created)}.`)
          }}
        />
      )}
      {removing && (
        <RemoveDialog
          blackout={removing}
          label={`${roomName(removing.roomId)}, ${period(removing)}`}
          onClose={() => {
            setRemoving(null)
          }}
          onRemoved={() => {
            setRemoving(null)
            setMessage('Blackout removed. Those times can be booked again.')
          }}
        />
      )}
    </section>
  )
}

function BlackoutDialog({
  facilityId,
  rooms,
  onClose,
  onCreated,
}: {
  facilityId: string
  rooms: { id: string; name: string }[]
  onClose: () => void
  onCreated: (blackout: Blackout) => void
}) {
  const tomorrow = addDays(todayInSingapore(), 1)
  const [roomId, setRoomId] = useState('')
  const [startAt, setStartAt] = useState(`${tomorrow}T08:00`)
  const [endAt, setEndAt] = useState(`${tomorrow}T22:00`)
  const [reason, setReason] = useState('')
  const [impact, setImpact] = useState<ImpactPreview | null>(null)
  const preview = usePreviewBlackout(facilityId)
  const create = useCreateBlackout(facilityId)
  const errors = formErrors(preview.error ?? create.error, BLACKOUT_FIELDS, {
    NOT_ALIGNED: 'startAt',
  })

  const input: BlackoutInput = {
    roomId: roomId || null,
    startAt: toInstant(startAt),
    endAt: toInstant(endAt),
    reason: reason.trim(),
  }

  function edited() {
    setImpact(null)
    preview.reset()
    create.reset()
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    if (impact) {
      create.mutate(input, { onSuccess: onCreated })
    } else {
      preview.mutate(input, { onSuccess: setImpact })
    }
  }

  const pending = preview.isPending || create.isPending
  const cancels = (impact?.affectedBookings.length ?? 0) > 0

  return (
    <Modal title="Add a blackout" onClose={onClose}>
      <form onSubmit={submit} noValidate>
        {errors.general != null && (
          <p className="form-error" role="alert">
            {errorMessage(errors.general)}
          </p>
        )}
        <Field label="Room" error={errors.fields.roomId}>
          {(props) => (
            <select
              {...props}
              value={roomId}
              onChange={(event) => {
                setRoomId(event.target.value)
                edited()
              }}
            >
              <option value="">Whole facility</option>
              {rooms.map((room) => (
                <option key={room.id} value={room.id}>
                  {room.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Starts" error={errors.fields.startAt} hint="On the hour or half hour">
          {(props) => (
            <input
              {...props}
              type="datetime-local"
              step={1800}
              required
              value={startAt}
              onChange={(event) => {
                setStartAt(event.target.value)
                edited()
              }}
            />
          )}
        </Field>
        <Field label="Ends" error={errors.fields.endAt}>
          {(props) => (
            <input
              {...props}
              type="datetime-local"
              step={1800}
              required
              value={endAt}
              onChange={(event) => {
                setEndAt(event.target.value)
                edited()
              }}
            />
          )}
        </Field>
        <Field
          label="Reason"
          error={errors.fields.reason}
          hint="Sent to people whose bookings are cancelled"
        >
          {(props) => (
            <input
              {...props}
              required
              maxLength={200}
              value={reason}
              onChange={(event) => {
                setReason(event.target.value)
                edited()
              }}
            />
          )}
        </Field>
        {impact && <ImpactSummary impact={impact} />}
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            className={`button ${cancels ? 'button-danger' : 'button-primary'}`}
            disabled={pending}
          >
            {pending
              ? 'Checking…'
              : !impact
                ? 'Check impact'
                : cancels
                  ? 'Add and cancel these bookings'
                  : 'Add blackout'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

function RemoveDialog({
  blackout,
  label,
  onClose,
  onRemoved,
}: {
  blackout: Blackout
  label: string
  onClose: () => void
  onRemoved: () => void
}) {
  const remove = useDeleteBlackout()
  return (
    <Modal title="Remove this blackout?" onClose={onClose}>
      <p className="modal-when">{label}</p>
      <p>Those times can be booked again. Bookings it cancelled are not restored.</p>
      {remove.error && (
        <p className="form-error" role="alert">
          {errorMessage(remove.error)}
        </p>
      )}
      <div className="modal-actions">
        <button type="button" className="button" onClick={onClose}>
          Keep blackout
        </button>
        <button
          type="button"
          className="button button-danger"
          disabled={remove.isPending}
          onClick={() => {
            remove.mutate(blackout.id, { onSuccess: onRemoved })
          }}
        >
          {remove.isPending ? 'Removing…' : 'Remove blackout'}
        </button>
      </div>
    </Modal>
  )
}
