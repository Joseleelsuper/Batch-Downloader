import { Suspense } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as accountApi from '../../api/account';
import * as bundleApi from '../../api/bundles';
import * as catalogApi from '../../api/catalogApps';
import type { AuthUser, BundleDetails, CatalogApp } from '../../types/catalog';
import { I18nProvider, t, useLocaleSelector } from '../../services/i18n';
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

function LanguageControls() {
  const { selectLocale } = useLocaleSelector();
  return <><button onClick={() => selectLocale('es')}>Español</button><button onClick={() => selectLocale('en')}>English</button></>;
}

const localeFetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 304 }));

function open(path: string, localized = false) {
  const page = <MemoryRouter initialEntries={[path]}><Suspense fallback={null}><Routes>
    <Route path="/bundles/:slug/customize" element={<BundleDraftPage />} />
    <Route path="/bundle-proposal" element={<BundleDraftPage />} />
    <Route path="/dashboard/bundles/:id/edit" element={<p>Saved private copy</p>} />
  </Routes></Suspense></MemoryRouter>;
  return render(localized ? <I18nProvider fetcher={localeFetcher}><LanguageControls />{page}</I18nProvider> : page);
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
    fireEvent.click(await screen.findByRole('button', { name: 'Quitar First' }));
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
    fireEvent.click(await screen.findByRole('button', { name: 'Quitar First' }));
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

  it('propone desde la búsqueda semántica, conserva los filtros y explica el origen sin llamar a un LLM', async () => {
    open('/bundle-proposal?query=editar+vídeo&os=windows&tag=tools');
    expect(await screen.findByRole('button', { name: 'Quitar Third' })).toBeInTheDocument();
    expect(catalogApi.fetchApps).toHaveBeenCalledWith(expect.objectContaining({ query: 'editar vídeo',
      tags: ['tools'], operatingSystems: ['windows'], searchMode: 'semantic', sort: 'relevance' }), expect.any(AbortSignal));
    expect(screen.getByText(/^Propuesta de la búsqueda semántica/)).toHaveAttribute('role', 'status');
    expect(state.start).not.toHaveBeenCalled();
    expect(accountApi.createOwnBundle).not.toHaveBeenCalled();
  });

  it('permite selección manual cuando la búsqueda semántica se degrada a literal', async () => {
    vi.mocked(catalogApi.fetchApps).mockResolvedValue({ data: [added], page: 1, pageSize: 12, total: 1,
      appliedMode: 'lexical', degradedReason: 'semantic_unavailable' });
    open('/bundle-proposal?query=editar+vídeo');
    expect(await screen.findByText(t('bundle.draft.unavailable'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Quitar Third' })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Añadir Third' }));
    expect(screen.getByRole('button', { name: 'Descargar bundle (1)' })).toBeEnabled();
  });

  it('muestra la explicación en el idioma seleccionado y la actualiza al cambiarlo', async () => {
    const suggestion = { ...added, description: '原始描述 Original description',
      longDescription: 'Permite editar vídeos y crear animaciones.',
      longDescriptionEn: 'Edit videos and create animations.' };
    vi.mocked(catalogApi.fetchApps).mockResolvedValue({ data: [suggestion], page: 1, pageSize: 12, total: 1,
      appliedMode: 'semantic' });
    open('/bundle-proposal?query=editar+vídeo', true);
    fireEvent.click(screen.getByRole('button', { name: 'Español' }));
    expect(await screen.findByText(suggestion.longDescription)).toBeInTheDocument();
    expect(screen.queryByText(suggestion.description)).not.toBeInTheDocument();
    expect(screen.queryByText(suggestion.longDescriptionEn)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'English' }));
    expect(await screen.findByText(suggestion.longDescriptionEn)).toBeInTheDocument();
    expect(screen.queryByText(suggestion.longDescription)).not.toBeInTheDocument();
    expect(screen.queryByText(suggestion.description)).not.toBeInTheDocument();
  });

  it.each([
    { language: 'Español', longDescription: '  ', longDescriptionEn: 'English catalogue description.',
      message: 'No hay una descripción disponible en tu idioma.' },
    { language: 'English', longDescription: 'Descripción española del catálogo.', longDescriptionEn: null,
      message: 'No description is available in your language.' },
  ])('avisa en $language cuando falta su traducción sin sustituirla por otro idioma', async ({ language, longDescription, longDescriptionEn, message }) => {
    const suggestion = { ...added, description: '原始描述 Original description', longDescription, longDescriptionEn };
    vi.mocked(catalogApi.fetchApps).mockResolvedValue({ data: [suggestion], page: 1, pageSize: 12, total: 1,
      appliedMode: 'semantic' });
    open('/bundle-proposal?query=editar+vídeo', true);
    fireEvent.click(screen.getByRole('button', { name: language }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText(suggestion.description)).not.toBeInTheDocument();
    expect(screen.queryByText(longDescription.trim() || longDescriptionEn!)).not.toBeInTheDocument();
  });
});
