import { Link, Navigate, useParams, useSearchParams } from 'react-router'
import { errorMessage } from '../../api/errors'
import { useSession } from '../../api/session'
import { BlackoutsTab } from './BlackoutsTab'
import { BookingsTab } from './BookingsTab'
import { HoursTab } from './HoursTab'
import { useFacility } from './manageApi'
import { RoomsTab } from './RoomsTab'
import { useManageableFacilities } from './useManageableFacilities'

const TABS = [
  { id: 'rooms', label: 'Rooms' },
  { id: 'hours', label: 'Opening hours' },
  { id: 'blackouts', label: 'Blackouts' },
  { id: 'bookings', label: 'Bookings' },
] as const

type Tab = (typeof TABS)[number]['id']

/** `/manage`: choose a facility, or go straight to it when there is only one. */
export function ManageHomePage() {
  const facilities = useManageableFacilities()
  const isAdmin = useSession().user?.role === 'ADMIN'

  const only = facilities.data?.length === 1 ? facilities.data[0] : undefined
  if (only) {
    return <Navigate to={`/manage/${only.id}`} replace />
  }

  return (
    <>
      <h1>Manage facilities</h1>
      {facilities.isPending && <p role="status">Loading facilities…</p>}
      {facilities.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(facilities.error)}
        </p>
      )}
      {facilities.data?.length === 0 && (
        <p className="notice">
          {isAdmin ? (
            <>
              There are no facilities yet. <Link to="/admin/facilities">Create one</Link>
            </>
          ) : (
            'You do not manage any facilities yet. Ask an admin to assign you one, then sign in again.'
          )}
        </p>
      )}
      <ul className="link-list">
        {facilities.data?.map((facility) => (
          <li key={facility.id}>
            <Link to={`/manage/${facility.id}`}>{facility.name}</Link>
            {facility.location && <span className="muted"> · {facility.location}</span>}
          </li>
        ))}
      </ul>
    </>
  )
}

/** `/manage/:facilityId`: one facility's rooms, hours, blackouts and bookings, in tabs. */
export function ManageFacilityPage() {
  const { facilityId = '' } = useParams()
  const [params] = useSearchParams()
  const requested = params.get('tab')
  const tab: Tab = TABS.some((t) => t.id === requested) ? (requested as Tab) : 'rooms'
  const facility = useFacility(facilityId)
  const manageable = useManageableFacilities()
  const several = (manageable.data?.length ?? 0) > 1

  return (
    <>
      {several && (
        <p className="breadcrumb">
          <Link to="/manage">All facilities</Link>
        </p>
      )}
      <h1>{facility.data?.name ?? 'Facility'}</h1>
      {facility.isError && (
        <p className="notice notice-error" role="alert">
          {errorMessage(facility.error)}
        </p>
      )}
      <nav className="tabs" aria-label="Facility sections">
        {TABS.map((t) => (
          <Link
            key={t.id}
            to={`?tab=${t.id}`}
            replace
            aria-current={t.id === tab ? 'page' : undefined}
          >
            {t.label}
          </Link>
        ))}
      </nav>
      {tab === 'rooms' && <RoomsTab facilityId={facilityId} />}
      {tab === 'hours' && facility.data && <HoursTab facility={facility.data} />}
      {tab === 'blackouts' && <BlackoutsTab facilityId={facilityId} />}
      {tab === 'bookings' && <BookingsTab facilityId={facilityId} />}
    </>
  )
}
