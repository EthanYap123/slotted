import { useState, type SyntheticEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { errorMessage } from '../../api/errors'
import { useSession } from '../../api/session'
import { Field } from '../../components/Field'
import { formErrors } from '../../components/formErrors'
import { Modal } from '../../components/Modal'
import { Pager } from '../../components/Pager'
import { formatDate, formatTime, isInFuture } from '../../lib/time'
import {
  ROLE_LABEL,
  useUpdateUser,
  useUsers,
  type Role,
  type User,
  type UserPatch,
} from './adminApi'

type Action =
  | { kind: 'role'; user: User }
  | { kind: 'disable'; user: User }
  | { kind: 'enable'; user: User }
  | { kind: 'lift'; user: User }

/** PRD AD-1: find users, change roles, disable and re-enable accounts, lift suspensions. */
export function UsersPage() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const role = (params.get('role') ?? '') as Role | ''
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const [search, setSearch] = useState(q)
  const users = useUsers(q, role, page)
  const [action, setAction] = useState<Action | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const me = useSession().user

  function show(next: { q?: string; role?: string; page?: number }) {
    const merged = { q, role, page: 0, ...next }
    const query: Record<string, string> = {}
    if (merged.q) query.q = merged.q
    if (merged.role) query.role = merged.role
    if (merged.page) query.page = String(merged.page)
    setParams(query, { replace: true })
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    setMessage(null)
    show({ q: search.trim() })
  }

  return (
    <>
      <h1>Users</h1>
      <form className="toolbar" onSubmit={submit} role="search">
        <Field label="Name or email">
          {(props) => (
            <input
              {...props}
              type="search"
              maxLength={100}
              value={search}
              onChange={(event) => {
                setSearch(event.target.value)
              }}
            />
          )}
        </Field>
        <Field label="Role">
          {(props) => (
            <select
              {...props}
              value={role}
              onChange={(event) => {
                setMessage(null)
                show({ q: search.trim(), role: event.target.value })
              }}
            >
              <option value="">Any</option>
              {Object.entries(ROLE_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <button type="submit" className="button">
          Search
        </button>
      </form>
      {message && (
        <p className="notice notice-ok" role="status">
          {message}
        </p>
      )}
      {users.isPending && <p role="status">Loading users…</p>}
      {users.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(users.error)}
        </p>
      )}
      {users.data?.items.length === 0 && <p className="notice">No users match.</p>}
      <ul className="booking-list">
        {users.data?.items.map((user) => {
          const suspended = user.suspendedUntil != null && isInFuture(user.suspendedUntil)
          const isMe = user.id === me?.id
          return (
            <li key={user.id} className="booking-card">
              <div className="booking-main">
                <h2 className="card-title">
                  {user.fullName}
                  {isMe && ' (you)'}
                </h2>
                <p className="booking-detail break-anywhere">{user.email}</p>
                <p className="booking-detail">
                  {ROLE_LABEL[user.role]}, joined {formatDate(user.createdAt)}
                </p>
                {suspended && user.suspendedUntil && (
                  <p className="booking-detail">
                    Suspended for no-shows until {formatDate(user.suspendedUntil)},{' '}
                    {formatTime(user.suspendedUntil)}
                  </p>
                )}
                <p className="booking-detail">
                  <Link to={`/admin/audit?entityType=USER&entityId=${user.id}`}>
                    History<span className="visually-hidden"> of {user.fullName}</span>
                  </Link>
                  {' · '}
                  <Link to={`/admin/audit?actorId=${user.id}`}>
                    Changes made<span className="visually-hidden"> by {user.fullName}</span>
                  </Link>
                </p>
              </div>
              <div className="booking-side">
                <span
                  className={`badge ${user.status === 'ACTIVE' ? 'badge-confirmed' : 'badge-no_show'}`}
                >
                  {user.status === 'ACTIVE' ? 'Active' : 'Disabled'}
                </span>
                <div className="actions">
                  <button
                    type="button"
                    className="button"
                    aria-label={`Change role of ${user.fullName}`}
                    onClick={() => {
                      setMessage(null)
                      setAction({ kind: 'role', user })
                    }}
                  >
                    Change role
                  </button>
                  {user.status === 'ACTIVE' ? (
                    <button
                      type="button"
                      className="button"
                      aria-label={`Disable ${user.fullName}`}
                      onClick={() => {
                        setMessage(null)
                        setAction({ kind: 'disable', user })
                      }}
                    >
                      Disable
                    </button>
                  ) : (
                    <button
                      type="button"
                      className="button"
                      aria-label={`Re-enable ${user.fullName}`}
                      onClick={() => {
                        setMessage(null)
                        setAction({ kind: 'enable', user })
                      }}
                    >
                      Re-enable
                    </button>
                  )}
                  {suspended && (
                    <button
                      type="button"
                      className="button"
                      aria-label={`Lift suspension of ${user.fullName}`}
                      onClick={() => {
                        setMessage(null)
                        setAction({ kind: 'lift', user })
                      }}
                    >
                      Lift suspension
                    </button>
                  )}
                </div>
              </div>
            </li>
          )
        })}
      </ul>
      <Pager
        meta={users.data?.page}
        label="Pages of users"
        onPage={(next) => {
          show({ q, role, page: next })
        }}
      />
      {action?.kind === 'role' && (
        <RoleDialog
          user={action.user}
          onClose={() => {
            setAction(null)
          }}
          onDone={(text) => {
            setAction(null)
            setMessage(text)
          }}
        />
      )}
      {action && action.kind !== 'role' && (
        <ConfirmUserDialog
          action={action}
          onClose={() => {
            setAction(null)
          }}
          onDone={(text) => {
            setAction(null)
            setMessage(text)
          }}
        />
      )}
    </>
  )
}

function RoleDialog({
  user,
  onClose,
  onDone,
}: {
  user: User
  onClose: () => void
  onDone: (message: string) => void
}) {
  const [role, setRole] = useState<Role>(user.role)
  const update = useUpdateUser()
  const errors = formErrors(update.error, ['role'] as const, { LAST_ADMIN: 'role' })

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    if (role === user.role) {
      onClose()
      return
    }
    update.mutate(
      { id: user.id, changes: { role } },
      {
        onSuccess: () => {
          onDone(
            `${user.fullName} is now ${role === 'ADMIN' ? 'an' : 'a'} ${ROLE_LABEL[role].toLowerCase()}. They are signed out and must sign in again.${role === 'MANAGER' ? ' Assign their facilities on the Facilities page.' : ''}`,
          )
        },
      },
    )
  }

  return (
    <Modal title={`Change role of ${user.fullName}`} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        {errors.general != null && (
          <p className="form-error" role="alert">
            {errorMessage(errors.general)}
          </p>
        )}
        <Field
          label="Role"
          error={errors.fields.role}
          hint="Changing a role signs the person out on every device."
        >
          {(props) => (
            <select
              {...props}
              value={role}
              onChange={(event) => {
                setRole(event.target.value as Role)
                update.reset()
              }}
            >
              {Object.entries(ROLE_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <div className="modal-actions">
          <button type="button" className="button" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="button button-primary" disabled={update.isPending}>
            {update.isPending ? 'Saving…' : 'Save role'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

const CONFIRM: Record<
  Exclude<Action['kind'], 'role'>,
  { title: string; body: string; button: string; changes: UserPatch; done: string; danger: boolean }
> = {
  disable: {
    title: 'Disable this account?',
    body: 'They are signed out at once and cannot sign in. Their future bookings are cancelled and they are emailed.',
    button: 'Disable account',
    changes: { status: 'DISABLED' },
    done: 'is disabled.',
    danger: true,
  },
  enable: {
    title: 'Re-enable this account?',
    body: 'They can sign in again. Bookings cancelled when the account was disabled are not restored.',
    button: 'Re-enable account',
    changes: { status: 'ACTIVE' },
    done: 'can sign in again.',
    danger: false,
  },
  lift: {
    title: 'Lift this suspension?',
    body: 'Earlier no-shows stop counting, so they can book again straight away.',
    button: 'Lift suspension',
    changes: { liftSuspension: true },
    done: 'can book again.',
    danger: false,
  },
}

function ConfirmUserDialog({
  action,
  onClose,
  onDone,
}: {
  action: Exclude<Action, { kind: 'role' }>
  onClose: () => void
  onDone: (message: string) => void
}) {
  const update = useUpdateUser()
  const text = CONFIRM[action.kind]
  return (
    <Modal title={text.title} onClose={onClose}>
      <p className="modal-when">
        {action.user.fullName}, {action.user.email}
      </p>
      <p>{text.body}</p>
      {update.error && (
        <p className="form-error" role="alert">
          {errorMessage(update.error)}
        </p>
      )}
      <div className="modal-actions">
        <button type="button" className="button" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className={`button ${text.danger ? 'button-danger' : 'button-primary'}`}
          disabled={update.isPending}
          onClick={() => {
            update.mutate(
              { id: action.user.id, changes: text.changes },
              {
                onSuccess: () => {
                  onDone(`${action.user.fullName} ${text.done}`)
                },
              },
            )
          }}
        >
          {update.isPending ? 'Saving…' : text.button}
        </button>
      </div>
    </Modal>
  )
}
