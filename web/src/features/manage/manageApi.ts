import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '../../api/client'
import type { components } from '../../api/schema'

export type Room = components['schemas']['Room']
export type RoomInput = components['schemas']['RoomInput']
export type RoomPatch = components['schemas']['RoomPatch']
export type RoomType = components['schemas']['RoomType']
export type OpeningHours = components['schemas']['OpeningHours']
export type DayOfWeek = OpeningHours['dayOfWeek']
export type Blackout = components['schemas']['Blackout']
export type BlackoutInput = components['schemas']['BlackoutInput']
export type ImpactPreview = components['schemas']['ImpactPreview']
export type BookingStatus = components['schemas']['BookingStatus']

export const ROOM_TYPE_LABEL: Record<RoomType, string> = {
  STUDY_ROOM: 'Study room',
  SPORTS_COURT: 'Sports court',
  MEETING_ROOM: 'Meeting room',
}

export function useFacility(facilityId: string) {
  return useQuery({
    queryKey: ['facilities', facilityId],
    queryFn: ({ signal }) =>
      request<components['schemas']['Facility']>('GET', `/facilities/${facilityId}`, { signal }),
  })
}

export function useRooms(facilityId: string) {
  return useQuery({
    queryKey: ['rooms', facilityId],
    queryFn: ({ signal }) =>
      request<Room[]>('GET', `/facilities/${facilityId}/rooms?includeInactive=true`, { signal }),
  })
}

export function useRoom(roomId: string) {
  return useQuery({
    queryKey: ['room', roomId],
    queryFn: ({ signal }) => request<Room>('GET', `/rooms/${roomId}`, { signal }),
  })
}

/** Rooms show up in the booking timeline too, so that is refreshed with them. */
function useRefreshRooms() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['rooms'] }),
      client.invalidateQueries({ queryKey: ['room'] }),
      client.invalidateQueries({ queryKey: ['availability'] }),
    ])
}

export function useCreateRoom(facilityId: string) {
  const refresh = useRefreshRooms()
  return useMutation({
    mutationFn: (input: RoomInput) =>
      request<Room>('POST', `/facilities/${facilityId}/rooms`, { body: input }),
    onSuccess: refresh,
  })
}

/** Sends the version last read, so an edit made in the meantime is refused, not overwritten. */
export function useUpdateRoom() {
  const refresh = useRefreshRooms()
  return useMutation({
    mutationFn: ({ room, changes }: { room: Room; changes: RoomPatch }) =>
      request<Room>('PATCH', `/rooms/${room.id}`, {
        body: changes,
        headers: room.version === undefined ? {} : { 'If-Match': String(room.version) },
      }),
    onSettled: refresh,
  })
}

/** The QR code as SVG text. It holds the room's secret token, so it is never cached. */
export function useRoomQr(roomId: string) {
  return useQuery({
    queryKey: ['room-qr', roomId],
    gcTime: 0,
    queryFn: ({ signal }) =>
      request<string>('GET', `/rooms/${roomId}/check-in-qr`, { signal, responseType: 'text' }),
  })
}

export function useRotateQr(roomId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => request<undefined>('POST', `/rooms/${roomId}/check-in-token/rotate`),
    onSuccess: () => client.invalidateQueries({ queryKey: ['room-qr', roomId] }),
  })
}

/** Changing hours or blackouts can cancel bookings, so everything that shows them is refreshed. */
function useRefreshSchedule() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['facilities'] }),
      client.invalidateQueries({ queryKey: ['blackouts'] }),
      client.invalidateQueries({ queryKey: ['availability'] }),
      client.invalidateQueries({ queryKey: ['bookings'] }),
      client.invalidateQueries({ queryKey: ['waitlist'] }),
    ])
}

/** With `dryRun`, only reports which bookings would be cancelled (BR-31). */
export function useReplaceOpeningHours(facilityId: string) {
  const refresh = useRefreshSchedule()
  return useMutation({
    mutationFn: ({ hours, dryRun }: { hours: OpeningHours[]; dryRun: boolean }) =>
      request<ImpactPreview>(
        'PUT',
        `/facilities/${facilityId}/opening-hours?dryRun=${String(dryRun)}`,
        { body: hours },
      ),
    onSuccess: async (_result, { dryRun }) => {
      if (!dryRun) {
        await refresh()
      }
    },
  })
}

export function useBlackouts(facilityId: string, from: string) {
  return useQuery({
    queryKey: ['blackouts', facilityId, from],
    queryFn: ({ signal }) =>
      request<Blackout[]>(
        'GET',
        `/facilities/${facilityId}/blackouts?from=${encodeURIComponent(from)}`,
        { signal },
      ),
  })
}

export function usePreviewBlackout(facilityId: string) {
  return useMutation({
    mutationFn: (input: BlackoutInput) =>
      request<ImpactPreview>('POST', `/facilities/${facilityId}/blackouts?dryRun=true`, {
        body: input,
      }),
  })
}

export function useCreateBlackout(facilityId: string) {
  const refresh = useRefreshSchedule()
  return useMutation({
    mutationFn: (input: BlackoutInput) =>
      request<Blackout>('POST', `/facilities/${facilityId}/blackouts`, { body: input }),
    onSuccess: refresh,
  })
}

export function useDeleteBlackout() {
  const refresh = useRefreshSchedule()
  return useMutation({
    mutationFn: (id: string) => request<undefined>('DELETE', `/blackouts/${id}`),
    onSettled: refresh,
  })
}

export interface BookingFilter {
  facilityId: string
  from: string
  to: string
  status: BookingStatus | ''
  page: number
}

export const BOOKINGS_PAGE_SIZE = 20

export function useFacilityBookings(filter: BookingFilter) {
  const params = new URLSearchParams({
    facilityId: filter.facilityId,
    from: filter.from,
    to: filter.to,
    page: String(filter.page),
    size: String(BOOKINGS_PAGE_SIZE),
  })
  if (filter.status) {
    params.set('status', filter.status)
  }
  return useQuery({
    queryKey: ['bookings', 'facility', filter],
    queryFn: ({ signal }) =>
      request<components['schemas']['BookingPage']>('GET', `/bookings?${params.toString()}`, {
        signal,
      }),
  })
}
