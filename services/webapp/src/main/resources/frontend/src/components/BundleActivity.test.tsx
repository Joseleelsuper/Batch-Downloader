import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api/bundles';
import { BundleActivity } from './BundleActivity';

const auth = vi.hoisted(() => ({ account: { id: 'viewer' } as { id: string } | null, status: 'authenticated' }));
vi.mock('../auth/AuthContext', () => ({ useAuth: () => auth }));
const bundle = { id: 'bundle', name: 'Trabajo', visibility: 'public', starCount: 4, starred: false, downloadCount: 2 };
beforeEach(() => { auth.account = { id: 'viewer' }; auth.status = 'authenticated'; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('ofrece iniciar sesión al pulsar la estrella anónima y permite cancelar', () => {
  auth.account = null; auth.status = 'anonymous';
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.removeAttribute('open'); } });
  const star = vi.spyOn(api, 'setBundleStar');
  render(<MemoryRouter initialEntries={['/catalog/bundles']}><BundleActivity bundle={bundle} /></MemoryRouter>);
  expect(screen.queryByText('Inicia sesión para dar estrellas')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Dar estrella a Trabajo' }));
  expect(screen.getByRole('dialog')).toHaveTextContent('Necesitas iniciar sesión');
  expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
  expect(star).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('confirma el voto, anuncia el estado y retira la mención al quitar el último voto administrativo', async () => {
  vi.spyOn(api, 'setBundleStar').mockResolvedValueOnce({ starCount: 5, starred: true, hasAdminStar: true, downloadCount: 2 })
    .mockResolvedValueOnce({ starCount: 4, starred: false, hasAdminStar: false, downloadCount: 2 });
  render(<BundleActivity bundle={bundle} />);
  fireEvent.click(screen.getByRole('button', { name: 'Dar estrella a Trabajo' }));
  expect(await screen.findByText('Con estrella de administración')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Quitar estrella de Trabajo' })).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(screen.getByRole('button', { name: 'Quitar estrella de Trabajo' }));
  await waitFor(() => expect(screen.queryByText('Con estrella de administración')).not.toBeInTheDocument());
  expect(api.setBundleStar).toHaveBeenNthCalledWith(2, 'bundle', false);
});

it('conserva el voto anterior cuando falla el guardado', async () => {
  vi.spyOn(api, 'setBundleStar').mockRejectedValue(new Error('unavailable'));
  render(<BundleActivity bundle={bundle} />);
  fireEvent.click(screen.getByRole('button', { name: 'Dar estrella a Trabajo' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo guardar la estrella');
  expect(screen.getByRole('button', { name: 'Dar estrella a Trabajo' })).toHaveAttribute('aria-pressed', 'false');
});

it('oculta toda la actividad de un bundle privado', () => {
  const { container } = render(<BundleActivity bundle={{ ...bundle, visibility: 'private', starred: true }} />);
  expect(container).toBeEmptyDOMElement();
});
