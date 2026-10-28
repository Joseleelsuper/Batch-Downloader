import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api/bundles';
import { BundleActivity } from './BundleActivity';

const auth = vi.hoisted(() => ({ account: { id: 'viewer' } as { id: string } | null, status: 'authenticated' }));
vi.mock('../auth/AuthContext', () => ({ useAuth: () => auth }));
const bundle = { id: 'bundle', name: 'Trabajo', visibility: 'public', starCount: 4, starred: false, downloadCount: 2 };
beforeEach(() => { auth.account = { id: 'viewer' }; auth.status = 'authenticated'; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('muestra contador e indicación de sesión sin ofrecer voto anónimo', () => {
  auth.account = null; auth.status = 'anonymous';
  render(<BundleActivity bundle={bundle} />);
  expect(screen.getByText('Inicia sesión para dar estrellas')).toBeInTheDocument();
  expect(screen.getByLabelText('4 estrellas')).toBeInTheDocument();
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
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
