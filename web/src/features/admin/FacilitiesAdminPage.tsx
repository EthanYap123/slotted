import { useState, type SyntheticEvent } from 'react'
import { Link } from 'react-router'
import { errorMessage } from '../../api/errors'
import { Field } from '../../components/Field'
import { formErrors } from '../../components/formErrors'
import { Modal } from '../../components/Modal'
import { useFacilities } from '../booking/bookingApi'
import {
  useCreateFacility,
  useFacilityManagers,
  useReplaceManagers,
  useUpdateFacility,
  useUsers,
  type Facility,
} from './adminApi'

const FACILITY_FIELDS = ['name', 'description', 'location'] as const

/** PRD AD-2: create facilities, edit their details and choose who manages them. */
export function FacilitiesAdminPage() {
  const facilities = useFacilities()
  const [editing, setEditing] = useState<Facility | 'new' | null>(null)
  const [assigning, setAssigning] = useState<Facility | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  return (
    <>
      <div className="section-head">
        <h1>Facilities</h1>
        <button
          type="button"
          className="button button-primary"
          onClick={() => {
            setMessage(null)
            setEditing('new')
          }}
        >
          Create facility
        </button>
      </div>
      {message && (
        <p className="notice notice-ok" role="status">
          {message}
        </p>
      )}
      {facilities.isPending && <p role="status">Loading facilities…</p>}
      {facilities.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(facilities.error)}
        </p>
      )}
      {facilities.data?.length === 0 && <p className="notice">There are no facilities yet.</p>}
      <ul className="booking-list">
        {facilities.data?.map((facility) => (
          <li key={facility.id} className="booking-card">
            <div className="booking-main">
              <h2 className="card-title">{facility.name}</h2>
              {facility.location && <p className="booking-detail">{facility.location}</p>}
              {facility.description && <p className="booking-detail">{facility.description}</p>}
              <Managers facilityId={facility.id} />
            </div>
            <div className="booking-side">
              <div className="actions">
                <Link
                  to={`/manage/${facility.id}`}
                  className="button"
                  aria-label={`Rooms and hours of ${facility.name}`}
                >
                  Rooms and hours
                </Link>
                <button
                  type="button"
                  className="button"
                  aria-label={`Edit details of ${facility.name}`}
                  onClick={() => {
                    setMessage(null)
                    setEditing(facility)
                  }}
                >
                  Edit details
                </button>
                <button
                  type="button"
                  className="button"
                  aria-label={`Choose managers of ${facility.name}`}
                  onClick={() => {
                    setMessage(null)
                    setAssigning(facility)
                  }}
                >
                  Managers
                </button>
              </div>
            </div>
          </li>
        ))}
      </ul>
      {editing && (
        <FacilityDialog
          facility={editing === 'new' ? null : editing}
          onClose={() => {
            setEditing(null)
          }}
          onSaved={(saved, created) => {
            setEditing(null)
            setMessage(
              created
                ? `Created ${saved.name}. Next, add its rooms and opening hours, and choose its managers.`
                : `Saved ${saved.name}.`,
            )
          }}
        />
      )}
      {assigning && (
        <ManagersDialog
          facility={assigning}
          onClose={() => {
            setAssigning(null)
          }}
          onSaved={() => {
            setAssigning(null)
            setMessage(
              `Managers of ${assigning.name} saved. Newly assigned managers see it after they next sign in.`,
            )
          }}
        />
      )}
    </>
  )
}

function Managers({ facilityId }: { facilityId: string }) {
  const managers = useFacilityManagers(facilityId)
  if (managers.isPending) return <p className="booking-detail">Loading managers…</p>
  if (managers.isError) return <p className="booking-detail">{errorMessage(managers.error)}</p>
  return (
    <p className="booking-detail">
      Managers:{' '}
      {managers.data.length === 0 ? 'none yet' : managers.data.map((m) => m.fullName).join(', ')}
    </p>
  )
}

