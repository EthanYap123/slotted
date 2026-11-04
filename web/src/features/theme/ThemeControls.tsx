import { isDark, setThemePreference, useThemePreference, type ThemePreference } from './theme'

/** Header button: flips between light and dark. */
export function ThemeToggle() {
  const dark = isDark(useThemePreference())
  return (
    <button
      type="button"
      className="icon-button"
      aria-label={dark ? 'Switch to light theme' : 'Switch to dark theme'}
      onClick={() => {
        setThemePreference(dark ? 'light' : 'dark')
      }}
    >
      <span aria-hidden="true">{dark ? '☀' : '☾'}</span>
    </button>
  )
}

const OPTIONS: { value: ThemePreference; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'system', label: 'Match device' },
]

/** Footer setting, saved on this device. */
export function ThemeSetting() {
  const preference = useThemePreference()
  return (
    <fieldset className="theme-setting">
      <legend>Theme</legend>
      {OPTIONS.map((option) => (
        <label key={option.value}>
          <input
            type="radio"
            name="theme"
            value={option.value}
            checked={preference === option.value}
            onChange={() => {
              setThemePreference(option.value)
            }}
          />
          {option.label}
        </label>
      ))}
    </fieldset>
  )
}
