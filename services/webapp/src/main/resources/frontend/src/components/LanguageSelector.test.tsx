import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { I18nProvider } from '../services/i18n';
import { LanguageSelector } from './LanguageSelector';

beforeEach(() => {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  };
  Object.defineProperty(window, 'localStorage', { configurable: true, value: storage });
  vi.stubGlobal('localStorage', storage);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function renderSelector() {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 304 }));
  return render(<I18nProvider fetcher={fetcher}><LanguageSelector /><button type="button">Fuera</button></I18nProvider>);
}

it('permite elegir el idioma con teclado, conserva la preferencia y devuelve el foco', async () => {
  renderSelector();
  const trigger = screen.getByRole('button', { name: 'Seleccionar idioma: ES' });
  fireEvent.keyDown(trigger, { key: 'ArrowDown' });
  const spanish = screen.getByRole('option', { name: 'Español' });
  const english = screen.getByRole('option', { name: 'English' });
  expect(spanish).toHaveAttribute('aria-selected', 'true');
  await waitFor(() => expect(spanish).toHaveFocus());
  fireEvent.keyDown(spanish, { key: 'End' });
  await waitFor(() => expect(english).toHaveFocus());
  fireEvent.keyDown(english, { key: 'Home' });
  await waitFor(() => expect(spanish).toHaveFocus());
  fireEvent.keyDown(spanish, { key: 'ArrowUp' });
  await waitFor(() => expect(english).toHaveFocus());
  fireEvent.click(english);
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Select language: EN' })).toHaveFocus();
  expect(JSON.parse(window.localStorage.getItem('batch-downloader.locale.v2') ?? '{}').locale).toBe('en');
  expect(document.documentElement.lang).toBe('en');
});

it('cierra con Escape, al pulsar fuera y al mover el foco fuera del selector', async () => {
  renderSelector();
  const trigger = screen.getByRole('button', { name: 'Seleccionar idioma: ES' });
  fireEvent.click(trigger);
  await waitFor(() => expect(screen.getByRole('option', { name: 'Español' })).toHaveFocus());
  fireEvent.keyDown(screen.getByRole('option', { name: 'Español' }), { key: 'Escape' });
  expect(trigger).toHaveFocus();
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  fireEvent.click(trigger);
  fireEvent.pointerDown(screen.getByRole('button', { name: 'Fuera' }));
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  fireEvent.click(trigger);
  await waitFor(() => expect(screen.getByRole('option', { name: 'Español' })).toHaveFocus());
  act(() => screen.getByRole('button', { name: 'Fuera' }).focus());
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
});
