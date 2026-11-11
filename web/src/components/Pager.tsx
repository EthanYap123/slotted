import type { components } from '../api/schema'

type PageMeta = components['schemas']['PageMeta']

/** Previous and next buttons for a paged list; shows nothing when there is only one page. */
export function Pager({
  meta,
  label,
  onPage,
}: {
  meta: PageMeta | undefined
  label: string
  onPage: (page: number) => void
}) {
  if (!meta || meta.totalPages <= 1) {
    return null
  }
  return (
    <nav className="pager" aria-label={label}>
      <button
        type="button"
        className="button"
        disabled={meta.number <= 0}
        onClick={() => {
          onPage(meta.number - 1)
        }}
      >
        Previous
      </button>
      <span>
        Page {meta.number + 1} of {meta.totalPages}
      </span>
      <button
        type="button"
        className="button"
        disabled={meta.number >= meta.totalPages - 1}
        onClick={() => {
          onPage(meta.number + 1)
        }}
      >
        Next
      </button>
    </nav>
  )
}
