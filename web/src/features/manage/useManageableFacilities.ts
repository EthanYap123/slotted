import { useSession } from '../../api/session'
import { useFacilities } from '../booking/bookingApi'

/** The facilities this person may manage: their own for a manager, every one for an admin. */
export function useManageableFacilities() {
  const user = useSession().user
  const facilities = useFacilities()
  const data =
    user?.role === 'ADMIN'
      ? facilities.data
      : facilities.data?.filter((facility) => user?.managedFacilityIds.includes(facility.id))
  return { ...facilities, data }
}
