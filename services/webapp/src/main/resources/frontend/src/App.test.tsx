import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { ScraperQueues } from './components/admin/ScraperQueues';
import * as accountApi from './api/account';
import * as adminAppsApi from './api/adminApps';
import * as bundlesApi from './api/bundles';
import * as catalogAppsApi from './api/catalogApps';
import { clearCatalogPageCache } from './pages/catalog/CatalogPages';
import * as downloadsApi from './api/downloads';
import * as delivery from './downloads/delivery';
import type { BundleDetails, BundleSummary, CatalogApp, CatalogResponse, ScraperQueueState } from './types/catalog';

vi.mock('./components/PageMetadata', () => ({ PageMetadata: () => null }));

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, String(value)),
  };
}

const catalogApp: CatalogApp = {
  id: 'app-1',
  slug: 'app-one',
  packageId: 'Example.App',
  name: 'Aplicación reciente',
  publisher: 'Example',
  tags: [],
  operatingSystems: ['windows'],
  sourceLabel: 'Winstall',
  resolutionStatus: 'direct',
  validationStatus: 'valid',
  downloadable: true,
  updatedAt: '2026-07-16T08:00:00Z',
};

const officialBundle: BundleSummary = {
  id: 'bundle-1',
  slug: 'launchers',
  name: 'Launchers',
  description: 'Launchers de videojuegos',
  creatorUsername: 'Joseleelsuper',
  type: 'official',
  visibility: 'public',
  starCount: 0,
  appCount: 1,
  tags: ['juegos'],
  operatingSystems: ['windows'],
  platformAvailability: [{
    operatingSystem: 'windows',
    downloadableAppCount: 1,
    previewApps: [catalogApp],
  }],
  previewApps: [catalogApp],
  updatedAt: '2026-07-16T08:00:00Z',
};

function LocationProbe() {
  return <output data-testid="location-search">{useLocation().search}</output>;
}

