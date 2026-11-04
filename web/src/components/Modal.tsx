import { useEffect, useId, useRef, type ReactNode } from 'react'

/**
 * A modal built on the native dialog element, which traps focus, closes on Escape and returns focus
 * to the control that opened it.
 */
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = ref.current
    if (dialog && !dialog.open) {
      dialog.showModal()
    }
    return () => {
      // Closing, rather than just removing it, returns focus to what opened the dialog.
      dialog?.close()
    }
  }, [])

  // The close event arrives later, so check it still applies: under React's StrictMode the dialog
  // has been reopened by then.
  function handleClose() {
    if (!ref.current?.open) {
      onClose()
    }
  }

  return (
    <dialog ref={ref} className="modal" aria-labelledby={titleId} onClose={handleClose}>
      <h2 id={titleId}>{title}</h2>
      {children}
    </dialog>
  )
}
