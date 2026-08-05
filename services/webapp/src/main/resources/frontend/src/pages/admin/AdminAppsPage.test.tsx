import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as catalogApi from '../../api/catalog';
import type {
  AppDetails,
  CatalogApp,
  ManualInstallerInspection,
  WebsiteAppDiscovery,
} from '../../types/catalog';
import { AdminAppsPage } from './AdminAppsPage';

const unresolvedApp: CatalogApp = {
  id: '11111111-1111-4111-8111-111111111111',
  slug: 'example-app',
  packageId: 'Example.App',
  name: 'Example App',
  publisher: 'Example',
  description: 'Descripción existente',
  longDescription: null,
  tags: [],
  operatingSystems: [],
  iconUrl: null,
  latestVersion: '1.0.0',
  sourceLabel: 'Revisión',
  resolutionStatus: 'requires_manual_review',
  validationStatus: 'unchecked',
  downloadable: false,
  updatedAt: '2026-07-28T08:00:00Z',
};

const secondApp: CatalogApp = {
  ...unresolvedApp,
  id: '22222222-2222-4222-8222-222222222222',
  slug: 'second-app',
  packageId: 'Example.Second',
  name: 'Second App',
};

function details(app: CatalogApp): AppDetails {
  return {
    ...app,
    officialUrl: 'https://example.com',
    originUrl: 'https://winstall.app/apps/Example.App',
    notes: 'Requiere revisión',
    downloadOptions: [],
  };
}

function inspection(
  status: ManualInstallerInspection['status'],
  overrides: Partial<ManualInstallerInspection> = {},
): ManualInstallerInspection {
  const primaryInstaller = status === 'ready' ? {
    finalDomain: 'example.com',
    filename: 'Example-1.2.0.exe',
    extension: '.exe',
    contentType: 'application/x-msdownload',
    sizeBytes: 4096,
    version: '1.2.0',
    operatingSystem: 'windows' as const,
    architecture: 'x86_64',
    platformRequired: false,
  } : null;
  return {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    appId: unresolvedApp.id,
    status,
    phase: status === 'ready' ? 'ready' : 'validating_installer',
    expectedAppVersion: 7,
    warnings: [],
    suggestions: status === 'ready' ? {
      name: { value: 'Example App', source: 'current' },
      publisher: { value: 'Example', source: 'current' },
      officialUrl: { value: 'https://example.com', source: 'current' },
      latestVersion: { value: '1.2.0', source: 'filename' },
      description: { value: 'Descripción existente', source: 'current' },
      longDescription: {
        value: 'Descripción larga generada y revisable.',
        source: 'generated_ai',
      },
      iconUrl: { value: 'https://example.com/icon.png', source: 'open_graph' },
    } : null,
    installer: primaryInstaller,
    ai: status === 'ready' ? {
      status: 'ready',
      provider: 'groq',
      model: 'model-test',
    } : null,
    errorCode: null,
    sourceRef: null,
    createdAt: '2026-07-28T08:00:00Z',
    updatedAt: '2026-07-28T08:00:01Z',
    expiresAt: '2026-07-29T08:00:00Z',
    ...overrides,
    installers: overrides.installers
      ?? ('installer' in overrides
        ? (overrides.installer ? [overrides.installer] : [])
        : (primaryInstaller ? [primaryInstaller] : [])),
  };
}

