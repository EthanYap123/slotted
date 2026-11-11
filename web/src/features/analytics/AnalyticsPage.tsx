import { useQuery } from '@tanstack/react-query'
import { useState, type CSSProperties } from 'react'
import { request } from '../../api/client'
import { errorMessage } from '../../api/errors'
import type { components } from '../../api/schema'
import { Field } from '../../components/Field'
import { formErrors } from '../../components/formErrors'
import { addDays, todayInSingapore } from '../../lib/time'
import { useManageableFacilities } from '../manage/useManageableFacilities'

type Report = components['schemas']['UtilisationReport']
type RoomRow = Report['rooms'][number]

/** The API allows at most this many days in one report. */
const MAX_DAYS = 92
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

function useUtilisation(facilityId: string, fromDate: string, toDate: string, enabled: boolean) {
  const params = new URLSearchParams({ fromDate, toDate })
  if (facilityId) params.set('facilityId', facilityId)
  return useQuery({
    queryKey: ['utilisation', facilityId, fromDate, toDate],
    enabled,
    queryFn: ({ signal }) =>
      request<Report>('GET', `/analytics/utilisation?${params.toString()}`, { signal }),
  })
}

function percent(share: number) {
  return `${String(Math.round(share * 100))}%`
}

function hours(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

function daysBetween(from: string, to: string) {
  return (Date.parse(to) - Date.parse(from)) / 86_400_000 + 1
}

/** PRD AD-4: how rooms are used. Managers only see the facilities they manage. */
export function AnalyticsPage() {
  const today = todayInSingapore()
  const [fromDate, setFromDate] = useState(addDays(today, -27))
  const [toDate, setToDate] = useState(today)
  const [facilityId, setFacilityId] = useState('')
  const facilities = useManageableFacilities()
  const span = fromDate && toDate ? daysBetween(fromDate, toDate) : 0
  const rangeError =
    !fromDate || !toDate
      ? 'Choose both dates'
      : span < 1
        ? 'Must be on or after the start'
        : span > MAX_DAYS
          ? `At most ${String(MAX_DAYS)} days at a time`
          : undefined
  const report = useUtilisation(facilityId, fromDate, toDate, rangeError === undefined)
  const errors = formErrors(report.error, ['fromDate', 'toDate', 'facilityId'] as const)

  return (
    <>
      <h1>Analytics</h1>
      <p className="page-note">
        Booked time counts bookings that were checked in, or confirmed and not yet a no-show.
        Blackouts are left out of open time.
      </p>
      <div className="toolbar">
        <Field label="Facility" error={errors.fields.facilityId}>
          {(props) => (
            <select
              {...props}
              value={facilityId}
              onChange={(event) => {
                setFacilityId(event.target.value)
              }}
            >
              <option value="">All my facilities</option>
              {facilities.data?.map((facility) => (
                <option key={facility.id} value={facility.id}>
                  {facility.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="From" error={errors.fields.fromDate}>
          {(props) => (
            <input
              {...props}
              type="date"
              value={fromDate}
              onChange={(event) => {
                setFromDate(event.target.value)
              }}
            />
          )}
        </Field>
        <Field label="To" error={rangeError ?? errors.fields.toDate}>
          {(props) => (
            <input
              {...props}
              type="date"
              value={toDate}
              onChange={(event) => {
                setToDate(event.target.value)
              }}
            />
          )}
        </Field>
      </div>

      {rangeError === undefined && report.isPending && (
        <p role="status">Working out the figures…</p>
      )}
      {errors.general != null && (
        <p className="notice notice-error" role="alert">
          {errorMessage(errors.general)}
        </p>
      )}
      {report.data && report.data.rooms.length === 0 && (
        <p className="notice">There are no rooms to report on for this period.</p>
      )}
      {report.data && report.data.rooms.length > 0 && (
        <>
          <Totals rooms={report.data.rooms} />
          <RoomTable rooms={report.data.rooms} showFacility={!facilityId} />
          <Heatmap cells={report.data.heatmap} />
        </>
      )}
    </>
  )
}

function Totals({ rooms }: { rooms: RoomRow[] }) {
  const open = rooms.reduce((sum, r) => sum + r.openHours, 0)
  const booked = rooms.reduce((sum, r) => sum + r.bookedHours, 0)
  const bookings = rooms.reduce((sum, r) => sum + r.bookings, 0)
  const cancellations = rooms.reduce((sum, r) => sum + r.cancellations, 0)
  return (
    <dl className="stat-row">
      <div className="stat">
        <dt>Utilisation</dt>
        <dd>{open > 0 ? percent(booked / open) : 'n/a'}</dd>
      </div>
      <div className="stat">
        <dt>Booked hours</dt>
        <dd>{hours(booked)}</dd>
      </div>
      <div className="stat">
        <dt>Bookings</dt>
        <dd>{bookings}</dd>
      </div>
      <div className="stat">
        <dt>Cancellations</dt>
        <dd>{cancellations}</dd>
      </div>
    </dl>
  )
}

function RoomTable({ rooms, showFacility }: { rooms: RoomRow[]; showFacility: boolean }) {
  return (
    <section aria-labelledby="rooms-report-heading">
      <h2 id="rooms-report-heading">By room</h2>
      <div className="table-scroll">
        <table className="data-table" aria-labelledby="rooms-report-heading">
          <thead>
            <tr>
              <th scope="col">Room</th>
              <th scope="col">Utilisation</th>
              <th scope="col">Booked / open hours</th>
              <th scope="col">Bookings</th>
              <th scope="col">Checked in</th>
              <th scope="col">No-shows</th>
              <th scope="col">Cancelled</th>
              <th scope="col">Late cancels</th>
            </tr>
          </thead>
          <tbody>
            {rooms.map((room) => (
              <tr key={room.roomId}>
                <th scope="row">
                  {room.roomName}
                  {showFacility && <span className="cell-note">{room.facilityName}</span>}
                </th>
                <td>
                  <span className="meter" aria-hidden="true">
                    <span style={{ width: percent(room.utilisation) }} />
                  </span>
                  {percent(room.utilisation)}
                </td>
                <td>
                  {hours(room.bookedHours)} / {hours(room.openHours)}
                </td>
                <td>{room.bookings}</td>
                <td>{percent(room.checkInRate)}</td>
                <td>{percent(room.noShowRate)}</td>
                <td>{room.cancellations}</td>
                <td>{room.lateCancellations}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

/** Utilisation by weekday and hour, all rooms together. Hours that were never open stay blank. */
function Heatmap({ cells }: { cells: Report['heatmap'] }) {
  if (cells.length === 0) return null
  const hoursShown = [...new Set(cells.map((c) => c.hour))].sort((a, b) => a - b)
  const first = hoursShown[0] ?? 0
  const last = hoursShown[hoursShown.length - 1] ?? first
  const range = Array.from({ length: last - first + 1 }, (_, i) => first + i)
  const value = (day: number, hour: number) =>
    cells.find((c) => c.dayOfWeek === day && c.hour === hour)?.utilisation

  return (
    <section aria-labelledby="heatmap-heading">
      <h2 id="heatmap-heading">Busiest times</h2>
      <p className="page-note">
        Share of open room time that was booked, by weekday and hour. Darker is busier.
      </p>
      <div className="table-scroll">
        <table className="heatmap" aria-labelledby="heatmap-heading">
          <thead>
            <tr>
              <td />
              {range.map((hour) => (
                <th key={hour} scope="col">
                  {String(hour).padStart(2, '0')}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {DAYS.map((label, index) => (
              <tr key={label}>
                <th scope="row">{label}</th>
                {range.map((hour) => {
                  const share = value(index + 1, hour)
                  return (
                    <td
                      key={hour}
                      className={share === undefined ? 'heat-closed' : 'heat'}
                      style={
                        share === undefined
                          ? undefined
                          : ({ '--heat': String(Math.round(share * 100)) } as CSSProperties)
                      }
                      title={
                        share === undefined
                          ? `${label} ${String(hour)}:00, closed`
                          : `${label} ${String(hour)}:00, ${percent(share)}`
                      }
                    >
                      <span className="visually-hidden">
                        {share === undefined ? 'closed' : percent(share)}
                      </span>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
