import { useSyncExternalStore } from 'react'

/** Light by default; following the device is a choice, not the default. */
export type ThemePreference = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'slotted.theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'
const listeners = new Set<() => void>()

function readPreference(): ThemePreference {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved === 'dark' || saved === 'system' ? saved : 'light'
  } catch {
    return 'light'
  }
}

export function setThemePreference(preference: ThemePreference) {
  try {
    localStorage.setItem(STORAGE_KEY, preference)
  } catch {
    // The choice still applies until the page is closed.
  }
  document.documentElement.setAttribute('data-theme', preference)
  listeners.forEach((listener) => {
    listener()
  })
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function useThemePreference(): ThemePreference {
  return useSyncExternalStore(subscribe, readPreference)
}

/** Whether the page is showing dark colours right now, once "Match device" is resolved. */
export function isDark(preference: ThemePreference): boolean {
  if (preference === 'system') {
    return window.matchMedia(DARK_QUERY).matches
  }
  return preference === 'dark'
}