function FacilityDialog({
  facility,
  onClose,
  onSaved,
}: {
  facility: Facility | null
  onClose: () => void
  onSaved: (facility: Facility, created: boolean) => void
}) {
  const [name, setName] = useState(facility?.name ?? '')
  const [description, setDescription] = useState(facility?.description ?? '')
  const [location, setLocation] = useState(facility?.location ?? '')
  const create = useCreateFacility()
  const update = useUpdateFacility()
  const pending = create.isPending || update.isPending
  const errors = formErrors(create.error ?? update.error, FACILITY_FIELDS, { NAME_TAKEN: 'name' })

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    // An empty string clears an optional field when editing (FacilityPatch).
    const input = { name, description: description.trim(), location: location.trim() }
    if (facility) {
      update.mutate(
        { facility, changes: input },
        {
          onSuccess: (saved) => {
            onSaved(saved, false)
          },
        },
      )
    } else {
      create.mutate(
        {
          name,
          ...(input.description ? { description: input.description } : {}),
          ...(input.location ? { location: input.location } : {}),
        },
        {
          onSuccess: (saved) => {
            onSaved(saved, true)
          },
        },
      )
    }
  }

  return (
    <Modal title={facility ? `Edit ${facility.name}` : 'Create a facility'} onClose={onClose}>
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
        <Field label="Location (optional)" error={errors.fields.location}>
          {(props) => (
            <input
              {...props}
              maxLength={200}
              value={location}
              onChange={(event) => {
                setLocation(event.target.value)
              }}
            />
          )}
        </Field>
        <Field label="Description (optional)" error={errors.fields.description}>
          {(props) => (
            <textarea
              {...props}
              rows={3}
              maxLength={1000}
              value={description}
              onChange={(event) => {
                setDescription(event.target.value)
              }}
            />
          )}
        </Field>
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="button button-primary" disabled={pending}>
            {pending ? 'Saving…' : facility ? 'Save changes' : 'Create facility'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

/** Only users with the Manager role can be chosen; the API refuses anyone else (NOT_A_MANAGER). */
function ManagersDialog({
  facility,
  onClose,
  onSaved,
}: {
  facility: Facility
  onClose: () => void
  onSaved: () => void
}) {
  const current = useFacilityManagers(facility.id)
  const candidates = useUsers('', 'MANAGER', 0, 100)
  const [chosen, setChosen] = useState<Set<string> | null>(null)
  const replace = useReplaceManagers(facility.id)
  const selected = chosen ?? new Set(current.data?.map((m) => m.id) ?? [])
  const loading = current.isPending || candidates.isPending
  const loadError = current.error ?? candidates.error

  function toggle(id: string, on: boolean) {
    const next = new Set(selected)
    if (on) next.add(id)
    else next.delete(id)
    setChosen(next)
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    // Someone whose role changed since being assigned is no longer a manager, so is left out.
    const managerIds = new Set(candidates.data?.items.map((user) => user.id))
    replace.mutate(
      [...selected].filter((id) => managerIds.has(id)),
      { onSuccess: onSaved },
    )
  }

  return (
    <Modal title={`Managers of ${facility.name}`} onClose={onClose}>
      <form onSubmit={submit}>
        {loading && <p role="status">Loading managers…</p>}
        {loadError && (
          <p className="form-error" role="alert">
            {errorMessage(loadError)}
          </p>
        )}
        {replace.error && (
          <p className="form-error" role="alert">
            {errorMessage(replace.error)}
          </p>
        )}
        {candidates.data?.items.length === 0 && (
          <p className="notice">
            No one has the Manager role yet. Change a user&apos;s role on the{' '}
            <Link to="/admin/users">Users</Link> page first.
          </p>
        )}
        {!loading && (candidates.data?.items.length ?? 0) > 0 && (
          <fieldset className="checkbox-list">
            <legend>Choose managers</legend>
            {candidates.data?.items.map((user) => (
              <label key={user.id}>
                <input
                  type="checkbox"
                  checked={selected.has(user.id)}
                  onChange={(event) => {
                    toggle(user.id, event.target.checked)
                  }}
                />
                <span>
                  {user.fullName} <span className="muted break-anywhere">{user.email}</span>
                </span>
              </label>
            ))}
          </fieldset>
        )}
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            className="button button-primary"
            disabled={loading || Boolean(loadError) || replace.isPending}
          >
            {replace.isPending ? 'Saving…' : 'Save managers'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
