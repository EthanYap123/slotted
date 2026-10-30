const apiUrl = import.meta.env.VITE_API_URL.replace(/\/$/, '')

export interface Health {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
}

export async function fetchHealth(signal?: AbortSignal): Promise<Health> {
  const response = await fetch(`${apiUrl}/actuator/health`, { signal })
  if (!response.ok) {
    throw new Error(`Health check failed with ${String(response.status)}`)
  }
  return (await response.json()) as Health
}
