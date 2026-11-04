import { plusMinutes } from '../../lib/time'
import type { RoomAvailability } from './bookingApi'

const CELL_MINUTES = 30

/**
 * End times the booking could have: each cell after the start while the room stays free, up to the
 * room's maximum length. The API checks all of this again; this just avoids offering bad choices.
 */
export function endOptions(room: RoomAvailability, startIndex: number): string[] {
  const options: string[] = []
  const maxCells = room.maxBookingMinutes / CELL_MINUTES
  for (let i = startIndex; i < room.cells.length && options.length < maxCells; i++) {
    const cell = room.cells[i]
    if (cell?.state !== 'FREE') {
      break
    }
    options.push(new Date(plusMinutes(cell.startAt, CELL_MINUTES)).toISOString())
  }
  return options
}
