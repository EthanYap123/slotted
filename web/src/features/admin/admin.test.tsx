import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { session } from '../../api/session'
import { admin, authResponseFor, fakeApi, manager, problem } from '../../test/fakeApi'
import { renderApp } from '../../test/render'
import type { AuditRecord, User } from './adminApi'

const TODAY = '2026-11-04'

function user(overrides: Partial<User> = {}): User {
  return {
    id: '44444444-4444-4444-4444-444444444444',
    email: 'ada@example.com',
    fullName: 'Ada Tan',
    role: 'STUDENT',
    status: 'ACTIVE',
    suspendedUntil: null,
    createdAt: '2026-10-01T10:00:00+08:00',
    ...overrides,
  }
}

function page<T>(items: T[]) {
  return { items, page: { number: 0, size: 20, totalElements: items.length, totalPages: 1 } }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(`${TODAY}T10:12:00+08:00`))
  session.signedIn(authResponseFor(admin))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('who sees the admin pages', () => {
  it('shows the admin links to admins', () => {
    fakeApi({ 'GET /admin/users': { body: page([]) } })
    renderApp('/admin/users')

    const nav = screen.getByRole('navigation', { name: 'Main' })
    for (const name of ['Manage', 'Analytics', 'Users', 'Facilities', 'Audit log']) {
      expect(within(nav).getByRole('link', { name })).toBeInTheDocument()
    }
  })

  it('keeps managers out of the admin pages', () => {
    session.signedIn(authResponseFor(manager))
    fakeApi({})
    renderApp('/admin/audit')

    expect(screen.getByRole('alert')).toHaveTextContent('You do not have access to this page.')
    expect(
      within(screen.getByRole('navigation', { name: 'Main' })).queryByRole('link', {
        name: 'Audit log',
      }),
    ).not.toBeInTheDocument()
  })
})

describe('users', () => {
  it('searches by name and disables an account after confirming', async () => {
    const api = fakeApi({
      'GET /admin/users': { body: page([user()]) },
      'PATCH /admin/users/44444444-4444-4444-4444-444444444444': {
        body: user({ status: 'DISABLED' }),
      },
    })
    const u = userEvent.setup()
    renderApp('/admin/users')

    await u.type(await screen.findByLabelText('Name or email'), 'ada')
    await u.click(screen.getByRole('button', { name: 'Search' }))
    expect(api.calls.at(-1)?.path).toBe('/admin/users?page=0&size=20&q=ada')

    await u.click(await screen.findByRole('button', { name: 'Disable Ada Tan' }))
    const dialog = screen.getByRole('dialog', { name: 'Disable this account?' })
    expect(dialog).toHaveTextContent('Their future bookings are cancelled')
    await u.click(within(dialog).getByRole('button', { name: 'Disable account' }))

    expect(await screen.findByText('Ada Tan is disabled.')).toBeVisible()
    expect(api.calls.find((c) => c.method === 'PATCH')?.body).toEqual({ status: 'DISABLED' })
  })

  it('shows why an admin cannot demote themselves beside the role field', async () => {
    const me = user({ id: admin.id, fullName: admin.fullName, email: admin.email, role: 'ADMIN' })
    fakeApi({
      'GET /admin/users': { body: page([me]) },
      [`PATCH /admin/users/${admin.id}`]: problem(
        422,
        'LAST_ADMIN',
        'You cannot remove your own admin role.',
      ),
    })
    const u = userEvent.setup()
    renderApp('/admin/users')

    expect(await screen.findByRole('heading', { name: 'Raj Kumar (you)' })).toBeVisible()
    await u.click(screen.getByRole('button', { name: 'Change role of Raj Kumar' }))
    const dialog = screen.getByRole('dialog')
    await u.selectOptions(within(dialog).getByLabelText('Role'), 'Student')
    await u.click(within(dialog).getByRole('button', { name: 'Save role' }))

    expect(await within(dialog).findByText('You cannot remove your own admin role.')).toBeVisible()
    expect(within(dialog).getByLabelText('Role')).toHaveAccessibleDescription(
      'You cannot remove your own admin role.',
    )
  })

  it('lifts a no-show suspension', async () => {
    const suspended = user({ suspendedUntil: '2026-11-08T10:00:00+08:00' })
    const api = fakeApi({
      'GET /admin/users': { body: page([suspended]) },
      'PATCH /admin/users/44444444-4444-4444-4444-444444444444': { body: user() },
    })
    const u = userEvent.setup()
    renderApp('/admin/users')

    expect(await screen.findByText(/Suspended for no-shows until Sun, 8 Nov 2026/)).toBeVisible()
    await u.click(screen.getByRole('button', { name: 'Lift suspension of Ada Tan' }))
    await u.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Lift suspension' }),
    )

    expect(await screen.findByText('Ada Tan can book again.')).toBeVisible()
    expect(api.calls.find((c) => c.method === 'PATCH')?.body).toEqual({ liftSuspension: true })
  })
})

