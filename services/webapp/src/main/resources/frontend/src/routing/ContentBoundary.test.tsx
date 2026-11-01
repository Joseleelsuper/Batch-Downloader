import { act, cleanup, render, screen } from '@testing-library/react';
import { lazy } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { ContentBoundary } from './ContentBoundary';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('mantiene la navegación mientras se descarga la pantalla', async () => {
  let complete!: (module: { default: () => React.JSX.Element }) => void;
  const Page = lazy(() => new Promise<{ default: () => React.JSX.Element }>((resolve) => { complete = resolve; }));
  render(<><nav><button type="button">Menú</button></nav><ContentBoundary><Page /></ContentBoundary></>);
  expect(screen.getByRole('button', { name: 'Menú' })).toBeVisible();
  expect(screen.getByRole('status')).toHaveTextContent('Cargando');
  await act(async () => complete({ default: () => <h1>Pantalla</h1> }));
  expect(await screen.findByRole('heading', { name: 'Pantalla' })).toBeVisible();
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
});

it('aísla un fallo del módulo y ofrece recargar sin retirar el menú', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  const expectedError = (event: ErrorEvent) => { if (event.error?.message === 'chunk unavailable') event.preventDefault(); };
  window.addEventListener('error', expectedError);
  const Page = lazy(() => Promise.reject(new Error('chunk unavailable')));
  try {
    render(<><nav><button type="button">Menú</button></nav><ContentBoundary><Page /></ContentBoundary></>);
    expect(await screen.findByRole('alert')).toHaveTextContent('No se ha podido cargar');
    expect(screen.getByRole('button', { name: 'Recargar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Menú' })).toBeVisible();
  } finally {
    window.removeEventListener('error', expectedError);
  }
});
