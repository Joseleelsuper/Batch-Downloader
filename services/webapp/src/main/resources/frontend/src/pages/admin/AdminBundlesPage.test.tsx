import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import * as adminApi from '../../api/adminApps';
import * as bundlesApi from '../../api/bundles';
import type { BundleDetails, CatalogApp } from '../../types/catalog';
import { AdminBundlesPage } from './AdminOverviewPages';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('edita un bundle oficial con selección persistente y guarda el orden elegido', async () => {
  const steam: CatalogApp = {
    id: 'steam', slug: 'steam', packageId: 'Valve.Steam', name: 'Steam', tags: [],
    operatingSystems: ['windows'], sourceLabel: 'Official', resolutionStatus: 'direct',
    validationStatus: 'valid', downloadable: true, updatedAt: '2026-10-03T00:00:00Z',
  };
  const epic: CatalogApp = { ...steam, id: 'epic', name: 'Epic Games' };
  const bundle: BundleDetails = {
    id: 'games', slug: 'games', name: 'Juegos', description: '', type: 'official', visibility: 'official',
    starCount: 0, appCount: 1, tags: [], operatingSystems: ['windows'], platformAvailability: [],
    previewApps: [steam], apps: [steam], updatedAt: steam.updatedAt,
  };
  vi.spyOn(bundlesApi, 'fetchBundles').mockResolvedValue({ data: [bundle], total: 1, page: 1, pageSize: 30 });
  vi.spyOn(bundlesApi, 'fetchBundle').mockResolvedValue(bundle);
  const update = vi.spyOn(bundlesApi, 'updateAdminBundle').mockResolvedValue({ ...bundle, apps: [epic, steam] });
  const search = vi.spyOn(adminApi, 'fetchAdminApps').mockResolvedValue({ data: [epic], total: 1, page: 1, pageSize: 20 });
  const { container } = render(<AdminBundlesPage />);

  fireEvent.click(await screen.findByRole('button', { name: /Juegos/ }));
  expect(await screen.findByRole('button', { name: 'Quitar Steam' })).toBeInTheDocument();
  fireEvent.click(await screen.findByRole('button', { name: 'Añadir Epic Games' }));
  fireEvent.click(screen.getByRole('button', { name: 'Subir Epic Games' }));
  fireEvent.submit(container.querySelector('form')!);

  await waitFor(() => expect(update).toHaveBeenCalledWith('games', expect.objectContaining({
    appIds: ['epic', 'steam'], type: 'official', visibility: 'official',
  })));
  expect(search).toHaveBeenCalledWith(expect.objectContaining({ filter: 'all', sort: 'relevance', pageSize: 20 }), expect.any(AbortSignal));
});
