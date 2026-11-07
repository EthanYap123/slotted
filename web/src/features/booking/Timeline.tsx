import { useRef, useState, type KeyboardEvent } from 'react'
import { formatTime } from '../../lib/time'
import type { RoomAvailability } from './bookingApi'

type CellState = RoomAvailability['cells'][number]['state']

const STATE_LABEL: Record<CellState, string> = {
  FREE: 'free',
  BOOKED: 'booked',
  MINE: 'your booking',
  BLACKOUT: 'closed for a blackout',
  PAST: 'in the past',
}

const STATE_MARK: Partial<Record<CellState, string>> = { MINE: '✓', BLACKOUT: '×' }

export function TimelineLegend() {
  return (
    <ul className="legend" aria-label="What the cells mean">
      <li>
        <span className="cell cell-free" aria-hidden="true" /> Free
      </li>
      <li>
        <span className="cell cell-booked" aria-hidden="true" /> Booked
      </li>
      <li>
        <span className="cell cell-mine" aria-hidden="true">
          ✓
        </span>{' '}
        Yours
      </li>
      <li>
        <span className="cell cell-blackout" aria-hidden="true">
          ×
        </span>{' '}
        Closed
      </li>
      <li>
        <span className="cell cell-past" aria-hidden="true" /> Past
      </li>
    </ul>
  )
}

/**
 * Rooms as rows of 30-minute cells. A grid with one tab stop: Tab enters it, arrow keys move between
 * cells, Enter or Space picks a free cell. Scrolls inside its own box, so a phone's page never
 * scrolls sideways. With {@code onPickTaken}, booked cells can be picked too, to wait for them.
 */
export function Timeline({
  rooms,
  onPick,
  onPickTaken,
}: {
  rooms: RoomAvailability[]
  onPick: (room: RoomAvailability, cellIndex: number) => void
  onPickTaken?: (room: RoomAvailability, cellIndex: number) => void
}) {
  const tableRef = useRef<HTMLTableElement>(null)
  const [moved, setMoved] = useState<{ row: number; column: number } | null>(null)

  const header = rooms[0]?.cells ?? []
  const current = moved ?? firstFreeCell(rooms)

  function moveTo(row: number, column: number) {
    const nextRow = clamp(row, rooms.length - 1)
    const nextColumn = clamp(column, (rooms[nextRow]?.cells.length ?? 1) - 1)
    setMoved({ row: nextRow, column: nextColumn })
    tableRef.current
      ?.querySelector<HTMLButtonElement>(`[data-cell="${String(nextRow)}-${String(nextColumn)}"]`)
      ?.focus()
  }

  function handleKeyDown(event: KeyboardEvent) {
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [current.row, current.column - 1],
      ArrowRight: [current.row, current.column + 1],
      ArrowUp: [current.row - 1, current.column],
      ArrowDown: [current.row + 1, current.column],
      Home: [current.row, 0],
      End: [current.row, header.length - 1],
    }
    const target = moves[event.key]
    if (target) {
      event.preventDefault()
      moveTo(target[0], target[1])
    }
  }

  return (
    <div className="timeline-scroll">
      <table ref={tableRef} className="timeline" role="grid" onKeyDown={handleKeyDown}>
        <caption className="visually-hidden">
          Room availability in 30-minute cells, Singapore time
        </caption>
        <thead>
          <tr>
            <th scope="col" className="timeline-room">
              Room
            </th>
            {header.map((cell) => {
              const label = formatTime(cell.startAt)
              return (
                <th key={cell.startAt} scope="col" className="timeline-time">
                  {label.endsWith(':00') ? label : <span className="visually-hidden">{label}</span>}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {rooms.map((room, row) => (
            <tr key={room.roomId}>
              <th scope="row" className="timeline-room">
                {room.roomName}
                <span className="timeline-capacity">Up to {room.capacity}</span>
              </th>
              {room.cells.map((cell, column) => {
                const free = cell.state === 'FREE'
                const canWait = cell.state === 'BOOKED' && onPickTaken !== undefined
                const isCurrent = current.row === row && current.column === column
                return (
                  <td key={cell.startAt}>
                    <button
                      type="button"
                      data-cell={`${String(row)}-${String(column)}`}
                      className={`cell cell-${cell.state.toLowerCase()}`}
                      tabIndex={isCurrent ? 0 : -1}
                      aria-disabled={!free && !canWait}
                      aria-label={`${room.roomName}, ${formatTime(cell.startAt)}, ${STATE_LABEL[cell.state]}${canWait ? ', join the waitlist' : ''}`}
                      onFocus={() => {
                        setMoved({ row, column })
                      }}
                      onClick={() => {
                        if (free) {
                          onPick(room, column)
                        } else if (canWait) {
                          onPickTaken(room, column)
                        }
                      }}
                    >
                      <span aria-hidden="true">{STATE_MARK[cell.state]}</span>
                    </button>
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function firstFreeCell(rooms: RoomAvailability[]) {
  for (const [row, room] of rooms.entries()) {
    const column = room.cells.findIndex((cell) => cell.state === 'FREE')
    if (column >= 0) {
      return { row, column }
    }
  }
  return { row: 0, column: 0 }
}

function clamp(value: number, max: number) {
  return Math.max(0, Math.min(value, max))
}