describe('facilities', () => {
  const library = {
    id: 'f1',
    name: 'Library',
    timeZone: 'Asia/Singapore',
    openingHours: [],
    version: 0,
  }
  const mei = user({
    id: manager.id,
    fullName: 'Mei Lim',
    email: 'mei@example.com',
    role: 'MANAGER',
  })
  const tom = user({
    id: '55555555-5555-5555-5555-555555555555',
    fullName: 'Tom Goh',
    email: 'tom@example.com',
    role: 'MANAGER',
  })

  it('shows a taken name beside the name field', async () => {
    fakeApi({
      'GET /facilities': { body: [library] },
      'GET /facilities/f1/managers': { body: [] },
      'POST /facilities': problem(409, 'NAME_TAKEN', 'There is already a facility with this name.'),
    })
    const u = userEvent.setup()
    renderApp('/admin/facilities')

    expect(await screen.findByText('Managers: none yet')).toBeVisible()
    await u.click(screen.getByRole('button', { name: 'Create facility' }))
    const dialog = screen.getByRole('dialog', { name: 'Create a facility' })
    await u.type(within(dialog).getByLabelText('Name'), 'library')
    await u.click(within(dialog).getByRole('button', { name: 'Create facility' }))

    expect(within(dialog).getByLabelText('Name')).toHaveAccessibleDescription(
      'There is already a facility with this name.',
    )
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('chooses managers from the users with the Manager role', async () => {
    const api = fakeApi({
      'GET /facilities': { body: [library] },
      'GET /facilities/f1/managers': [
        { body: [{ id: mei.id, fullName: mei.fullName, email: mei.email }] },
      ],
      'GET /admin/users?page=0&size=100&role=MANAGER': { body: page([mei, tom]) },
      'PUT /facilities/f1/managers': { status: 204 },
    })
    const u = userEvent.setup()
    renderApp('/admin/facilities')

    expect(await screen.findByText('Managers: Mei Lim')).toBeVisible()
    await u.click(screen.getByRole('button', { name: 'Choose managers of Library' }))
    const dialog = screen.getByRole('dialog', { name: 'Managers of Library' })
    expect(await within(dialog).findByRole('checkbox', { name: /Mei Lim/ })).toBeChecked()
    await u.click(within(dialog).getByRole('checkbox', { name: /Tom Goh/ }))
    await u.click(within(dialog).getByRole('button', { name: 'Save managers' }))

    expect(await screen.findByText(/Managers of Library saved/)).toBeVisible()
    expect(api.calls.find((c) => c.method === 'PUT')?.body).toEqual({
      userIds: [mei.id, tom.id],
    })
  })
})

describe('audit log', () => {
  const bookingId = '66666666-6666-6666-6666-666666666666'

  /** The API sends any JSON object here; the generated type allows no fields. */
  function values(fields: Record<string, unknown>) {
    return fields as unknown as AuditRecord['after']
  }

  function record(overrides: Partial<AuditRecord>): AuditRecord {
    return {
      id: 1,
      occurredAt: `${TODAY}T09:00:00+08:00`,
      actorId: admin.id,
      actorName: 'Raj Kumar',
      action: 'BOOKING_CREATED',
      entityType: 'BOOKING',
      entityId: bookingId,
      before: null,
      after: values({ status: 'CONFIRMED' }),
      requestId: 'req-1',
      ...overrides,
    }
  }

  it("shows one booking's history, with system actions marked", async () => {
    const api = fakeApi({
      'GET /admin/audit-log': {
        body: page([
          record({}),
          record({
            id: 2,
            action: 'BOOKING_NO_SHOW',
            actorId: null,
            actorName: 'System',
            before: values({ status: 'CONFIRMED' }),
            after: values({ status: 'NO_SHOW' }),
            requestId: null,
          }),
        ]),
      },
    })
    const u = userEvent.setup()
    renderApp(`/admin/audit?entityType=BOOKING&entityId=${bookingId}`)

    expect(await screen.findByText('Booking no show')).toBeVisible()
    expect(screen.getByText('History of one item, oldest change first.')).toBeVisible()
    expect(api.calls[0]?.path).toBe(
      `/admin/audit-log?page=0&size=25&entityType=BOOKING&entityId=${bookingId}`,
    )
    const entries = screen.getAllByRole('listitem')
    expect(within(entries[1] as HTMLElement).getByText('System')).toBeVisible()
    await u.click(within(entries[1] as HTMLElement).getByText('1 field changed'))
    expect(within(entries[1] as HTMLElement).getByText('CONFIRMED → NO_SHOW')).toBeVisible()
  })

  it('checks an ID before searching with it', async () => {
    const api = fakeApi({ 'GET /admin/audit-log': { body: page([]) } })
    const u = userEvent.setup()
    renderApp('/admin/audit')

    expect(await screen.findByText('No records match.')).toBeVisible()
    await u.type(screen.getByLabelText('Item ID'), 'not-an-id')
    await u.click(screen.getByRole('button', { name: 'Show records' }))

    expect(screen.getByLabelText('Item ID')).toHaveAccessibleDescription('Paste a full ID')
    expect(api.calls).toHaveLength(1)
  })
})
