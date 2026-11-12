import { useState, type SyntheticEvent } from 'react'
import { Link } from 'react-router'
import { errorMessage } from '../../api/errors'
import { Field } from '../../components/Field'
import { formErrors } from '../../components/formErrors'
import { Modal } from '../../components/Modal'
import {
  ROOM_TYPE_LABEL,
  useCreateRoom,
  useRooms,
  useUpdateRoom,
  type Room,
  type RoomType,
} from './manageApi'

const LENGTHS = [30, 60, 90, 120, 150, 180, 210, 240]
const ROOM_FIELDS = ['name', 'type', 'capacity', 'maxBookingMinutes'] as const

function lengthLabel(minutes: number) {
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${String(rest)} minutes`
  const h = hours === 1 ? '1 hour' : `${String(hours)} hours`
  return rest ? `${h} ${String(rest)} minutes` : h
}

/** PRD MG-1 and MG-5: rooms in one facility, with their QR codes. */
export function RoomsTab({ facilityId }: { facilityId: string }) {
  const rooms = useRooms(facilityId)
  const [editing, setEditing] = useState<Room | 'new' | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const toggle = useUpdateRoom()

  function setActive(room: Room, active: boolean) {
    setMessage(null)
    toggle.mutate(
      { room, changes: { active } },
      {
        onSuccess: () => {
          setMessage(
            active
              ? `${room.name} can be booked again.`
              : `${room.name} is deactivated. It can no longer be booked; existing bookings are kept.`,
          )
        },
      },
    )
  }

  return (
    <section aria-labelledby="rooms-heading">
      <div className="section-head">
        <h2 id="rooms-heading">Rooms</h2>
        <button
          type="button"
          className="button button-primary"
          onClick={() => {
            setMessage(null)
            setEditing('new')
          }}
        >
          Add room
        </button>
      </div>
      {message && (
        <p className="notice notice-ok" role="status">
          {message}
        </p>
      )}
      {toggle.error && (
        <p className="notice notice-error" role="alert">
          {errorMessage(toggle.error)}
        </p>
      )}
      {rooms.isPending && <p role="status">Loading rooms…</p>}
      {rooms.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(rooms.error)}
        </p>
      )}
      {rooms.data?.length === 0 && <p className="notice">This facility has no rooms yet.</p>}
      <ul className="booking-list">
        {rooms.data?.map((room) => (
          <li key={room.id} className="booking-card">
            <div className="booking-main">
              <h3>{room.name}</h3>
              <p className="booking-detail">
                {ROOM_TYPE_LABEL[room.type]}, up to {room.capacity}{' '}
                {room.capacity === 1 ? 'person' : 'people'}, bookings up to{' '}
                {lengthLabel(room.maxBookingMinutes)}
              </p>
            </div>
            <div className="booking-side">
              <span className={`badge ${room.active ? 'badge-confirmed' : ''}`}>
                {room.active ? 'Active' : 'Inactive'}
              </span>
              <div className="actions">
                <button
                  type="button"
                  className="button"
                  aria-label={`Edit ${room.name}`}
                  onClick={() => {
                    setMessage(null)
                    setEditing(room)
                  }}
                >
                  Edit
                </button>
                <Link
                  to={`/manage/rooms/${room.id}/qr`}
                  className="button"
                  aria-label={`QR code for ${room.name}`}
                >
                  QR code
                </Link>
                <button
                  type="button"
                  className="button"
                  disabled={toggle.isPending}
                  aria-label={`${room.active ? 'Deactivate' : 'Reactivate'} ${room.name}`}
                  onClick={() => {
                    setActive(room, !room.active)
                  }}
                >
                  {room.active ? 'Deactivate' : 'Reactivate'}
                </button>
              </div>
            </div>
          </li>
        ))}
      </ul>
      {editing && (
        <RoomDialog
          facilityId={facilityId}
          room={editing === 'new' ? null : editing}
          onClose={() => {
            setEditing(null)
          }}
          onSaved={(saved) => {
            setEditing(null)
            setMessage(`Saved ${saved.name}.`)
          }}
        />
      )}
    </section>
  )
}

function RoomDialog({
  facilityId,
  room,
  onClose,
  onSaved,
}: {
  facilityId: string
  room: Room | null
  onClose: () => void
  onSaved: (room: Room) => void
}) {
  const [name, setName] = useState(room?.name ?? '')
  const [type, setType] = useState<RoomType>(room?.type ?? 'STUDY_ROOM')
  const [capacity, setCapacity] = useState(String(room?.capacity ?? 4))
  const [maxMinutes, setMaxMinutes] = useState(room?.maxBookingMinutes ?? 120)
  const create = useCreateRoom(facilityId)
  const update = useUpdateRoom()
  const pending = create.isPending || update.isPending
  const errors = formErrors(create.error ?? update.error, ROOM_FIELDS, { NAME_TAKEN: 'name' })

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    const input = { name, type, capacity: Number(capacity), maxBookingMinutes: maxMinutes }
    if (room) {
      update.mutate({ room, changes: input }, { onSuccess: onSaved })
    } else {
      create.mutate(input, { onSuccess: onSaved })
    }
  }

  return (
    <Modal title={room ? `Edit ${room.name}` : 'Add a room'} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        {errors.general != null && (
          <p className="form-error" role="alert">
            {errorMessage(errors.general)}
          </p>
        )}
        <Field label="Name" error={errors.fields.name}>
          {(props) => (
            <input
              {...props}
              required
              maxLength={100}
              value={name}
              onChange={(event) => {
                setName(event.target.value)
              }}
            />
          )}
        </Field>
        <Field label="Type" error={errors.fields.type}>
          {(props) => (
            <select
              {...props}
              value={type}
              onChange={(event) => {
                setType(event.target.value as RoomType)
              }}
            >
              {Object.entries(ROOM_TYPE_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Capacity (people)" error={errors.fields.capacity}>
          {(props) => (
            <input
              {...props}
              type="number"
              inputMode="numeric"
              min={1}
              max={500}
              required
              value={capacity}
              onChange={(event) => {
                setCapacity(event.target.value)
              }}
            />
          )}
        </Field>
        <Field label="Longest booking" error={errors.fields.maxBookingMinutes}>
          {(props) => (
            <select
              {...props}
              value={maxMinutes}
              onChange={(event) => {
                setMaxMinutes(Number(event.target.value))
              }}
            >
              {LENGTHS.map((minutes) => (
                <option key={minutes} value={minutes}>
                  {lengthLabel(minutes)}
                </option>
              ))}
            </select>
          )}
        </Field>
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="button button-primary" disabled={pending}>
            {pending ? 'Saving…' : room ? 'Save changes' : 'Add room'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
