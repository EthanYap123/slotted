import { plusMinutes } from '../../lib/time'
import type { RoomAvailability } from './bookingApi'

const CELL_MINUTES = 30

/**
 * End times the booking could have: each cell after the start while the room stays free, up to the
 * room's maximum length. The API checks all of this again; this just avoids offering bad choices.
 */
export function endOptions(room: RoomAvailability, startIndex: number): string[] {
  return optionsThrough(room, startIndex, ['FREE'])
}

/**
 * End times a waitlist entry could have: like a booking, but through taken time too (BR-24). It
 * stops at the student's own bookings, closed time and anything else that could never be booked.
 */
export function waitlistEndOptions(room: RoomAvailability, startIndex: number): string[] {
  return optionsThrough(room, startIndex, ['FREE', 'BOOKED'])
}

function optionsThrough(
  room: RoomAvailability,
  startIndex: number,
  states: RoomAvailability['cells'][number]['state'][],
): string[] {
  const options: string[] = []
  const maxCells = room.maxBookingMinutes / CELL_MINUTES
  for (let i = startIndex; i < room.cells.length && options.length < maxCells; i++) {
    const cell = room.cells[i]
    if (!cell || !states.includes(cell.state)) {
      break
    }
    options.push(new Date(plusMinutes(cell.startAt, CELL_MINUTES)).toISOString())
  }
  return options
}
