import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiStatus } from './ApiStatus'

function renderWithClient() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ApiStatus />
    </QueryClientProvider>,
  )
}

function mockFetch(response: Response | Error) {
  const fetchMock = vi.fn(() =>
    response instanceof Error ? Promise.reject(response) : Promise.resolve(response),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('ApiStatus', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('says the server may be waking up while the first request is pending', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => undefined)),
    )

    renderWithClient()

    expect(screen.getByRole('status')).toHaveTextContent(/can take up to a minute/i)
  })

  it('shows online when health is UP', async () => {
    const fetchMock = mockFetch(Response.json({ status: 'UP' }))

    renderWithClient()

    expect(await screen.findByText('Server is online.')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('http://api.test/actuator/health', expect.anything())
  })

  it('shows unreachable when the health check fails', async () => {
    mockFetch(new Response(null, { status: 503 }))

    renderWithClient()

    expect(await screen.findByText(/not reachable/i)).toBeInTheDocument()
  })

  it('shows unreachable when the network request fails', async () => {
    mockFetch(new TypeError('Failed to fetch'))

    renderWithClient()

    expect(await screen.findByText(/not reachable/i)).toBeInTheDocument()
  })
})
