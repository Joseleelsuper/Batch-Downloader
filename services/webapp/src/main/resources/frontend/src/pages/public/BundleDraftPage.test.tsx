import { Suspense } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as accountApi from '../../api/account';
import * as bundleApi from '../../api/bundles';
import * as catalogApi from '../../api/catalogApps';
import type { AuthUser, BundleDetails, CatalogApp } from '../../types/catalog';
import { t } from '../../services/i18n';
import { BundleDraftPage } from './BundleDraftPage';

const state = vi.hoisted(() => ({ account: null as AuthUser | null, start: vi.fn() }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ account: state.account }) }));
vi.mock('../../hooks/useDownloadJob', () => ({ useDownloadJob: () => ({ job: null, starting: false, start: state.start }) }));

const app: CatalogApp = {
  id: '00000000-0000-0000-0000-000000000001', slug: 'first', packageId: 'Example.First',
  name: 'First', tags: ['tools'], operatingSystems: ['windows'], downloadable: true,
  sourceLabel: 'Official', resolutionStatus: 'direct', validationStatus: 'valid', updatedAt: '',
};
const other = { ...app, id: '00000000-0000-0000-0000-000000000002', name: 'Second' };
const added = { ...app, id: '00000000-0000-0000-0000-000000000003', name: 'Third' };
const bundle: BundleDetails = {
  id: 'source-bundle', slug: 'tools', name: 'Tools', description: 'Source description', type: 'official',
  visibility: 'official', starCount: 0, appCount: 2, tags: ['tools'], operatingSystems: ['windows'],
  platformAvailability: [], previewApps: [app, other], apps: [app, other], updatedAt: '',
};

function open(path: string) {
  const page = <MemoryRouter initialEntries={[path]}><Suspense fallback={null}><Routes>
    <Route path="/bundles/:slug/customize" element={<BundleDraftPage />} />
    <Route path="/dashboard/bundles/:id/edit" element={<p>Saved private copy</p>} />
  </Routes></Suspense></MemoryRouter>;
  return render(page);
}

beforeEach(() => {
  state.account = null;
  state.start.mockResolvedValue(undefined);
  vi.spyOn(bundleApi, 'fetchBundle').mockResolvedValue(bundle);
  vi.spyOn(catalogApi, 'fetchApps').mockResolvedValue({ data: [added], page: 1, pageSize: 20, total: 1,
    requestedMode: 'semantic', appliedMode: 'semantic' });
  vi.spyOn(accountApi, 'createOwnBundle');
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); });

describe('editable bundle drafts', () => {
  it('personaliza y descarga anónimamente por IDs sin guardar ni modificar el bundle original', async () => {
    open('/bundles/tools/customize');
    fireEvent.click(await screen.findByRole('button', { name: 'Quitar First' }, { timeout: 5000 }));
    fireEvent.click(await screen.findByRole('button', { name: 'Añadir Third' }));
    fireEvent.click(screen.getByRole('button', { name: 'Descargar bundle (2)' }));
    expect(state.start).toHaveBeenCalledWith({ appIds: [other.id, added.id], operatingSystems: ['windows'] }, expect.any(String));
    expect(accountApi.createOwnBundle).not.toHaveBeenCalled();
    expect(bundle.apps).toEqual([app, other]);
    expect(screen.queryByRole('button', { name: t('bundle.draft.save') })).not.toBeInTheDocument();
  });

  it('crea una copia privada independiente solo después de la confirmación del usuario', async () => {
    state.account = { id: 'owner', username: 'owner', email: 'owner@example.com', role: 'USER', emailVerified: true, createdAt: '' };
    vi.mocked(accountApi.createOwnBundle).mockResolvedValue({ id: 'copy', slug: 'copia-de-tools', name: 'Copia de Tools',
      visibility: 'private', appCount: 1, tags: ['tools'], apps: [other], updatedAt: '', version: 0 });
    open('/bundles/tools/customize?clone=1');
    fireEvent.click(await screen.findByRole('button', { name: 'Quitar First' }, { timeout: 5000 }));
    expect(accountApi.createOwnBundle).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: t('bundle.draft.save') }));
    await waitFor(() => expect(accountApi.createOwnBundle).toHaveBeenCalledWith({ name: 'Copia de Tools', slug: 'copia-de-tools',
      description: 'Source description', tags: ['tools'], appIds: [other.id] }));
    expect(await screen.findByText('Saved private copy')).toBeInTheDocument();
    expect(bundle.apps).toEqual([app, other]);
  });

  it('no ofrece clonación de un bundle privado ni guarda automáticamente una propuesta', async () => {
    vi.mocked(bundleApi.fetchBundle).mockResolvedValue({ ...bundle, visibility: 'private' });
    open('/bundles/tools/customize?clone=1');
    expect(await screen.findByText(t('bundle.loadError'))).toBeInTheDocument();
    expect(accountApi.createOwnBundle).not.toHaveBeenCalled();
  });

});
