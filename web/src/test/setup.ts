import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { session } from '../api/session'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  localStorage.clear()
  session.reset()
  document.documentElement.removeAttribute('data-theme')
})

// jsdom does not fully implement these browser features.
HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
  this.setAttribute('open', '')
}
HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
  this.removeAttribute('open')
  this.dispatchEvent(new Event('close'))
}
window.matchMedia = (query: string) =>
  ({ matches: false, media: query }) as unknown as MediaQueryList
