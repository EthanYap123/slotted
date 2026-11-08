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
export type SeriesInput = components['schemas']['SeriesInput']
export type SeriesPreview = components['schemas']['SeriesPreview']
export type Series = components['schemas']['Series']
export type WaitlistEntry = components['schemas']['WaitlistEntry']
export type SlotRequest = components['schemas']['SlotRequest']

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

/** Anything that changes a booking changes the timeline, My bookings and the waitlist. */
function useRefreshBookings() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['availability'] }),
      client.invalidateQueries({ queryKey: ['bookings'] }),
      client.invalidateQueries({ queryKey: ['waitlist'] }),
    ])
}

export function useMyWaitlist() {
  return useQuery({
    queryKey: ['waitlist', 'mine'],
    queryFn: ({ signal }) => request<WaitlistEntry[]>('GET', '/waitlist/mine', { signal }),
  })
}

export function useJoinWaitlist() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: (slot: SlotRequest) => request<WaitlistEntry>('POST', '/waitlist', { body: slot }),
    onSettled: refresh,
  })
}

export function useLeaveWaitlist() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: (id: string) => request<undefined>('DELETE', `/waitlist/${id}`),
    onSettled: refresh,
  })
}

export function useCreateBooking() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: (input: BookingInput) => request<Booking>('POST', '/bookings', { body: input }),
    // Also after an error: if the slot was just taken, the timeline must show it.
    onSettled: refresh,
  })
}

export function usePreviewSeries() {
  return useMutation({
    mutationFn: (input: SeriesInput) =>
      request<SeriesPreview>('POST', '/booking-series/preview', { body: input }),
  })
}

export function useCreateSeries() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: (input: SeriesInput) => request<Series>('POST', '/booking-series', { body: input }),
    onSettled: refresh,
  })
}

export function useCancelSeries() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: ({ id, fromDate, reason }: { id: string; fromDate: string; reason: string }) =>
      request<Series>('POST', `/booking-series/${id}/cancel`, {
        body: { fromDate, ...(reason.trim() ? { reason: reason.trim() } : {}) },
      }),
    onSettled: refresh,
  })
}

export function useCheckIn() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: (token: string) => request<Booking>('POST', '/check-ins', { body: { token } }),
    onSettled: refresh,
  })
}

export function useEndBooking() {
  const refresh = useRefreshBookings()
  return useMutation({
    mutationFn: (id: string) => request<Booking>('POST', `/bookings/${id}/end`),
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
