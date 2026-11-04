/**
 * Every date and time a person sees is Singapore time, whatever the browser's zone (ADR-0003).
 * Dates are passed around as "YYYY-MM-DD" strings, meaning that day in Singapore.
 */
const ZONE = 'Asia/Singapore'

const dateKey = new Intl.DateTimeFormat('en-CA', {
  timeZone: ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})
const time = new Intl.DateTimeFormat('en-GB', {
  timeZone: ZONE,
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})
const longDate = new Intl.DateTimeFormat('en-GB', {
  timeZone: ZONE,
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

/** Today's date in Singapore. */
export function todayInSingapore(): string {
  return dateKey.format(new Date())
}

export function addDays(date: string, days: number): string {
  const utc = new Date(`${date}T00:00:00Z`)
  utc.setUTCDate(utc.getUTCDate() + days)
  return utc.toISOString().slice(0, 10)
}

/** "14:00" */
export function formatTime(instant: string | number): string {
  return time.format(new Date(instant))
}

/** "Wed, 4 Nov 2026" for an instant. */
export function formatDate(instant: string): string {
  return longDate.format(new Date(instant))
}

/** "Wed, 4 Nov 2026" for a "YYYY-MM-DD" date. */
export function formatDay(date: string): string {
  return longDate.format(new Date(`${date}T00:00:00+08:00`))
}

export function plusMinutes(instant: string, minutes: number): number {
  return new Date(instant).getTime() + minutes * 60_000
}

export function isInFuture(instant: string): boolean {
  return new Date(instant).getTime() > Date.now()
}

export function isWithinHours(instant: string, hours: number): boolean {
  return new Date(instant).getTime() - Date.now() < hours * 3_600_000
}