function websiteDiscovery(
  status: WebsiteAppDiscovery['status'] = 'ready',
): WebsiteAppDiscovery {
  return {
    id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    status,
    phase: status === 'ready' ? 'ready' : 'searching_installers',
    warnings: [],
    providedInstallerPlatforms: ['windows'],
    suggestions: status === 'ready' ? {
      name: { value: 'Website Desktop', source: 'json_ld' },
      publisher: { value: 'Website Vendor', source: 'open_graph' },
      officialUrl: { value: 'https://website.example/product', source: 'source_page' },
      latestVersion: { value: '3.1.0', source: 'filename' },
      description: {
        value: 'Aplicación descubierta desde su web oficial.',
        source: 'open_graph',
      },
      longDescription: {
        value: 'Descripción larga generada automáticamente en español.',
        source: 'generated_ai',
      },
      iconUrl: { value: 'https://website.example/icon.png', source: 'open_graph' },
    } : null,
    installers: status === 'ready' ? [{
      id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      finalDomain: 'website.example',
      filename: 'WebsiteDesktop-3.1.0.exe',
      extension: '.exe',
      contentType: 'application/x-msdownload',
      sizeBytes: 8192,
      version: '3.1.0',
      operatingSystem: 'windows',
      architecture: 'x86_64',
    }] : [],
    ai: status === 'ready' ? {
      status: 'ready',
      provider: 'groq',
      model: 'model-test',
    } : null,
    errorCode: null,
    appliedAppId: null,
    createdAt: '2026-07-28T08:00:00Z',
    updatedAt: '2026-07-28T08:00:01Z',
    expiresAt: '2026-07-29T08:00:00Z',
  };
}

