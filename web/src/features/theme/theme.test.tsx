import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/render'

const root = () => document.documentElement

describe('theme', () => {
  it('opens in light mode even on a dark device', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }))

    renderApp('/login')

    expect(screen.getByRole('radio', { name: 'Light' })).toBeChecked()
    expect(screen.getByRole('button', { name: 'Switch to dark theme' })).toBeInTheDocument()
  })

  it('flips with the header button and remembers the choice', async () => {
    const user = userEvent.setup()
    renderApp('/login')

    await user.click(screen.getByRole('button', { name: 'Switch to dark theme' }))

    expect(root()).toHaveAttribute('data-theme', 'dark')
    expect(localStorage.getItem('slotted.theme')).toBe('dark')
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeChecked()

    await user.click(screen.getByRole('button', { name: 'Switch to light theme' }))

    expect(root()).toHaveAttribute('data-theme', 'light')
  })

  it('can match the device', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }))
    const user = userEvent.setup()
    renderApp('/login')

    await user.click(screen.getByRole('radio', { name: 'Match device' }))

    expect(root()).toHaveAttribute('data-theme', 'system')
    expect(localStorage.getItem('slotted.theme')).toBe('system')
    expect(screen.getByRole('button', { name: 'Switch to light theme' })).toBeInTheDocument()
  })
})
