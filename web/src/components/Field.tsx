import { useId, type ReactNode } from 'react'

/** The attributes that link a control to its label's error or hint. */
export interface ControlProps {
  id: string
  'aria-invalid': boolean
  'aria-describedby': string | undefined
}

/**
 * A labelled form control with its error or hint underneath. The control is passed as a function
 * so it can be any input, select or textarea and still be linked to the message.
 */
export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string
  error?: string
  hint?: string
  children: (props: ControlProps) => ReactNode
}) {
  const id = useId()
  const messageId = `${id}-message`
  const message = error ?? hint
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children({
        id,
        'aria-invalid': Boolean(error),
        'aria-describedby': message ? messageId : undefined,
      })}
      {message && (
        <span id={messageId} className={error ? 'field-error' : 'field-hint'}>
          {message}
        </span>
      )}
    </div>
  )
}
