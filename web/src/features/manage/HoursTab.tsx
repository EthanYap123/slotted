import { useState, type SyntheticEvent } from 'react'
import { errorMessage } from '../../api/errors'
import type { Facility } from '../booking/bookingApi'
import { ImpactSummary } from './ImpactSummary'
import {
  useReplaceOpeningHours,
  type DayOfWeek,
  type ImpactPreview,
  type OpeningHours,
} from './manageApi'

const DAYS: { id: DayOfWeek; label: string }[] = [
  { id: 'MONDAY', label: 'Monday' },
  { id: 'TUESDAY', label: 'Tuesday' },
  { id: 'WEDNESDAY', label: 'Wednesday' },
  { id: 'THURSDAY', label: 'Thursday' },
  { id: 'FRIDAY', label: 'Friday' },
  { id: 'SATURDAY', label: 'Saturday' },
  { id: 'SUNDAY', label: 'Sunday' },
]

/** Every half hour from 00:00 to 23:30, the times the API accepts. */
const TIMES = Array.from({ length: 48 }, (_, i) => {
  const hour = String(Math.floor(i / 2)).padStart(2, '0')
  return `${hour}:${i % 2 ? '30' : '00'}`
})

interface DayHours {
  open: boolean
  opensAt: string
  closesAt: string
}

type Week = Record<DayOfWeek, DayHours>

function weekFrom(hours: OpeningHours[]): Week {
  const week = {} as Week
  for (const day of DAYS) {
    const found = hours.find((h) => h.dayOfWeek === day.id)
    week[day.id] = found
      ? { open: true, opensAt: found.opensAt.slice(0, 5), closesAt: found.closesAt.slice(0, 5) }
      : { open: false, opensAt: '08:00', closesAt: '22:00' }
  }
  return week
}

function hoursFrom(week: Week): OpeningHours[] {
  return DAYS.filter((day) => week[day.id].open).map((day) => ({
    dayOfWeek: day.id,
    opensAt: week[day.id].opensAt,
    closesAt: week[day.id].closesAt,
  }))
}

/** PRD MG-2: weekly opening hours, previewing the bookings a change would cancel first. */
export function HoursTab({ facility }: { facility: Facility }) {
  const [week, setWeek] = useState(() => weekFrom(facility.openingHours))
  const [preview, setPreview] = useState<ImpactPreview | null>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const replace = useReplaceOpeningHours(facility.id)
  const invalid = DAYS.filter((d) => week[d.id].open && week[d.id].closesAt <= week[d.id].opensAt)

  function change(day: DayOfWeek, next: Partial<DayHours>) {
    setWeek((current) => ({ ...current, [day]: { ...current[day], ...next } }))
    setPreview(null)
    setSaved(null)
    replace.reset()
  }

  function check(event: SyntheticEvent) {
    event.preventDefault()
    if (invalid.length > 0) return
    replace.mutate({ hours: hoursFrom(week), dryRun: true }, { onSuccess: setPreview })
  }

  function save() {
    replace.mutate(
      { hours: hoursFrom(week), dryRun: false },
      {
        onSuccess: (result) => {
          setPreview(null)
          const count = result.affectedBookings.length
          setSaved(
            count === 0
              ? 'Opening hours saved.'
              : `Opening hours saved. ${count === 1 ? '1 booking was' : `${String(count)} bookings were`} cancelled and the people who made them emailed.`,
          )
        },
      },
    )
  }

  return (
    <section aria-labelledby="hours-heading">
      <h2 id="hours-heading">Opening hours</h2>
      <p className="page-note">
        Singapore time. Bookings that fall outside new hours are cancelled, so you see which ones
        before saving.
      </p>
      {saved && (
        <p className="notice notice-ok" role="status">
          {saved}
        </p>
      )}
      <form onSubmit={check}>
        {replace.error && (
          <p className="form-error" role="alert">
            {errorMessage(replace.error)}
          </p>
        )}
        <div className="table-scroll hours-scroll">
          <table className="data-table hours-table">
            <caption className="visually-hidden">Opening hours by day</caption>
            <thead>
              <tr>
                <th scope="col">Day</th>
                <th scope="col">Open</th>
                <th scope="col">Opens</th>
                <th scope="col">Closes</th>
              </tr>
            </thead>
            <tbody>
              {DAYS.map((day) => {
                const hours = week[day.id]
                const wrong = invalid.some((d) => d.id === day.id)
                const errorId = `hours-${day.id}-error`
                return (
                  <tr key={day.id}>
                    <th scope="row">{day.label}</th>
                    <td>
                      <input
                        type="checkbox"
                        aria-label={`Open on ${day.label}`}
                        checked={hours.open}
                        onChange={(event) => {
                          change(day.id, { open: event.target.checked })
                        }}
                      />
                    </td>
                    <td>
                      <select
                        aria-label={`${day.label} opens at`}
                        disabled={!hours.open}
                        value={hours.opensAt}
                        onChange={(event) => {
                          change(day.id, { opensAt: event.target.value })
                        }}
                      >
                        {TIMES.map((t) => (
                          <option key={t}>{t}</option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <select
                        aria-label={`${day.label} closes at`}
                        disabled={!hours.open}
                        value={hours.closesAt}
                        aria-invalid={wrong}
                        aria-describedby={wrong ? errorId : undefined}
                        onChange={(event) => {
                          change(day.id, { closesAt: event.target.value })
                        }}
                      >
                        {TIMES.map((t) => (
                          <option key={t}>{t}</option>
                        ))}
                      </select>
                      {wrong && (
                        <span id={errorId} className="field-error">
                          Must be after opening
                        </span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        {!preview && (
          <button
            type="submit"
            className="button button-primary"
            disabled={replace.isPending || invalid.length > 0}
          >
            {replace.isPending ? 'Checking…' : 'Check and save'}
          </button>
        )}
      </form>
      {preview && (
        <div className="confirm-box">
          <ImpactSummary impact={preview} />
          <div className="modal-actions">
            <button
              type="button"
              className="button"
              onClick={() => {
                setPreview(null)
              }}
            >
              Keep editing
            </button>
            <button
              type="button"
              className={`button ${preview.affectedBookings.length > 0 ? 'button-danger' : 'button-primary'}`}
              disabled={replace.isPending}
              onClick={save}
            >
              {replace.isPending
                ? 'Saving…'
                : preview.affectedBookings.length > 0
                  ? 'Save and cancel these bookings'
                  : 'Save opening hours'}
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