beforeEach(() => {
  window.sessionStorage.clear();
});
describe('catalog workspace', () => {
  beforeEach(() => {
    clearCatalogPageCache();
    const storage = memoryStorage();
    Object.defineProperty(window, 'localStorage', { configurable: true, value: storage });
    vi.stubGlobal('localStorage', storage);
    vi.spyOn(accountApi, 'me').mockResolvedValue(null);
    vi.spyOn(catalogAppsApi, 'connectCatalogEvents').mockReturnValue(() => undefined);
    vi.spyOn(downloadsApi, 'connectDownloadJobEvents').mockReturnValue(() => undefined);
    vi.spyOn(downloadsApi, 'reportDownloadActivity').mockResolvedValue(undefined);
    vi.spyOn(catalogAppsApi, 'fetchApps').mockResolvedValue({
      data: [],
      page: 1,
      pageSize: 12,
      total: 0,
    });
    vi.spyOn(catalogAppsApi, 'fetchCatalogStats').mockResolvedValue({
      total: 0,
      filters: { all: 0, available: 0, review: 0, missing: 0 },
      generatedAt: '2026-07-13T12:00:00Z',
    });
    vi.spyOn(catalogAppsApi, 'fetchCatalogFacets').mockResolvedValue({
      tags: [],
      publishers: [],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('keeps the three desktop grid regions in place while the filter rail is hidden', async () => {
    window.localStorage.setItem('catalog.filters.open', 'false');
    const { container } = render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );

    const workspace = container.querySelector('main.workspace');
    expect(workspace).toHaveClass('filters-hidden');
    expect(workspace?.children[0]).toHaveClass('filter-rail-shell');
    expect(workspace?.children[0]).toHaveAttribute('hidden');
    expect(workspace?.children[1]).toHaveClass('filter-bookmark');
    expect(workspace?.children[2]).toHaveClass('catalog-panel');
    expect(workspace?.children).toHaveLength(3);
    expect(container.querySelector('.details-drawer')).not.toBeInTheDocument();

    const bookmark = screen.getByRole('button', { name: 'Abrir filtros' });
    expect(bookmark).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(bookmark);

    expect(workspace).not.toHaveClass('filters-hidden');
    expect(workspace?.children[0]).not.toHaveAttribute('hidden');
    expect(window.localStorage.getItem('catalog.filters.open')).toBe('true');
    await waitFor(() => expect(catalogAppsApi.fetchApps).toHaveBeenCalled());
  });

  it('keeps the public footer available after the catalog workspace', async () => {
    const { container } = render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );

    await waitFor(() => expect(catalogAppsApi.fetchApps).toHaveBeenCalled());
    const shell = container.querySelector('.site-shell-app');
    const workspace = shell?.querySelector('.workspace');
    const footer = shell?.querySelector('.site-footer');

    expect(workspace).not.toBeNull();
    expect(footer).not.toBeNull();
    expect(workspace?.nextElementSibling).toBe(footer);
    expect(within(footer as HTMLElement).getByRole('heading', { name: 'Páginas' })).toBeInTheDocument();
  });

  it('renders catalog totals even when the app page request fails', async () => {
    vi.mocked(catalogAppsApi.fetchApps).mockRejectedValue(new Error('request_failed_500'));
    vi.mocked(catalogAppsApi.fetchCatalogStats).mockResolvedValue({
      total: 13_493,
      filters: { all: 13_493, available: 13_404, review: 88, missing: 1 },
      generatedAt: '2026-07-16T08:00:00Z',
    });

    render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findAllByText('13.493')).toHaveLength(1);
    expect(screen.getByText('13.404')).toBeInTheDocument();
    expect(screen.getByText('88')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(await screen.findByText('No se pudo cargar el catálogo.')).toBeInTheDocument();
  });

  it('defaults to semantic and available, then persists explicit search and status choices', async () => {
    render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    const semantic = await screen.findByRole('button', { name: 'IA semántica' });
    expect(semantic).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Disponibles/ })).toHaveClass('filter-item-active');
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent('searchMode=semantic');
    });
    const latestFetchAppsCall = vi.mocked(catalogAppsApi.fetchApps).mock.calls[
      vi.mocked(catalogAppsApi.fetchApps).mock.calls.length - 1
    ];
    expect(latestFetchAppsCall?.[0].filter).toBe('available');

    fireEvent.click(screen.getByRole('button', { name: 'Literal' }));
    fireEvent.click(screen.getByRole('button', { name: /Todas/ }));

    expect(window.localStorage.getItem('catalog.search.mode')).toBe('lexical');
    expect(window.localStorage.getItem('catalog.filter.status')).toBe('all');
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent('searchMode=lexical');
      expect(screen.getByTestId('location-search')).toHaveTextContent('status=all');
    });
  });

  it('shows the alphabet only for name ordering and jumps to the first page of a letter', async () => {
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [],
      page: 1,
      pageSize: 12,
      total: 30,
      alphabet: [
        { letter: '#', page: 1, count: 2 },
        { letter: 'A', page: 1, count: 15 },
        { letter: 'C', page: 3, count: 13 },
      ],
    });

    render(
      <MemoryRouter initialEntries={['/catalog?status=all&sort=name&searchMode=lexical']}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    const alphabet = await screen.findByRole('navigation', {
      name: 'Índice alfabético de aplicaciones',
    });
    expect(within(alphabet).getByRole('button', {
      name: 'Ir a la letra A, 15 aplicaciones',
    })).toBeEnabled();
    expect(within(alphabet).getByRole('button', {
      name: 'No hay aplicaciones que empiecen por B',
    })).toBeDisabled();

    fireEvent.click(within(alphabet).getByRole('button', {
      name: 'Ir a la letra C, 13 aplicaciones',
    }));

    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent('page=3');
    });
  });

  it('hides the alphabet for non-alphabetical ordering', async () => {
    render(
      <MemoryRouter initialEntries={['/catalog?status=all&sort=updated&searchMode=lexical']}>
        <App />
      </MemoryRouter>,
    );

    await waitFor(() => expect(catalogAppsApi.fetchApps).toHaveBeenCalled());
    expect(screen.queryByRole('navigation', {
      name: 'Índice alfabético de aplicaciones',
    })).not.toBeInTheDocument();
  });

  it('shows a brief notice when a semantic request degrades as a whole', async () => {
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [],
      page: 1,
      pageSize: 12,
      total: 0,
      requestedMode: 'semantic',
      appliedMode: 'lexical',
      degradedReason: 'semantic_index_unavailable',
    });

    render(
      <MemoryRouter initialEntries={['/catalog?searchMode=semantic&query=editor']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText(
      'La búsqueda semántica no está disponible temporalmente; se muestran resultados literales.',
    )).toBeInTheDocument();
    expect(window.localStorage.getItem('catalog.search.mode')).toBeNull();
  });

  it('explains when semantic search falls back because the query is too short', async () => {
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [],
      page: 1,
      pageSize: 12,
      total: 0,
      requestedMode: 'semantic',
      appliedMode: 'lexical',
      degradedReason: 'semantic_query_too_short',
    });

    render(
      <MemoryRouter initialEntries={['/catalog?searchMode=semantic&query=Steam']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText(
      'La búsqueda semántica necesita una consulta más descriptiva; se muestran resultados literales.',
    )).toBeInTheDocument();
  });

  it('restores the saved catalog status when the URL does not choose one', async () => {
    window.localStorage.setItem('catalog.filter.status', 'review');

    render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent('status=review');
    });
    expect(screen.getByRole('button', { name: /Revisión/ })).toHaveClass('filter-item-active');
    expect(catalogAppsApi.fetchApps).toHaveBeenLastCalledWith(
      expect.objectContaining({ filter: 'review' }),
      expect.any(AbortSignal),
    );
  });

  it('clears every selected tag from the filter rail without changing other filters', async () => {
    render(
      <MemoryRouter initialEntries={[
        '/catalog?status=all&tag=quiz&tag=game&searchMode=semantic',
      ]}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', {
      name: 'Eliminar todos los tags seleccionados',
    }));

    await waitFor(() => {
      const search = screen.getByTestId('location-search').textContent ?? '';
      expect(search).toContain('status=all');
      expect(search).toContain('searchMode=semantic');
      expect(search).not.toContain('tag=');
    });
  });

  it('clears the selected publisher from the filter rail without changing other filters', async () => {
    render(
      <MemoryRouter initialEntries={[
        '/catalog?status=all&publisher=Riot+Games%2C+Inc&searchMode=semantic',
      ]}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', {
      name: 'Eliminar el editor seleccionado',
    }));

    await waitFor(() => {
      const search = screen.getByTestId('location-search').textContent ?? '';
      expect(search).toContain('status=all');
      expect(search).toContain('searchMode=semantic');
      expect(search).not.toContain('publisher=');
    });
  });

  it('chains selected tags with AND while refreshing the compatible directory', async () => {
    vi.mocked(catalogAppsApi.fetchCatalogFacets).mockImplementation(async ({ tags }) => ({
      tags: tags?.includes('automation')
        ? [{ label: 'cli', value: 'cli', normalizedValue: 'cli', letter: 'C', count: 4 }]
        : [{ label: 'automation', value: 'automation', normalizedValue: 'automation', letter: 'A', count: 8 }],
      publishers: [],
    }));

    render(
      <MemoryRouter initialEntries={['/catalog/tags?status=all&searchMode=lexical']}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: /automation/i }));
    await waitFor(() => expect(catalogAppsApi.fetchCatalogFacets).toHaveBeenLastCalledWith(
      expect.objectContaining({ tags: ['automation'], publisher: undefined }),
    ));
    expect(await screen.findByRole('button', { name: /cli/i })).toBeInTheDocument();
    expect(screen.queryByText('Coincidencias mínimas')).not.toBeInTheDocument();
  });

  it('uses a selected tag prefetch when returning from its directory', async () => {
    vi.mocked(catalogAppsApi.fetchCatalogFacets).mockResolvedValue({
      tags: [{ label: 'automation', value: 'automation', normalizedValue: 'automation', letter: 'A', count: 1 }],
      publishers: [],
    });
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [catalogApp], page: 1, pageSize: 12, total: 1,
    });

    render(
      <MemoryRouter initialEntries={['/catalog/tags?searchMode=lexical']}>
        <App />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: /automation/i }));
    await waitFor(() => expect(catalogAppsApi.fetchApps).toHaveBeenCalledWith(
      expect.objectContaining({ tags: ['automation'] }), undefined,
    ));
    await act(async () => Promise.resolve());
    fireEvent.click(screen.getByRole('link', { name: 'Volver al catálogo' }));

    expect(screen.getByText('Aplicación reciente')).toBeInTheDocument();
    expect(screen.queryByText('Cargando...')).not.toBeInTheDocument();
    expect(catalogAppsApi.fetchApps).toHaveBeenCalledTimes(1);
  });

  it('replaces and then removes the singular editor selection', async () => {
    vi.mocked(catalogAppsApi.fetchCatalogFacets).mockResolvedValue({
      tags: [],
      publishers: [
        { label: 'ACME', value: 'ACME', normalizedValue: 'acme', letter: 'A', count: 8 },
        { label: 'Beta', value: 'Beta', normalizedValue: 'beta', letter: 'B', count: 3 },
      ],
    });

    render(
      <MemoryRouter initialEntries={['/catalog/editors?status=all&searchMode=lexical']}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    const acme = await screen.findByRole('button', { name: /ACME/ });
    fireEvent.click(acme);
    await waitFor(() => expect(screen.getByTestId('location-search')).toHaveTextContent('publisher=ACME'));

    fireEvent.click(screen.getByRole('button', { name: 'B' }));
    fireEvent.click(screen.getByRole('button', { name: /Beta/ }));
    await waitFor(() => expect(screen.getByTestId('location-search')).toHaveTextContent('publisher=Beta'));

    fireEvent.click(screen.getByRole('button', { name: /Beta/ }));
    await waitFor(() => expect(screen.getByTestId('location-search')).not.toHaveTextContent('publisher='));
    expect(catalogAppsApi.fetchCatalogFacets).toHaveBeenLastCalledWith(
      expect.objectContaining({ publisher: undefined }),
    );
  });

  it('recovers empty facets by clearing only tags and editor', async () => {
    vi.mocked(catalogAppsApi.fetchCatalogFacets).mockResolvedValue({ tags: [], publishers: [] });

    render(
      <MemoryRouter initialEntries={[
        '/catalog/tags?query=editor&status=review&os=linux&architecture=x64&tag=automation&publisher=ACME&searchMode=semantic',
      ]}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    expect(await screen.findByText('No hay filtros compatibles con la selección actual.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Restablecer tags y editor' }));

    await waitFor(() => {
      const search = screen.getByTestId('location-search').textContent ?? '';
      expect(search).toContain('query=editor');
      expect(search).toContain('status=review');
      expect(search).toContain('os=linux');
      expect(search).toContain('architecture=x64');
      expect(search).toContain('searchMode=semantic');
      expect(search).not.toContain('tag=');
      expect(search).not.toContain('publisher=');
    });
  });

  it('coalesces bursts of catalog events into a single refresh', async () => {
    vi.useFakeTimers();
    let notifyCatalogChanged: (() => void) | undefined;
    vi.mocked(catalogAppsApi.connectCatalogEvents).mockImplementation((onEvent) => {
      notifyCatalogChanged = () => onEvent({
        type: 'catalog.changed',
        version: '1',
        generatedAt: '2026-07-16T08:00:00Z',
      });
      return () => undefined;
    });

    render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );
    await act(async () => Promise.resolve());
    expect(catalogAppsApi.fetchApps).toHaveBeenCalledTimes(1);

    act(() => {
      notifyCatalogChanged?.();
      notifyCatalogChanged?.();
      notifyCatalogChanged?.();
    });
    expect(catalogAppsApi.fetchApps).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(catalogAppsApi.fetchApps).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });

  it('removes a selected app deleted during a catalog refresh', async () => {
    vi.useFakeTimers();
    let notifyCatalogChanged: (() => void) | undefined;
    vi.mocked(catalogAppsApi.connectCatalogEvents).mockImplementation((onEvent) => {
      notifyCatalogChanged = () => onEvent({
        type: 'catalog.changed',
        version: '2',
        generatedAt: '2026-07-18T12:00:00Z',
      });
      return () => undefined;
    });
    vi.mocked(catalogAppsApi.fetchApps)
      .mockResolvedValueOnce({ data: [catalogApp], page: 1, pageSize: 12, total: 1 })
      .mockResolvedValueOnce({ data: [], page: 1, pageSize: 12, total: 0 });
    vi.spyOn(catalogAppsApi, 'fetchAppDetails').mockRejectedValue(new Error('request_failed_404'));

    render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );
    await act(async () => Promise.resolve());
    fireEvent.click(screen.getByRole('checkbox', { name: 'Seleccionar Aplicación reciente' }));
    expect(screen.getByText('1/100')).toBeInTheDocument();

    act(() => notifyCatalogChanged?.());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
      await Promise.resolve();
    });
    vi.useRealTimers();

    await waitFor(() => expect(screen.getByText('0/100')).toBeInTheDocument());
    expect(catalogAppsApi.fetchAppDetails).toHaveBeenCalledWith('app-1', expect.any(AbortSignal));
  });

  it('preserves selections when navigating to another catalog page', async () => {
    const secondPageApp: CatalogApp = {
      ...catalogApp,
      id: 'app-2',
      name: 'Aplicación de la segunda página',
    };
    vi.mocked(catalogAppsApi.fetchApps).mockImplementation(async ({ page }) => ({
      data: page === 2 ? [secondPageApp] : [catalogApp],
      page,
      pageSize: 12,
      total: 13,
    }));

    render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Aplicación reciente')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Seleccionar Aplicación reciente' }));
    const pagination = screen.getByText(/Mostrando 1 a 12 de 13 resultados/).closest('footer');
    expect(pagination).not.toBeNull();
    const nextPage = within(pagination!).getByRole('link', { name: 'Página siguiente' });
    expect(nextPage).toHaveAttribute('href', '/catalog?page=2&searchMode=semantic');
    fireEvent.click(nextPage);

    expect(await screen.findByText('Aplicación de la segunda página')).toBeInTheDocument();
    expect(screen.getByText('1/100')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Quitar Aplicación reciente de la selección' }));
    expect(screen.getByText('0/100')).toBeInTheDocument();
  });

  it('keeps filtered catalog pagination out of crawlable links', async () => {
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [catalogApp], page: 1, pageSize: 12, total: 13,
    });
    render(
      <MemoryRouter initialEntries={['/catalog?query=editor']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('button', { name: 'Página siguiente' })).toBeEnabled();
    expect(screen.queryByRole('link', { name: 'Página siguiente' })).not.toBeInTheDocument();
  });

  it('revalidates selected apps before sending a download job', async () => {
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [catalogApp],
      page: 1,
      pageSize: 12,
      total: 1,
    });
    vi.spyOn(catalogAppsApi, 'fetchAppDetails').mockResolvedValue({
      ...catalogApp,
      resolutionStatus: 'requires_manual_review',
      validationStatus: 'unchecked',
      downloadable: false,
      notes: '',
    });
    const createDownloadJob = vi.spyOn(downloadsApi, 'createDownloadJob');

    render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Aplicación reciente')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Seleccionar Aplicación reciente' }));
    fireEvent.click(screen.getByRole('button', { name: 'Descargar ZIP' }));

    await waitFor(() => expect(catalogAppsApi.fetchAppDetails).toHaveBeenCalledWith('app-1'));
    await waitFor(() => expect(screen.getByText('0/100')).toBeInTheDocument());
    expect(createDownloadJob).not.toHaveBeenCalled();
  });

  it('sends only apps that remain selectable after validation', async () => {
    const picker = vi.spyOn(delivery, 'chooseDownloadDestination').mockResolvedValue({ createWritable: vi.fn() });
    const staleApp: CatalogApp = {
      ...catalogApp,
      id: 'app-2',
      name: 'Aplicación obsoleta',
    };
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [catalogApp, staleApp],
      page: 1,
      pageSize: 12,
      total: 2,
    });
    vi.spyOn(catalogAppsApi, 'fetchAppDetails').mockImplementation(async (id) => ({
      ...(id === catalogApp.id ? catalogApp : staleApp),
      resolutionStatus: id === catalogApp.id ? 'direct' : 'requires_manual_review',
      validationStatus: id === catalogApp.id ? 'valid' : 'unchecked',
      downloadable: id === catalogApp.id,
      notes: '',
    }));
    const createDownloadJob = vi.spyOn(downloadsApi, 'createDownloadJob').mockResolvedValue({
      id: 'job-1',
      status: 'QUEUED',
      failureCode: null,
      progress: 0,
      requestedCount: 1,
      acceptedCount: 1,
      omittedCount: 0,
      items: [],
      createdAt: '2026-07-18T12:00:00Z',
      expiresAt: '2026-07-19T12:00:00Z',
    });

    render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Aplicación obsoleta')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Seleccionar Aplicación reciente' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Seleccionar Aplicación obsoleta' }));
    fireEvent.click(screen.getByRole('button', { name: 'Descargar ZIP' }));
    expect(picker).toHaveBeenCalledOnce();

    await waitFor(() => expect(createDownloadJob).toHaveBeenCalledWith({
      appIds: ['app-1'],
      operatingSystems: undefined,
    }));
    expect(screen.getByText('1/100')).toBeInTheDocument();
    expect(picker).toHaveBeenCalledOnce();
  });

  it('removes legacy pending status from the URL and loads Disponibles', async () => {
    render(
      <MemoryRouter initialEntries={['/catalog?status=pending&query=editor']}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByTestId('location-search')).toHaveTextContent('?query=editor'));
    expect(screen.queryByText('Pendientes')).not.toBeInTheDocument();
    expect(catalogAppsApi.fetchApps).toHaveBeenLastCalledWith(
      expect.objectContaining({ filter: 'available', query: 'editor' }),
      expect.any(AbortSignal),
    );
  });

  it('hides the previous page without a loading label while a different filter is pending', async () => {
    let resolveReview!: (response: CatalogResponse) => void;
    vi.mocked(catalogAppsApi.fetchApps).mockImplementation(({ filter }) => {
      if (filter === 'review') return new Promise((resolve) => {
        resolveReview = resolve;
      });
      return Promise.resolve({ data: [catalogApp], page: 1, pageSize: 12, total: 1 });
    });

    render(
      <MemoryRouter initialEntries={['/catalog?status=available']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Aplicación reciente')).toBeInTheDocument();
    await waitFor(() => expect(resolveReview).toBeTypeOf('function'));
    fireEvent.click(screen.getByRole('button', { name: /Revisión/ }));

    expect(screen.queryByText('Aplicación reciente')).not.toBeInTheDocument();
    expect(screen.queryByText('Cargando...')).not.toBeInTheDocument();

    await act(async () => {
      resolveReview({ data: [], page: 1, pageSize: 12, total: 0 });
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(await screen.findByText('No hay aplicaciones que coincidan con la búsqueda.')).toBeInTheDocument();
  });

  it('shows a prefetched filter immediately without requesting it again', async () => {
    const reviewApp = { ...catalogApp, id: 'review-1', name: 'Aplicación en revisión' };
    vi.mocked(catalogAppsApi.fetchApps).mockImplementation(async ({ filter }) => ({
      data: filter === 'review' ? [reviewApp] : [catalogApp],
      page: 1,
      pageSize: 12,
      total: 1,
    }));

    render(
      <MemoryRouter initialEntries={['/catalog?status=available']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Aplicación reciente')).toBeInTheDocument();
    await waitFor(() => expect(vi.mocked(catalogAppsApi.fetchApps).mock.calls.some(
      ([params]) => params.filter === 'review',
    )).toBe(true));
    await act(async () => Promise.resolve());
    const requests = vi.mocked(catalogAppsApi.fetchApps).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: /Revisión/ }));

    expect(screen.getByText('Aplicación en revisión')).toBeInTheDocument();
    expect(screen.queryByText('Cargando...')).not.toBeInTheDocument();
    expect(catalogAppsApi.fetchApps).toHaveBeenCalledTimes(requests);
  });

  it('loads an application into an expandable row and closes it from the same chevron', async () => {
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [catalogApp],
      page: 1,
      pageSize: 12,
      total: 1,
    });
    vi.spyOn(catalogAppsApi, 'fetchAppDetails').mockResolvedValue({
      ...catalogApp,
      longDescription: 'Detalle disponible debajo de la fila.',
      officialUrl: 'https://example.test',
      originUrl: 'https://winstall.app/apps/Example.App',
      notes: '',
      downloadOptions: [],
    });

    render(
      <MemoryRouter initialEntries={['/catalog?status=all']}>
        <App />
      </MemoryRouter>,
    );

    const open = await screen.findByRole('button', {
      name: 'Mostrar detalles de Aplicación reciente',
    });
    fireEvent.click(open);

    await waitFor(() => expect(catalogAppsApi.fetchAppDetails).toHaveBeenCalledWith('app-1'));
    expect(await screen.findByText('Detalle disponible debajo de la fila.')).toBeInTheDocument();
    const close = screen.getByRole('button', {
      name: 'Ocultar detalles de Aplicación reciente',
    });
    expect(close).toHaveAttribute('aria-expanded', 'true');

    fireEvent.click(close);

    expect(screen.getByRole('button', {
      name: 'Mostrar detalles de Aplicación reciente',
    })).toHaveAttribute('aria-expanded', 'false');
    expect(document.querySelector('.app-detail-row')).not.toHaveClass('app-detail-row-open');
  });

  it('shows the requested app even when it is outside the current catalog page', async () => {
    vi.mocked(catalogAppsApi.fetchApps).mockResolvedValue({
      data: [catalogApp], page: 1, pageSize: 12, total: 100,
    });
    vi.spyOn(catalogAppsApi, 'fetchAppDetails').mockResolvedValue({
      ...catalogApp, id: 'app-2', name: 'Aplicación solicitada',
      longDescription: 'Ficha específica accesible desde su URL.', notes: '', downloadOptions: [],
    });

    render(<MemoryRouter initialEntries={['/catalog/app/app-2']}><App /></MemoryRouter>);

    expect(await screen.findByText('Ficha específica accesible desde su URL.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ocultar detalles de Aplicación solicitada' }))
      .toHaveAttribute('aria-expanded', 'true');
  });
});

