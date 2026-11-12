import { useState, type SyntheticEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { errorMessage } from '../../api/errors'
import { Field } from '../../components/Field'
import { formErrors } from '../../components/formErrors'
import { Pager } from '../../components/Pager'
import { formatDate, formatTime } from '../../lib/time'
import { useAuditLog, type AuditEntityType, type AuditFilter, type AuditRecord } from './adminApi'

const ENTITY_LABEL: Record<AuditEntityType, string> = {
  BOOKING: 'Booking',
  WAITLIST_ENTRY: 'Waitlist entry',
  ROOM: 'Room',
  FACILITY: 'Facility',
  OPENING_HOURS: 'Opening hours',
  BLACKOUT: 'Blackout',
  USER: 'User',
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** "BOOKING_ENDED_EARLY" becomes "Booking ended early". */
function actionLabel(action: AuditRecord['action']) {
  const words = action.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function show(value: unknown): string {
  if (value === null || value === undefined) return 'none'
  if (typeof value === 'string') return value
  return JSON.stringify(value)
}

/** The fields a change touched, with their old and new values. */
function changedFields(record: AuditRecord) {
  const before = (record.before ?? {}) as Record<string, unknown>
  const after = (record.after ?? {}) as Record<string, unknown>
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])]
  return keys
    .filter((key) => JSON.stringify(before[key]) !== JSON.stringify(after[key]))
    .map((key) => ({
      key,
      before: record.before ? show(before[key]) : null,
      after: record.after ? show(after[key]) : null,
    }))
}

function filterFrom(params: URLSearchParams): AuditFilter {
  return {
    entityType: (params.get('entityType') ?? '') as AuditEntityType | '',
    entityId: params.get('entityId') ?? '',
    actorId: params.get('actorId') ?? '',
    fromDate: params.get('from') ?? '',
    toDate: params.get('to') ?? '',
    page: Math.max(0, Number(params.get('page') ?? 0) || 0),
  }
}

