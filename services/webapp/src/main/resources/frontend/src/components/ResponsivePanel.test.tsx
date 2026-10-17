import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { Link, MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MobileMenuButton, ResponsivePanel } from './ResponsivePanel';

let compact = true;
let listeners: Set<() => void>;

function Harness() {
  const [open, setOpen] = useState(false);
  return <MemoryRouter>
    <MobileMenuButton controls="test-navigation" open={open} onClick={() => setOpen(true)} />
    <ResponsivePanel id="test-navigation" title="Navegación" open={open} onClose={() => setOpen(false)}
      desktopClassName="desktop-navigation">
      <Link to="/next">Siguiente página</Link>
      <button type="button">Acción del panel</button>
    </ResponsivePanel>
  </MemoryRouter>;
}

beforeEach(() => {
  compact = true;
  listeners = new Set();
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches: compact,
    addEventListener: (_event: string, callback: () => void) => listeners.add(callback),
    removeEventListener: (_event: string, callback: () => void) => listeners.delete(callback),
  })));
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true, value(this: HTMLDialogElement) { this.setAttribute('open', ''); },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true, value(this: HTMLDialogElement) { this.removeAttribute('open'); },
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  document.body.style.overflow = '';
});

function openPanel() {
  const trigger = screen.getByRole('button', { name: 'Abrir menú' });
  trigger.focus();
  fireEvent.click(trigger);
  return trigger;
}

describe('ResponsivePanel', () => {
  it('starts closed, locks scrolling while open and restores the trigger on Escape', () => {
    render(<Harness />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    document.body.style.overflow = 'auto';
    const trigger = openPanel();
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(document.body.style.overflow).toBe('hidden');

    fireEvent(screen.getByRole('dialog', { name: 'Navegación' }), new Event('cancel', { cancelable: true }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe('auto');
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('dismisses on the backdrop without treating a drag from the content as dismissal', () => {
    render(<Harness />);
    openPanel();
    const dialog = screen.getByRole('dialog');
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Acción del panel' }));
    fireEvent.pointerUp(dialog);
    expect(dialog).toHaveAttribute('open');
    fireEvent.pointerDown(dialog);
    fireEvent.pointerUp(dialog);
    expect(dialog).not.toHaveAttribute('open');
  });

  it('closes after navigation and releases scrolling', () => {
    render(<Harness />);
    openPanel();
    fireEvent.click(screen.getByRole('link', { name: 'Siguiente página' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe('');
  });

  it('releases the modal on desktop and keeps it closed after returning to mobile', () => {
    const { container } = render(<Harness />);
    openPanel();
    act(() => { compact = false; listeners.forEach((listener) => listener()); });
    expect(container.querySelector('.desktop-navigation')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe('');
    act(() => { compact = true; listeners.forEach((listener) => listener()); });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Abrir menú' })).toHaveAttribute('aria-expanded', 'false');
  });

  it('cleans up an open drawer when its page unmounts', () => {
    const { unmount } = render(<Harness />);
    openPanel();
    unmount();
    expect(document.body.style.overflow).toBe('');
    expect(listeners.size).toBe(0);
  });
});
