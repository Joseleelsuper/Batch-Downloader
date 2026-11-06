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

function maintenanceBundle(): BundleDetails {
  return {
    id: 'maintenance', slug: 'maintenance', name: 'Mantenimiento', description: '', type: 'official', visibility: 'official',
    starCount: 0, appCount: 0, tags: [], operatingSystems: [], platformAvailability: [],
    previewApps: [], apps: [], inactiveAppCount: 2, updatedAt: '2026-11-06T00:00:00Z',
  };
}

function mockMaintenance(bundle: BundleDetails) {
  vi.spyOn(bundlesApi, 'fetchBundles').mockResolvedValue({ data: [bundle], total: 1, page: 1, pageSize: 30 });
  vi.spyOn(bundlesApi, 'fetchBundle').mockResolvedValue(bundle);
  vi.spyOn(adminApi, 'fetchAdminApps').mockResolvedValue({ data: [], total: 0, page: 1, pageSize: 20 });
}

it('exige confirmar la retirada de asociaciones inactivas antes de guardar y reinicia el consentimiento', async () => {
  const bundle = maintenanceBundle();
  mockMaintenance(bundle);
  const update = vi.spyOn(bundlesApi, 'updateAdminBundle').mockResolvedValue({ ...bundle, inactiveAppCount: 0 });
  const { container } = render(<AdminBundlesPage />);
  fireEvent.click(await screen.findByRole('button', { name: /Mantenimiento/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent('2 aplicaciones inactivas');
  const save = screen.getByRole('button', { name: 'Guardar cambios' });
  expect(save).toBeDisabled();
  fireEvent.submit(container.querySelector('form')!);
  expect(update).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('checkbox', { name: /Confirmo que al guardar/ }));
  fireEvent.submit(container.querySelector('form')!);
  await waitFor(() => expect(update).toHaveBeenCalledWith(bundle.id, expect.objectContaining({ appIds: [] })));
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  fireEvent.click(screen.getByRole('button', { name: /Mantenimiento/ }));
  expect(await screen.findByRole('checkbox', { name: /Confirmo que al guardar/ })).not.toBeChecked();
  expect(save).toBeDisabled();
});

it('permite cancelar la eliminación y solo borra tras confirmar, conservando el editor si falla', async () => {
  const bundle = maintenanceBundle();
  mockMaintenance(bundle);
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  const remove = vi.spyOn(bundlesApi, 'deleteAdminBundle').mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue(undefined);
  render(<AdminBundlesPage />);
  fireEvent.click(await screen.findByRole('button', { name: /Mantenimiento/ }));
  const button = await screen.findByRole('button', { name: 'Eliminar bundle' });
  fireEvent.click(button);
  expect(remove).not.toHaveBeenCalled();
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('Mantenimiento'));
  confirm.mockReturnValue(true);
  fireEvent.click(button);
  expect(await screen.findByText('No se pudo eliminar el bundle. Puedes volver a intentarlo.')).toHaveAttribute('role', 'status');
  expect(screen.getByRole('button', { name: 'Eliminar bundle' })).toBeEnabled();
  fireEvent.click(button);
  await waitFor(() => expect(remove).toHaveBeenCalledTimes(2));
  expect(await screen.findByText('Bundle eliminado.')).toHaveAttribute('role', 'status');
  expect(screen.queryByRole('button', { name: 'Eliminar bundle' })).not.toBeInTheDocument();
});