describe('AdminAppsPage', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 800 });
    vi.spyOn(catalogApi, 'fetchAdminApps').mockResolvedValue({
      data: [unresolvedApp, secondApp],
      page: 1,
      pageSize: 12,
      total: 2,
    });
    vi.spyOn(catalogApi, 'fetchCatalogStats').mockResolvedValue({
      total: 10,
      filters: { all: 10, available: 6, review: 3, missing: 1 },
      lastScrape: null,
      generatedAt: '2026-07-28T08:00:00Z',
    });
    vi.spyOn(catalogApi, 'fetchAppDetails').mockImplementation(async (id) => (
      details(id === secondApp.id ? secondApp : unresolvedApp)
    ));
    vi.spyOn(catalogApi, 'fetchCurrentManualInstallerInspection').mockRejectedValue(
      new catalogApi.ApiRequestError(404, 'inspection_not_found'),
    );
    vi.spyOn(catalogApi, 'fetchManualInstallerInspection').mockResolvedValue(
      inspection('ready'),
    );
    vi.spyOn(catalogApi, 'applyManualInstallerInspection').mockResolvedValue({
      application: { ...details(unresolvedApp), downloadable: true },
      sourceRef: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      sourceRefs: ['bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'],
      warnings: [],
    });
    vi.spyOn(catalogApi, 'createManualInstallerInspection').mockResolvedValue(
      inspection('queued'),
    );
    vi.spyOn(catalogApi, 'patchAdminApp').mockImplementation(async (_id, payload) => ({
      ...details(unresolvedApp),
      ...payload,
    } as AppDetails));
    vi.spyOn(catalogApi, 'createAdminApp').mockResolvedValue(details(unresolvedApp));
    vi.spyOn(catalogApi, 'createWebsiteAppDiscovery').mockResolvedValue(
      websiteDiscovery(),
    );
    vi.spyOn(catalogApi, 'fetchWebsiteAppDiscovery').mockResolvedValue(
      websiteDiscovery(),
    );
    vi.spyOn(catalogApi, 'applyWebsiteAppDiscovery').mockResolvedValue({
      application: {
        ...details(unresolvedApp),
        id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        name: 'Website Desktop',
        officialUrl: 'https://website.example/product',
      },
      installerCount: 1,
      warnings: [],
    });
    vi.spyOn(catalogApi, 'generateAdminDescription').mockResolvedValue({
      jobId: 'job',
      status: 'queued',
    });
    vi.spyOn(catalogApi, 'deleteAdminApp').mockResolvedValue(undefined);
    vi.spyOn(catalogApi, 'exportAdminAppsCsv').mockResolvedValue(undefined);
    vi.spyOn(catalogApi, 'deleteAllAdminApps').mockResolvedValue({ deleted: 10 });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('starts in Por resolver, exposes counts and debounces the search', async () => {
    render(<AdminAppsPage />);

    const unresolvedFilter = await screen.findByRole('button', { name: /Por resolver/ });
    expect(unresolvedFilter).toHaveAttribute('aria-pressed', 'true');
    expect(unresolvedFilter).toHaveTextContent('4');
    await waitFor(() => {
      expect(catalogApi.fetchAdminApps).toHaveBeenLastCalledWith(
        expect.objectContaining({ filter: 'unresolved', query: '' }),
        expect.any(AbortSignal),
      );
    });

    fireEvent.change(screen.getByPlaceholderText(/Buscar por nombre/), {
      target: { value: 'editor' },
    });
    await new Promise((resolve) => window.setTimeout(resolve, 340));

    await waitFor(() => {
      expect(catalogApi.fetchAdminApps).toHaveBeenLastCalledWith(
        expect.objectContaining({ filter: 'unresolved', query: 'editor' }),
        expect.any(AbortSignal),
      );
    });
  });

  it('keeps normalized icon media separate from the text column', async () => {
    vi.mocked(catalogApi.fetchAdminApps).mockResolvedValue({
      data: [{
        ...unresolvedApp,
        iconUrl: 'https://example.com/oversized-logo.png',
      }],
      page: 1,
      pageSize: 12,
      total: 1,
    });
    render(<AdminAppsPage />);

    const row = await screen.findByRole('option', { name: /Example App/ });
    const iconCell = row.querySelector('.admin-app-row-icon');
    const copyColumn = row.querySelector('.admin-app-row-copy');

    expect(iconCell).toBeInTheDocument();
    expect(iconCell?.querySelector('.app-mini-icon')).toHaveAttribute(
      'src',
      'https://example.com/oversized-logo.png',
    );
    expect(copyColumn).toHaveTextContent('Example App');
    expect(iconCell?.contains(copyColumn)).toBe(false);
  });

  it('opens the editable official website from its default field action', async () => {
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('option', { name: /Example App/ }));
    const officialWebsite = await screen.findByRole('link', {
      name: 'Abrir la web oficial en una pestaña nueva',
    });

    expect(officialWebsite).toHaveAttribute('href', 'https://example.com/');
    expect(officialWebsite).toHaveAttribute('target', '_blank');
    expect(screen.getByLabelText('Web oficial')).toHaveValue('https://example.com');
  });

  it('creates a new app from its website and optional OS installers', async () => {
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('button', { name: 'Nueva' }));
    expect(screen.getByRole('heading', { name: 'Crear aplicación' })).toBeInTheDocument();
    expect(screen.getByLabelText('Web oficial')).toBeInTheDocument();
    expect(screen.getByLabelText('Windows')).toBeInTheDocument();
    expect(screen.getByLabelText('macOS')).toBeInTheDocument();
    expect(screen.getByLabelText('Linux')).toBeInTheDocument();
    expect(screen.queryByLabelText('Nombre')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Descripción larga/)).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Web oficial'), {
      target: { value: 'https://website.example/product' },
    });
    fireEvent.change(screen.getByLabelText('Windows'), {
      target: { value: 'https://downloads.website.example/WebsiteDesktop.exe' },
    });
    fireEvent.click(screen.getByRole('button', {
      name: 'Analizar web y buscar instaladores',
    }));

    expect(await screen.findByDisplayValue('Website Desktop')).toBeInTheDocument();
    expect(catalogApi.createWebsiteAppDiscovery).toHaveBeenCalledWith({
      officialUrl: 'https://website.example/product',
      installerUrls: {
        windows: 'https://downloads.website.example/WebsiteDesktop.exe',
        macos: null,
        linux: null,
      },
    });
    expect(screen.getByDisplayValue('Website Vendor')).toBeInTheDocument();
    expect(screen.getByText('WebsiteDesktop-3.1.0.exe')).toBeInTheDocument();
    expect(screen.getByText('Generado con IA')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Nombre/), {
      target: { value: 'Website Desktop revisada' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Crear aplicación' }));

    await waitFor(() => {
      expect(catalogApi.applyWebsiteAppDiscovery).toHaveBeenCalledWith(
        'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        expect.objectContaining({
          name: 'Website Desktop revisada',
          officialUrl: 'https://website.example/product',
          latestVersion: '3.1.0',
        }),
      );
    });
  });

  it('clears a completed website analysis when Nueva is clicked explicitly', async () => {
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('button', { name: 'Nueva' }));
    fireEvent.change(screen.getByLabelText('Web oficial'), {
      target: { value: 'https://website.example/product' },
    });
    fireEvent.click(screen.getByRole('button', {
      name: 'Analizar web y buscar instaladores',
    }));

    expect(await screen.findByDisplayValue('Website Desktop')).toBeInTheDocument();
    expect(window.sessionStorage.getItem(
      'batch-downloader.admin.website-discovery.v1',
    )).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Nueva' }));

    await waitFor(() => {
      expect(screen.getByLabelText('Web oficial')).toHaveValue('');
      expect(screen.queryByDisplayValue('Website Desktop')).not.toBeInTheDocument();
    });
    expect(window.sessionStorage.getItem(
      'batch-downloader.admin.website-discovery.v1',
    )).toBeNull();
  });

  it('ignores a website analysis response that finishes after Nueva resets the form', async () => {
    let resolveDiscovery: ((value: WebsiteAppDiscovery) => void) | undefined;
    vi.mocked(catalogApi.createWebsiteAppDiscovery).mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveDiscovery = resolve;
      }),
    );
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('button', { name: 'Nueva' }));
    fireEvent.change(screen.getByLabelText('Web oficial'), {
      target: { value: 'https://website.example/product' },
    });
    fireEvent.click(screen.getByRole('button', {
      name: 'Analizar web y buscar instaladores',
    }));
    fireEvent.click(screen.getByRole('button', { name: 'Nueva' }));
    resolveDiscovery?.(websiteDiscovery());

    await waitFor(() => {
      expect(screen.getByLabelText('Web oficial')).toHaveValue('');
      expect(screen.queryByDisplayValue('Website Desktop')).not.toBeInTheDocument();
    });
    expect(window.sessionStorage.getItem(
      'batch-downloader.admin.website-discovery.v1',
    )).toBeNull();
  });

  it('recovers a website analysis only when the page is reloaded', async () => {
    window.sessionStorage.setItem(
      'batch-downloader.admin.website-discovery.v1',
      JSON.stringify({
        id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        officialUrl: 'https://website.example/product',
      }),
    );

    render(<AdminAppsPage />);

    expect(await screen.findByDisplayValue('Website Desktop')).toBeInTheDocument();
    expect(catalogApi.fetchWebsiteAppDiscovery).toHaveBeenCalledWith(
      'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      expect.any(AbortSignal),
    );
  });

  it('accepts optional installer URI slots per operating system for unresolved apps', async () => {
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('option', { name: /Example App/ }));
    fireEvent.change(await screen.findByLabelText(/^Página de origen/), {
      target: { value: 'https://example.com/downloads' },
    });
    fireEvent.change(screen.getByLabelText('Windows'), {
      target: { value: 'https://downloads.example.com/Example.exe' },
    });
    fireEvent.change(screen.getByLabelText('Linux'), {
      target: { value: 'https://downloads.example.com/example.AppImage' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Analizar instaladores' }));

    await waitFor(() => {
      expect(catalogApi.createManualInstallerInspection).toHaveBeenCalledWith(
        unresolvedApp.id,
        {
          installerUrls: {
            windows: 'https://downloads.example.com/Example.exe',
            macos: null,
            linux: 'https://downloads.example.com/example.AppImage',
          },
          sourcePageUrl: 'https://example.com/downloads',
        },
      );
    });
  });

  it('recovers a running inspection and hydrates the editable preview when ready', async () => {
    vi.mocked(catalogApi.fetchCurrentManualInstallerInspection).mockResolvedValue(
      inspection('running'),
    );
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('option', { name: /Example App/ }));
    expect(await screen.findByText('Analizando los instaladores')).toBeInTheDocument();

    await waitFor(
      () => expect(catalogApi.fetchManualInstallerInspection).toHaveBeenCalled(),
      { timeout: 2500 },
    );
    expect(await screen.findByDisplayValue('Descripción larga generada y revisable.'))
      .toBeInTheDocument();
    expect(screen.getByText('Generado con IA')).toBeInTheDocument();
    expect(screen.getByText('Example-1.2.0.exe')).toBeInTheDocument();
  });

  it('publishes an edited preview, removes the resolved row and selects the next app', async () => {
    vi.mocked(catalogApi.fetchCurrentManualInstallerInspection).mockResolvedValue(
      inspection('ready'),
    );
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('option', { name: /Example App/ }));
    const longDescription = await screen.findByLabelText(/^Descripción larga/);
    fireEvent.change(longDescription, {
      target: { value: 'Descripción corregida por administración.' },
    });
    expect(screen.getByText('Edición manual')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Guardar y publicar' }));

    await waitFor(() => {
      expect(catalogApi.applyManualInstallerInspection).toHaveBeenCalledWith(
        unresolvedApp.id,
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        expect.objectContaining({
          expectedAppVersion: 7,
          longDescription: 'Descripción corregida por administración.',
          operatingSystem: 'windows',
        }),
      );
    });
    await waitFor(() => expect(catalogApi.fetchAppDetails).toHaveBeenCalledWith(
      secondApp.id,
      expect.any(AbortSignal),
    ));
  });

  it('requires an explicit platform for a neutral validated artifact', async () => {
    vi.mocked(catalogApi.fetchCurrentManualInstallerInspection).mockResolvedValue(
      inspection('ready', {
        installer: {
          finalDomain: 'example.com',
          filename: 'Example.zip',
          extension: '.zip',
          contentType: 'application/zip',
          sizeBytes: 4096,
          version: null,
          operatingSystem: null,
          architecture: 'unknown',
          platformRequired: true,
        },
      }),
    );
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('option', { name: /Example App/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Guardar y publicar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Selecciona la plataforma antes de publicar.',
    );
    expect(catalogApi.applyManualInstallerInspection).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText(/^Sistema operativo/), {
      target: { value: 'linux' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar y publicar' }));
    await waitFor(() => expect(catalogApi.applyManualInstallerInspection).toHaveBeenCalledWith(
      unresolvedApp.id,
      expect.any(String),
      expect.objectContaining({ operatingSystem: 'linux' }),
    ));
  });

  it('permite volver y restaura el foco si falla el detalle en móvil', async () => {
    vi.mocked(catalogApi.fetchAppDetails).mockRejectedValueOnce(
      new catalogApi.ApiRequestError(503, 'details_unavailable'),
    );
    render(<AdminAppsPage />);

    const row = await screen.findByRole('option', { name: /Example App/ });
    fireEvent.click(row);
    expect(await screen.findByText('No se pudo abrir la aplicación')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Volver' }));
    await waitFor(() => expect(row).toHaveFocus());
  });

  it('ignores a stale list response after the administrator changes filters', async () => {
    let resolveSlow: ((value: {
      data: CatalogApp[];
      page: number;
      pageSize: number;
      total: number;
    }) => void) | undefined;
    vi.mocked(catalogApi.fetchAdminApps)
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveSlow = resolve;
      }))
      .mockResolvedValueOnce({
        data: [{ ...unresolvedApp, id: 'fast', name: 'Disponible reciente' }],
        page: 1,
        pageSize: 12,
        total: 1,
      });
    render(<AdminAppsPage />);

    fireEvent.click(await screen.findByRole('button', { name: /Disponibles/ }));
    expect(await screen.findByText('Disponible reciente')).toBeInTheDocument();
    resolveSlow?.({
      data: [{ ...unresolvedApp, id: 'stale', name: 'Respuesta antigua' }],
      page: 1,
      pageSize: 12,
      total: 1,
    });

    await waitFor(() => expect(screen.queryByText('Respuesta antigua')).not.toBeInTheDocument());
  });
});
