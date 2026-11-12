import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '../../api/client'
import type { components } from '../../api/schema'
import { addDays } from '../../lib/time'

export type User = components['schemas']['User']
export type UserPage = components['schemas']['UserPage']
export type UserPatch = components['schemas']['UserPatch']
export type Role = components['schemas']['Role']
export type Facility = components['schemas']['Facility']
export type FacilityInput = components['schemas']['FacilityInput']
export type FacilityManager = components['schemas']['FacilityManager']
export type AuditRecord = components['schemas']['AuditRecord']
export type AuditPage = components['schemas']['AuditPage']
export type AuditEntityType = AuditRecord['entityType']

export const USERS_PAGE_SIZE = 20
export const AUDIT_PAGE_SIZE = 25

export const ROLE_LABEL: Record<Role, string> = {
  STUDENT: 'Student',
  MANAGER: 'Manager',
  ADMIN: 'Admin',
}

export function useUsers(q: string, role: Role | '', page: number, size = USERS_PAGE_SIZE) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (q.trim()) params.set('q', q.trim())
  if (role) params.set('role', role)
  return useQuery({
    queryKey: ['users', q.trim(), role, page, size],
    queryFn: ({ signal }) =>
      request<UserPage>('GET', `/admin/users?${params.toString()}`, { signal }),
  })
}

export function useUpdateUser() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, changes }: { id: string; changes: UserPatch }) =>
      request<User>('PATCH', `/admin/users/${id}`, { body: changes }),
    onSettled: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: ['users'] }),
        client.invalidateQueries({ queryKey: ['managers'] }),
      ]),
  })
}

export function useCreateFacility() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: FacilityInput) => request<Facility>('POST', '/facilities', { body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['facilities'] }),
  })
}

export function useUpdateFacility() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ facility, changes }: { facility: Facility; changes: FacilityInput }) =>
      request<Facility>('PATCH', `/facilities/${facility.id}`, {
        body: changes,
        headers: facility.version === undefined ? {} : { 'If-Match': String(facility.version) },
      }),
    onSettled: () => client.invalidateQueries({ queryKey: ['facilities'] }),
  })
}

export function useFacilityManagers(facilityId: string) {
  return useQuery({
    queryKey: ['managers', facilityId],
    queryFn: ({ signal }) =>
      request<FacilityManager[]>('GET', `/facilities/${facilityId}/managers`, { signal }),
  })
}

export function useReplaceManagers(facilityId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (userIds: string[]) =>
      request<undefined>('PUT', `/facilities/${facilityId}/managers`, { body: { userIds } }),
    onSettled: () => client.invalidateQueries({ queryKey: ['managers', facilityId] }),
  })
}

export interface AuditFilter {
  entityType: AuditEntityType | ''
  entityId: string
  actorId: string
  fromDate: string
  toDate: string
  page: number
}

export function useAuditLog(filter: AuditFilter) {
  const params = new URLSearchParams({ page: String(filter.page), size: String(AUDIT_PAGE_SIZE) })
  if (filter.entityType) params.set('entityType', filter.entityType)
  if (filter.entityId) params.set('entityId', filter.entityId)
  if (filter.actorId) params.set('actorId', filter.actorId)
  if (filter.fromDate) params.set('from', `${filter.fromDate}T00:00:00+08:00`)
  // The "to" date is included, so the period runs to the start of the next day.
  if (filter.toDate) params.set('to', `${addDays(filter.toDate, 1)}T00:00:00+08:00`)
  return useQuery({
    queryKey: ['audit', filter],
    queryFn: ({ signal }) =>
      request<AuditPage>('GET', `/admin/audit-log?${params.toString()}`, { signal }),
  })
}
