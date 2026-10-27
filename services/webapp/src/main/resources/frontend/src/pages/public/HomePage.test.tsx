import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import * as bundlesApi from '../../api/bundles';
import * as appsApi from '../../api/catalogApps';
import type { BundleResponse, BundleSummary } from '../../types/catalog';
import { HomePage } from './HomePage';

const auth = vi.hoisted(() => ({ account: { id: 'first' }, status: 'authenticated' }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => auth }));
vi.mock('../../components/BundleCard', () => ({ BundleCard: ({ bundle }: { bundle: BundleSummary }) =>
  <button type="button" aria-pressed={bundle.starred}>{bundle.name}</button> }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); auth.account = { id: 'first' }; });

it('oculta el estado personal anterior mientras llega el de la nueva cuenta', async () => {
  const bundle = { id: 'bundle', name: 'Trabajo', starred: true } as BundleSummary;
  vi.spyOn(appsApi, 'fetchApps').mockResolvedValue({ data: [], total: 0, page: 1, pageSize: 6 });
  const fetch = vi.spyOn(bundlesApi, 'fetchBundles').mockImplementation(async ({ type }) =>
    ({ data: type === 'official' ? [bundle] : [], total: type === 'official' ? 1 : 0, page: 1, pageSize: 3 }));
  const { rerender } = render(<MemoryRouter><HomePage /></MemoryRouter>);
  expect(await screen.findByRole('button', { name: 'Trabajo' })).toHaveAttribute('aria-pressed', 'true');
  let resolve!: (response: BundleResponse) => void;
  fetch.mockImplementation(({ type }) => type === 'official' ? new Promise((done) => { resolve = done; })
    : Promise.resolve({ data: [], total: 0, page: 1, pageSize: 3 }));
  auth.account = { id: 'second' };
  rerender(<MemoryRouter><HomePage /></MemoryRouter>);
  expect(screen.queryByRole('button', { name: 'Trabajo' })).not.toBeInTheDocument();
  await act(async () => { resolve({ data: [{ ...bundle, starred: false }], total: 1, page: 1, pageSize: 3 }); });
  expect(await screen.findByRole('button', { name: 'Trabajo' })).toHaveAttribute('aria-pressed', 'false');
});

it('termina el indicador de carga y muestra el error si falla la primera petición de bundles', async () => {
  vi.spyOn(appsApi, 'fetchApps').mockResolvedValue({ data: [], total: 0, page: 1, pageSize: 6 });
  vi.spyOn(bundlesApi, 'fetchBundles').mockRejectedValue(new Error('unavailable'));
  render(<MemoryRouter><HomePage /></MemoryRouter>);
  expect(await screen.findAllByRole('alert')).toHaveLength(2);
  expect(screen.queryAllByText(/Cargando/)).toHaveLength(0);
});
