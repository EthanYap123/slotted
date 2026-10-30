import { useQuery } from '@tanstack/react-query'
import { fetchHealth } from '../../api/client'

/** Shows whether the API is reachable. The free host sleeps, so the first call can take a minute. */
export function ApiStatus() {
  const health = useQuery({
    queryKey: ['health'],
    queryFn: ({ signal }) => fetchHealth(signal),
  })

  if (health.isPending) {
    return (
      <p className="status status-waiting" role="status">
        Connecting to the server. If it was asleep this can take up to a minute.
      </p>
    )
  }
  if (health.isError || health.data.status !== 'UP') {
    return (
      <p className="status status-down" role="status">
        The server is not reachable right now.
      </p>
    )
  }
  return (
    <p className="status status-up" role="status">
      Server is online.
    </p>
  )
}
