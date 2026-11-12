import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { errorMessage } from '../../api/errors'
import { Modal } from '../../components/Modal'
import { useRoom, useRoomQr, useRotateQr } from './manageApi'

/**
 * Shows SVG text as an image. Going through an image, rather than inserting the markup, means
 * nothing in the file can run as part of the page.
 */
function svgUrl(svg: string | undefined): string | null {
  return svg ? `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}` : null
}

/** PRD MG-5: a room's printable check-in QR code, and replacing it if it leaks. */
export function RoomQrPage() {
  const { roomId = '' } = useParams()
  const room = useRoom(roomId)
  const qr = useRoomQr(roomId)
  const url = useMemo(() => svgUrl(qr.data), [qr.data])
  const [confirming, setConfirming] = useState(false)
  const [rotated, setRotated] = useState(false)
  const name = room.data?.name ?? 'Room'

  return (
    <>
      <p className="breadcrumb no-print">
        {room.data && <Link to={`/manage/${room.data.facilityId}`}>Back to rooms</Link>}
      </p>
      <div className="qr-sheet">
        <h1>{name}</h1>
        <p className="qr-instructions">Scan with your phone&apos;s camera to check in.</p>
        {qr.isPending && <p role="status">Loading the QR code…</p>}
        {(qr.isError || room.isError) && (
          <p className="notice notice-error" role="alert">
            {errorMessage(qr.error ?? room.error)}
          </p>
        )}
        {url && <img className="qr-image" src={url} alt={`Check-in QR code for ${name}`} />}
      </div>
      {rotated && (
        <p className="notice notice-ok no-print" role="status">
          A new QR code was made. The old one no longer works, so print this one and replace it.
        </p>
      )}
      <div className="actions no-print">
        <button
          type="button"
          className="button button-primary"
          disabled={!url}
          onClick={() => {
            window.print()
          }}
        >
          Print
        </button>
        <button
          type="button"
          className="button"
          onClick={() => {
            setRotated(false)
            setConfirming(true)
          }}
        >
          Replace QR code
        </button>
      </div>
      {confirming && (
        <RotateDialog
          roomId={roomId}
          name={name}
          onClose={() => {
            setConfirming(false)
          }}
          onRotated={() => {
            setConfirming(false)
            setRotated(true)
          }}
        />
      )}
    </>
  )
}

function RotateDialog({
  roomId,
  name,
  onClose,
  onRotated,
}: {
  roomId: string
  name: string
  onClose: () => void
  onRotated: () => void
}) {
  const rotate = useRotateQr(roomId)
  return (
    <Modal title="Replace this QR code?" onClose={onClose}>
      <p>
        The printed code for {name} stops working at once. Do this if the code has been copied or
        shared, then print the new one.
      </p>
      {rotate.error && (
        <p className="form-error" role="alert">
          {errorMessage(rotate.error)}
        </p>
      )}
      <div className="modal-actions">
        <button type="button" className="button" onClick={onClose}>
          Keep current code
        </button>
        <button
          type="button"
          className="button button-danger"
          disabled={rotate.isPending}
          onClick={() => {
            rotate.mutate(undefined, { onSuccess: onRotated })
          }}
        >
          {rotate.isPending ? 'Replacing…' : 'Replace QR code'}
        </button>
      </div>
    </Modal>
  )
}