describe('home loading', () => {
  beforeEach(() => {
    vi.spyOn(accountApi, 'me').mockResolvedValue(null);
    vi.spyOn(bundlesApi, 'fetchBundles').mockImplementation(async (params) => ({
      data: params.type === 'official' ? [officialBundle] : [],
      page: 1,
      pageSize: 3,
      total: params.type === 'official' ? 1 : 0,
    }));
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('keeps successful bundles visible when the recent-app request fails', async () => {
    vi.spyOn(catalogAppsApi, 'fetchApps').mockRejectedValue(new Error('request_failed_500'));

    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Launchers')).toBeInTheDocument();
    expect(screen.getByText('Launchers de videojuegos')).toBeInTheDocument();
    expect(screen.getByText('Creado por José Gallardo')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Windows: 1 aplicaciones disponibles' })).toBeInTheDocument();
    expect(await screen.findByText('No se pudo cargar la página principal.')).toBeInTheDocument();
  });

  it('abre las aplicaciones recientes con la ordenacion por actualizacion', async () => {
    const recentApps = Array.from({ length: 6 }, (_, index) => ({
      ...catalogApp,
      id: `recent-${index + 1}`,
      name: `Reciente ${index + 1}`,
    }));
    vi.spyOn(catalogAppsApi, 'fetchApps').mockResolvedValue({
      data: recentApps,
      page: 1,
      pageSize: 6,
      total: recentApps.length,
    });

    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('link', { name: /Reciente 1/ }))
      .toHaveAttribute('href', '/catalog/app/recent-1?sort=updated');
    expect(screen.getByRole('link', { name: 'Ver todo' }))
      .toHaveAttribute('href', '/catalog?sort=updated');
  });

  it('calcula el indicador restante a partir de las miniaturas que realmente muestra', async () => {
    const previewApps = Array.from({ length: 6 }, (_, index) => ({
      ...catalogApp,
      id: `app-${index + 1}`,
      slug: `app-${index + 1}`,
      packageId: `Example.App${index + 1}`,
      name: `Aplicación ${index + 1}`,
    }));
    const bundleWithOverflow = {
      ...officialBundle,
      appCount: 17,
      platformAvailability: [{
        operatingSystem: 'windows',
        downloadableAppCount: 17,
        previewApps,
      }],
      previewApps,
    } satisfies BundleSummary;
    vi.mocked(bundlesApi.fetchBundles).mockImplementation(async (params) => ({
      data: params.type === 'official' ? [bundleWithOverflow] : [],
      page: 1,
      pageSize: 3,
      total: params.type === 'official' ? 1 : 0,
    }));
    vi.spyOn(catalogAppsApi, 'fetchApps').mockResolvedValue({
      data: [],
      page: 1,
      pageSize: 6,
      total: 0,
    });

    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByText('+12')).toBeInTheDocument();
    expect(container.querySelectorAll('.bundle-card .mini-apps .mini-icon')).toHaveLength(5);
    expect(container.querySelector('.bundle-card-header-home .bundle-card-count')).toHaveTextContent('17 apps');
    expect(container.querySelector('.bundle-card-preview')).toContainElement(screen.getByText('+12'));
    expect(container.querySelector('.bundle-card > .bundle-download-action-compact')).not.toBeNull();
  });

  it('muestra el selector de SO del bundle en su detalle', async () => {
    vi.spyOn(bundlesApi, 'fetchBundle').mockResolvedValue({
      ...officialBundle,
      apps: [catalogApp],
    } satisfies BundleDetails);

    render(
      <MemoryRouter initialEntries={['/bundles/launchers']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('button', { name: 'Windows: 1 aplicaciones disponibles' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('button', { name: 'Linux' })).not.toBeInTheDocument();
  });
});

describe('public support pages', () => {
  beforeEach(() => {
    vi.spyOn(accountApi, 'me').mockResolvedValue(null);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('explains the collected data and provides a private rights channel', async () => {
    render(<MemoryRouter initialEntries={['/privacy']}><App /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Política de privacidad', level: 1 })).toBeInTheDocument();
    expect(screen.getByText(/José Gallardo es el responsable/)).toBeInTheDocument();
    expect(screen.getByText(/BATCH_SESSION/)).toHaveTextContent('BATCH_DOWNLOAD_OWNER');
    expect(screen.getByText(/Resend solo se utiliza/)).toHaveTextContent('no enviamos publicidad');
    expect(screen.getByText(/no implica el borrado inmediato/)).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Contacto: JoseGallardoC@protonmail.com' })[0])
      .toHaveAttribute('href', 'mailto:JoseGallardoC@protonmail.com');
    expect(screen.getByRole('link', { name: 'Derechos y reclamaciones ante la AEPD' }))
      .toHaveAttribute('href', 'https://www.aepd.es/derechos-y-deberes/conoce-tus-derechos');
  });

  it('keeps an unknown route on a real not-found page', async () => {
    render(<MemoryRouter initialEntries={['/no-existe']}><App /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Página no encontrada', level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver al inicio' })).toHaveAttribute('href', '/');
  });

  it('shows the generic public error for an unknown code', async () => {
    render(
      <MemoryRouter initialEntries={['/error?code=legacy']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('alert')).toHaveTextContent('Ha ocurrido un error');
    expect(screen.getByRole('link', { name: 'Volver al login' })).toHaveAttribute('href', '/login');
  });

  it('renders the three footer columns with real internal and external destinations', async () => {
    render(
      <MemoryRouter initialEntries={['/terms']}>
        <App />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: 'Términos y condiciones', level: 1 })).toBeInTheDocument();
    expect(screen.getByText('Última actualización')).toBeInTheDocument();
    expect(screen.getByText(/de \w+ de \d{4}/)).toHaveAttribute('datetime');
    expect(screen.getByRole('heading', { name: 'Páginas' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Legales' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Proyecto' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Política de privacidad' })).toHaveAttribute('href', '/privacy');
    expect(screen.getByRole('link', { name: 'Portfolio' })).toHaveAttribute(
      'href',
      'https://joseleelportfolio.vercel.app/',
    );
    expect(screen.getByRole('link', { name: 'Código fuente' })).toHaveAttribute(
      'href',
      'https://github.com/Joseleelsuper/Batch-Downloader',
    );
    expect(screen.getByRole('link', { name: 'Memoria' })).toHaveAttribute(
      'href',
      'https://github.com/Joseleelsuper/Batch-Downloader-Memoria/releases/latest',
    );
    expect(screen.getByRole('link', { name: 'Portfolio' }).querySelector('svg')).not.toBeNull();
  });
});

describe('admin bundle editor', () => {
  const candidateApp: CatalogApp = {
    ...catalogApp,
    id: 'app-2',
    slug: 'app-two',
    packageId: 'Example.AppTwo',
    name: 'Aplicación candidata',
  };

  beforeEach(() => {
    vi.spyOn(accountApi, 'me').mockResolvedValue({
      id: '00000000-0000-0000-0000-000000000001',
      username: 'admin',
      email: 'admin@example.test',
      emailVerified: true,
      role: 'ADMIN',
      createdAt: '2026-08-08T00:00:00Z',
    });
    vi.spyOn(bundlesApi, 'fetchBundles').mockResolvedValue({
      data: [officialBundle],
      page: 1,
      pageSize: 30,
      total: 1,
    });
    vi.spyOn(bundlesApi, 'fetchBundle').mockResolvedValue({
      ...officialBundle,
      apps: [catalogApp],
    });
    vi.spyOn(adminAppsApi, 'fetchAdminApps').mockResolvedValue({
      data: [catalogApp, candidateApp],
      page: 1,
      pageSize: 12,
      total: 2,
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('marca las aplicaciones añadidas e impide duplicarlas en el bundle', async () => {
    render(
      <MemoryRouter initialEntries={['/admin/bundles']}>
        <App />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: /Launchers/ }));
    const add = await screen.findByRole('button', { name: 'Añadir Aplicación candidata' });
    expect(screen.getByRole('button', { name: `${catalogApp.name} ya está en el bundle` })).toBeDisabled();
    fireEvent.click(add);
    expect(screen.getByRole('button', { name: 'Aplicación candidata ya está en el bundle' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Quitar Aplicación candidata' })).toBeEnabled();
  });
});

function queue(queueName: string, appName: string): ScraperQueueState {
  return {
    queue: queueName,
    queued: 1,
    inProgress: 0,
    completed: 0,
    discarded: 0,
    failed: 0,
    items: [{
      id: `${queueName}-1`,
      packageId: `${queueName}.package`,
      appName,
      status: 'queued',
      attempts: 0,
      updatedAt: '2026-07-13T12:00:00Z',
    }],
  };
}

describe('scraper pipeline', () => {
  afterEach(() => cleanup());

  it('presents SO Filter and ignores the removed icon enrichment queue', () => {
    const { container } = render(<ScraperQueues queues={[
      queue('scraper_so_filter', 'Pendiente de clasificar'),
      queue('so_filter_descriptor', 'Pendiente de descripción'),
      queue('icon_enrichment', 'Trabajo de iconos antiguo'),
    ]} />);

    expect(container.querySelector('.pipeline-stages')).not.toBeNull();
    expect(container.querySelector('.pipeline-queues')).not.toBeNull();
    expect(screen.getByText('Scraper -> SO Filter')).toBeInTheDocument();
    expect(screen.getByText('SO Filter')).toBeInTheDocument();
    expect(screen.getByText('SO Filter -> Descriptor')).toBeInTheDocument();
    expect(screen.getByText('Pendiente de clasificar')).toBeInTheDocument();
    expect(screen.queryByText('Trabajo de iconos antiguo')).not.toBeInTheDocument();
    expect(screen.queryByText('Iconos')).not.toBeInTheDocument();
  });

  it('ignores the removed descriptor queue after its data migration', () => {
    render(<ScraperQueues queues={[queue('scraper_descriptor', 'Trabajo antiguo')]} />);

    expect(screen.queryByText('Trabajo antiguo')).not.toBeInTheDocument();
    expect(screen.getByText('SO Filter -> Descriptor')).toBeInTheDocument();
  });
});
