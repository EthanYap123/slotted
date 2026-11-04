import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '../../api/client'
import type { components } from '../../api/schema'

export type Facility = components['schemas']['Facility']
export type FacilityAvailability = components['schemas']['FacilityAvailability']
export type RoomAvailability = components['schemas']['RoomAvailability']
export type Booking = components['schemas']['Booking']
export type BookingPage = components['schemas']['BookingPage']
export type BookingInput = components['schemas']['BookingInput']
export type BookingScope = 'upcoming' | 'past'

const PAGE_SIZE = 10

export function useFacilities() {
  return useQuery({
    queryKey: ['facilities'],
    queryFn: ({ signal }) => request<Facility[]>('GET', '/facilities', { signal }),
  })
}

export function useAvailability(facilityId: string | undefined, date: string) {
  return useQuery({
    queryKey: ['availability', facilityId, date],
    enabled: facilityId !== undefined,
    queryFn: ({ signal }) =>
      request<FacilityAvailability>(
        'GET',
        `/facilities/${String(facilityId)}/availability?date=${date}`,
        { signal },
      ),
  })
}

export function useMyBookings(scope: BookingScope, page: number) {
  return useQuery({
    queryKey: ['bookings', 'mine', scope, page],
    queryFn: ({ signal }) =>
      request<BookingPage>(
        'GET',
        `/bookings/mine?scope=${scope}&page=${String(page)}&size=${String(PAGE_SIZE)}`,
        { signal },
      ),
  })
}

/** Anything that changes a booking changes both the timeline and My bookings. */
function useRefreshBookings() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['availability'] }),
      client.invalidateQueries({ queryKey: ['bookings'] }),
    ])
}

export function useCreateBooking() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: (input: BookingInput) => request<Booking>('POST', '/bookings', { body: input }),
    // Also after an error: if the slot was just taken, the timeline must show it.
    onSettled: refresh,
  })
}

export function useCancelBooking() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      request<Booking>('POST', `/bookings/${id}/cancel`, {
        body: reason.trim() ? { reason: reason.trim() } : {},
      }),
    onSettled: refresh,
  })
}