/** PRD AD-3: who changed what. Filtered by one entity, its history reads oldest first. */
export function AuditLogPage() {
  const [params, setParams] = useSearchParams()
  const filter = filterFrom(params)
  const [draft, setDraft] = useState(filter)
  // Following a link to another item's history changes the address, so the form follows it.
  const [draftFor, setDraftFor] = useState(params.toString())
  if (draftFor !== params.toString()) {
    setDraftFor(params.toString())
    setDraft(filter)
  }
  const [invalid, setInvalid] = useState<Partial<Record<'entityId' | 'actorId', string>>>({})
  const audit = useAuditLog(filter)
  const errors = formErrors(audit.error, ['entityId', 'actorId', 'from', 'to'] as const)
  const oneEntity = Boolean(filter.entityId)

  function apply(next: AuditFilter) {
    const query: Record<string, string> = {}
    if (next.entityType) query.entityType = next.entityType
    if (next.entityId) query.entityId = next.entityId
    if (next.actorId) query.actorId = next.actorId
    if (next.fromDate) query.from = next.fromDate
    if (next.toDate) query.to = next.toDate
    if (next.page) query.page = String(next.page)
    setParams(query, { replace: true })
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault()
    const entityId = draft.entityId.trim()
    const actorId = draft.actorId.trim()
    const problems = {
      ...(entityId && !UUID.test(entityId) ? { entityId: 'Paste a full ID' } : {}),
      ...(actorId && !UUID.test(actorId) ? { actorId: 'Paste a full ID' } : {}),
    }
    setInvalid(problems)
    if (Object.keys(problems).length === 0) {
      apply({ ...draft, entityId, actorId, page: 0 })
    }
  }

  function clear() {
    const empty = {
      entityType: '',
      entityId: '',
      actorId: '',
      fromDate: '',
      toDate: '',
      page: 0,
    } as const
    setDraft(empty)
    setInvalid({})
    apply(empty)
  }

  return (
    <>
      <h1>Audit log</h1>
      <p className="page-note">
        Every change to bookings, waitlists, rooms, facilities, hours, blackouts and users. Records
        are kept for 180 days. Times are Singapore time.
      </p>
      <form className="filters" onSubmit={submit} noValidate>
        <Field label="Type">
          {(props) => (
            <select
              {...props}
              value={draft.entityType}
              onChange={(event) => {
                setDraft({ ...draft, entityType: event.target.value as AuditEntityType | '' })
              }}
            >
              <option value="">Any</option>
              {Object.entries(ENTITY_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Item ID" error={invalid.entityId ?? errors.fields.entityId}>
          {(props) => (
            <input
              {...props}
              value={draft.entityId}
              spellCheck={false}
              onChange={(event) => {
                setDraft({ ...draft, entityId: event.target.value })
              }}
            />
          )}
        </Field>
        <Field label="Changed by (user ID)" error={invalid.actorId ?? errors.fields.actorId}>
          {(props) => (
            <input
              {...props}
              value={draft.actorId}
              spellCheck={false}
              onChange={(event) => {
                setDraft({ ...draft, actorId: event.target.value })
              }}
            />
          )}
        </Field>
        <Field label="From" error={errors.fields.from}>
          {(props) => (
            <input
              {...props}
              type="date"
              value={draft.fromDate}
              onChange={(event) => {
                setDraft({ ...draft, fromDate: event.target.value })
              }}
            />
          )}
        </Field>
        <Field label="To" error={errors.fields.to}>
          {(props) => (
            <input
              {...props}
              type="date"
              value={draft.toDate}
              onChange={(event) => {
                setDraft({ ...draft, toDate: event.target.value })
              }}
            />
          )}
        </Field>
        <div className="filter-actions">
          <button type="submit" className="button button-primary">
            Show records
          </button>
          <button type="button" className="button" onClick={clear}>
            Clear
          </button>
        </div>
      </form>

      {oneEntity && <p className="page-note">History of one item, oldest change first.</p>}
      {audit.isPending && <p role="status">Loading the audit log…</p>}
      {errors.general != null && (
        <p className="notice notice-error" role="alert">
          {errorMessage(errors.general)}
        </p>
      )}
      {audit.data?.items.length === 0 && <p className="notice">No records match.</p>}
      <ol className="audit-list">
        {audit.data?.items.map((record) => (
          <AuditEntry key={record.id} record={record} />
        ))}
      </ol>
      <Pager
        meta={audit.data?.page}
        label="Pages of the audit log"
        onPage={(page) => {
          apply({ ...filter, page })
        }}
      />
    </>
  )
}

function AuditEntry({ record }: { record: AuditRecord }) {
  const changes = changedFields(record)
  const system = record.actorId === null
  return (
    <li className="audit-entry">
      <div className="audit-head">
        <strong>{actionLabel(record.action)}</strong>
        <span className="muted">
          {formatDate(record.occurredAt)}, {formatTime(record.occurredAt)}
        </span>
      </div>
      <p className="booking-detail">
        By{' '}
        {system ? (
          <span className="badge">System</span>
        ) : (
          <Link to={`/admin/audit?actorId=${String(record.actorId)}`}>
            {record.actorName ?? 'Unknown user'}
          </Link>
        )}
        {' · '}
        {ENTITY_LABEL[record.entityType]}{' '}
        <Link
          to={`/admin/audit?entityType=${record.entityType}&entityId=${record.entityId}`}
          className="break-anywhere"
        >
          {record.entityId}
        </Link>
      </p>
      {changes.length > 0 && (
        <details>
          <summary>
            {changes.length === 1 ? '1 field' : `${String(changes.length)} fields`} changed
          </summary>
          <dl className="audit-changes">
            {changes.map((change) => (
              <div key={change.key}>
                <dt>{change.key}</dt>
                <dd className="break-anywhere">
                  {change.before === null
                    ? change.after
                    : change.after === null
                      ? `${change.before} (removed)`
                      : `${change.before} → ${change.after}`}
                </dd>
              </div>
            ))}
          </dl>
        </details>
      )}
      {record.requestId && (
        <p className="booking-detail break-anywhere">Request ID: {record.requestId}</p>
      )}
    </li>
  )
}
