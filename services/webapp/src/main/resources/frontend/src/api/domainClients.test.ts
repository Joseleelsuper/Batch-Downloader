import { afterEach, describe, expect, it, vi } from 'vitest';
import { adminLogin, adminLogout, login, logout, me } from './account';
import {
  applyManualInstallerInspection,
  applyWebsiteAppDiscovery,
  confirmInstallerAbsence,
  createAdminApp,
  createManualInstallerInspection,
  createWebsiteAppDiscovery,
  deleteAdminApp,
  deleteAllAdminApps,
  exportAdminAppsCsv,
  fetchAbsenceVerificationSummary,
  fetchActiveAbsenceVerification,
  fetchAdminApps,
  fetchCurrentManualInstallerInspection,
  fetchManualInstallerInspection,
  fetchWebsiteAppDiscovery,
  generateAdminDescription,
  patchAdminApp,
} from './adminApps';
import { fetchAdminAudit, fetchAdminRequests } from './adminMeta';
import {
  createAdminBundle,
  fetchBundle,
  fetchBundles,
  updateAdminBundle,
} from './bundles';
import {
  connectCatalogEvents,
  fetchAppDetails,
  fetchApps,
  fetchCatalogFacets,
  fetchCatalogStats,
} from './catalogApps';
import {
  cancelDownloadJob,
  connectDownloadJobEvents,
  createDownloadJob,
  downloadJobFileUrl,
  fetchDownloadJobFileLink,
  fetchDownloadJob,
} from './downloads';
import {
  connectScraperEvents,
  createScraperRun,
  enqueueMissingScraperDescriptions,
  fetchAdminCurrentRun,
  fetchAdminLogs,
  fetchAdminMetrics,
  fetchAdminQueues,
  fetchAdminRuns,
  fetchAdminSnapshots,
  pruneTerminalScraperQueueItems,
  recoverStuckScraperQueueItems,
  retryFailedScraperQueueItems,
  scraperWebSocketUrl,
  sendScraperCommand,
} from './scraperAdmin';
import { invalidateCsrfToken } from './http/csrf';
import type { DownloadJob } from '../types/catalog';

class FakeWebSocket extends EventTarget {
  static instances: FakeWebSocket[] = [];

  readonly url: string;

  constructor(url: string | URL) {
    super();
    this.url = String(url);
    FakeWebSocket.instances.push(this);
  }

  close() {
    this.dispatchEvent(new Event('close'));
  }
}

class FakeEventSource extends EventTarget {
  static instances: FakeEventSource[] = [];

  readonly url: string;
  readonly withCredentials: boolean;
  readonly close = vi.fn();

  constructor(url: string | URL, init?: EventSourceInit) {
    super();
    this.url = String(url);
    this.withCredentials = init?.withCredentials === true;
    FakeEventSource.instances.push(this);
  }
}

function downloadJob(status: DownloadJob['status'] = 'QUEUED'): DownloadJob {
  return {
    id: 'job/id',
    status,
    failureCode: null,
    progress: status === 'READY' ? 100 : 0,
    requestedCount: 1,
    acceptedCount: 1,
    omittedCount: 0,
    items: [],
    createdAt: '2026-08-24T10:00:00Z',
    expiresAt: '2026-08-25T10:00:00Z',
  };
}

describe('current identity', () => {
  afterEach(() => {
    FakeWebSocket.instances = [];
    FakeEventSource.instances = [];
    invalidateCsrfToken();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('reconecta el websocket del catálogo y filtra mensajes ajenos', async () => {
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    vi.stubGlobal('WebSocket', FakeWebSocket as unknown as typeof WebSocket);
    const onEvent = vi.fn();
    const onState = vi.fn();

    const disconnect = connectCatalogEvents(onEvent, onState);
    const first = FakeWebSocket.instances[0];
    expect(new URL(first.url).pathname).toBe('/api/v1/catalog/ws');
    expect(onState).toHaveBeenLastCalledWith('reconnecting');

    first.dispatchEvent(new Event('open'));
    first.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({ type: 'ignored.event' }),
    }));
    first.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({ type: 'catalog.changed', version: 2 }),
    }));
    expect(onEvent).toHaveBeenCalledOnce();
    expect(onState).toHaveBeenLastCalledWith('live');

    first.dispatchEvent(new Event('close'));
    expect(onState).toHaveBeenLastCalledWith('offline');
    await vi.advanceTimersByTimeAsync(2_500);
    expect(FakeWebSocket.instances).toHaveLength(2);
    expect(onState).toHaveBeenLastCalledWith('reconnecting');

    disconnect();
    await vi.runAllTimersAsync();
    expect(FakeWebSocket.instances).toHaveLength(2);
  });

  it('usa el mismo supervisor para los eventos del scraper', () => {
    vi.stubGlobal('WebSocket', FakeWebSocket as unknown as typeof WebSocket);
    const onEvent = vi.fn();

    const disconnect = connectScraperEvents(onEvent);
    const socket = FakeWebSocket.instances[0];
    expect(new URL(socket.url).pathname).toBe('/api/v1/admin/scraper/ws');
    socket.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({ type: 'scraper.changed', runId: 'run-1' }),
    }));
    expect(onEvent).toHaveBeenCalledOnce();
    disconnect();
  });

  it('maps the anonymous 204 response to null', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);

    await expect(me()).resolves.toBeNull();
    expect(fetcher).toHaveBeenCalledWith('/api/v1/auth/me', expect.objectContaining({
      credentials: 'include',
    }));
  });

  it('returns the authenticated identity from a 200 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      username: 'admin',
      role: 'ADMIN',
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));

    await expect(me()).resolves.toEqual({ username: 'admin', role: 'ADMIN' });
  });

  it('incluye el sistema operativo elegido al crear un trabajo para un bundle', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'csrf-token' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 'job-1',
        status: 'QUEUED',
        failureCode: null,
        progress: 0,
        requestedCount: 16,
        acceptedCount: 16,
        omittedCount: 0,
        items: [],
        createdAt: '2026-07-16T09:00:00Z',
        expiresAt: '2026-07-17T09:00:00Z',
      }), { status: 202 }));
    vi.stubGlobal('fetch', fetcher);

    await createDownloadJob({ bundleId: 'bundle-1', operatingSystems: ['linux'] });

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/v1/auth/csrf', expect.objectContaining({
      credentials: 'include',
    }));
    expect(fetcher).toHaveBeenCalledWith('/api/v1/download-jobs', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ bundleId: 'bundle-1', operatingSystems: ['linux'] }),
    }));
  });

  it('envía la fuente concreta elegida para una descarga individual', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'csrf-token' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 'job-versioned',
        status: 'QUEUED',
        failureCode: null,
        progress: 0,
        requestedCount: 1,
        acceptedCount: 1,
        omittedCount: 0,
        items: [],
        createdAt: '2026-07-16T09:00:00Z',
        expiresAt: '2026-07-17T09:00:00Z',
      }), { status: 202 }));
    vi.stubGlobal('fetch', fetcher);

    await createDownloadJob({
      appIds: ['app-1'],
      sourceRef: 'resolved-source-1',
    });

    expect(fetcher).toHaveBeenCalledWith('/api/v1/download-jobs', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ appIds: ['app-1'], sourceRef: 'resolved-source-1' }),
    }));
  });

  it('envía explícitamente el filtro all al listado administrativo', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      data: [],
      page: 1,
      pageSize: 12,
      total: 0,
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetcher);

    await fetchAdminApps({
      query: '',
      filter: 'all',
      sort: 'updated',
      page: 1,
      pageSize: 12,
    });

    expect(fetcher).toHaveBeenCalledWith(
      '/api/v1/admin/apps?status=all&sort=updated&page=1&pageSize=12',
      expect.objectContaining({ credentials: 'include' }),
    );
  });

  it('envía tags repetidas y un editor singular al catálogo y a sus facetas', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: [],
        page: 1,
        pageSize: 12,
        total: 0,
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        tags: [],
        publishers: [],
      }), { status: 200 }));
    vi.stubGlobal('fetch', fetcher);
    const shared = {
      query: 'editor',
      filter: 'available' as const,
      tags: ['automation', 'cli'],
      publisher: 'ACME',
      operatingSystems: ['linux'] as ('linux')[],
      architecture: 'x64',
      searchMode: 'semantic' as const,
    };

    await fetchApps({ ...shared, sort: 'updated', page: 1, pageSize: 12 });
    await fetchCatalogFacets(shared);

    expect(fetcher.mock.calls[0]?.[0]).toContain(
      '/api/v1/apps?query=editor&status=available&tag=automation&tag=cli&publisher=ACME&os=linux&architecture=x64&searchMode=semantic',
    );
    expect(fetcher.mock.calls[1]?.[0]).toBe(
      '/api/v1/apps/facets?query=editor&status=available&tag=automation&tag=cli&publisher=ACME&os=linux&architecture=x64&searchMode=semantic',
    );
  });

  it('omite filtros vacíos y aplica valores por defecto a catálogo y bundles', async () => {
    const fetcher = vi.fn().mockImplementation(
      () => Promise.resolve(new Response('{}', { status: 200 })),
    );
    vi.stubGlobal('fetch', fetcher);

    await fetchApps({ query: '  ', filter: 'all', sort: 'name', page: 2, pageSize: 24 });
    await fetchCatalogFacets({ query: '', filter: 'all' });
    await fetchBundles({});
    await fetchBundles({ type: 'community', page: 4, pageSize: 6, sort: 'name' });

    expect(fetcher.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/apps?sort=name&page=2&pageSize=24',
      '/api/v1/apps/facets?',
      '/api/v1/bundles?page=1&pageSize=12&sort=updated',
      '/api/v1/bundles?type=community&page=4&pageSize=6&sort=name',
    ]);
  });

  it('deduplica creaciones simultáneas y libera la clave tras éxito o error', async () => {
    let resolveCreation: ((response: Response) => void) | undefined;
    const creation = new Promise<Response>((resolve) => { resolveCreation = resolve; });
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'csrf' }), { status: 200 }))
      .mockReturnValueOnce(creation)
      .mockResolvedValueOnce(new Response(JSON.stringify(downloadJob()), { status: 202 }))
      .mockRejectedValueOnce(new TypeError('network'))
      .mockResolvedValueOnce(new Response(JSON.stringify(downloadJob()), { status: 202 }));
    vi.stubGlobal('fetch', fetcher);
    const request = { appIds: ['app-1'] };

    const first = createDownloadJob(request);
    const duplicate = createDownloadJob(request);
    resolveCreation?.(new Response(JSON.stringify(downloadJob()), { status: 202 }));
    await expect(Promise.all([first, duplicate])).resolves.toHaveLength(2);
    expect(fetcher).toHaveBeenCalledTimes(2);

    await createDownloadJob(request);
    await expect(createDownloadJob({ appIds: ['failure'] })).rejects.toThrow('network');
    await expect(createDownloadJob({ appIds: ['failure'] })).resolves.toMatchObject({ id: 'job/id' });
    expect(fetcher).toHaveBeenCalledTimes(5);
  });

  it('supervisa descargas por SSE y rechaza eventos malformados', () => {
    vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource);
    const onJob = vi.fn();
    const onError = vi.fn();
    const disconnect = connectDownloadJobEvents('job/id', onJob, onError);
    const source = FakeEventSource.instances[0];

    expect(source.url).toContain('/api/v1/download-jobs/job%2Fid/events');
    expect(source.withCredentials).toBe(true);
    source.dispatchEvent(new MessageEvent('message', { data: '{bad-json' }));
    expect(onError).toHaveBeenCalledWith();
    source.dispatchEvent(new MessageEvent('job', {
      data: JSON.stringify(downloadJob('READY')),
    }));
    expect(onJob).toHaveBeenCalledWith(expect.objectContaining({ status: 'READY' }));
    expect(source.close).toHaveBeenCalled();
    disconnect();
  });

  it('degrada los eventos de descarga a polling y reintenta un fallo', async () => {
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    vi.stubGlobal('EventSource', undefined);
    const fetcher = vi.fn()
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(new Response(JSON.stringify(downloadJob('READY')), { status: 200 }));
    vi.stubGlobal('fetch', fetcher);
    const onJob = vi.fn();
    const onError = vi.fn();
    const disconnect = connectDownloadJobEvents('job/id', onJob, onError);

    await vi.advanceTimersByTimeAsync(2_500);
    expect(onError).toHaveBeenCalledWith(expect.any(TypeError));
    await vi.advanceTimersByTimeAsync(5_000);
    expect(onJob).toHaveBeenCalledWith(expect.objectContaining({ status: 'READY' }));
    disconnect();
  });

  it('cierra un websocket con error y descarta mensajes no JSON', () => {
    vi.stubGlobal('WebSocket', FakeWebSocket as unknown as typeof WebSocket);
    const onEvent = vi.fn();
    const onState = vi.fn();
    const disconnect = connectCatalogEvents(onEvent, onState);
    const socket = FakeWebSocket.instances[0];

    socket.dispatchEvent(new MessageEvent('message', { data: 'not-json' }));
    socket.dispatchEvent(new Event('error'));
    expect(onEvent).not.toHaveBeenCalled();
    expect(onState).toHaveBeenCalledWith('offline');
    disconnect();
  });

  it('genera URLs codificadas y exporta CSV liberando el objeto temporal', async () => {
    const createObjectURL = vi.fn().mockReturnValue('blob:export');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('csv', { status: 200 })));

    expect(downloadJobFileUrl('job/id')).toContain('/api/v1/download-jobs/job%2Fid/file');
    expect(new URL(scraperWebSocketUrl()).protocol).toBe('ws:');
    await exportAdminAppsCsv();

    expect(click).toHaveBeenCalledOnce();
    expect(createObjectURL).toHaveBeenCalledOnce();
    const exportedBlob = createObjectURL.mock.calls[0]?.[0] as Blob;
    expect(exportedBlob.size).toBe(3);
    expect(exportedBlob.type).toBe('text/plain;charset=utf-8');
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:export');
  });

  it('rechaza una exportación CSV HTTP fallida', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('no', { status: 503 })));
    await expect(exportAdminAppsCsv()).rejects.toThrow('request_failed_503');
  });

  it('prepara el enlace firmado del autointento mediante la ruta aditiva', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ url: 'https://downloads.example.test/job.zip?signature=example' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ));
    vi.stubGlobal('fetch', fetcher);

    await expect(fetchDownloadJobFileLink('job/id')).resolves.toEqual({
      url: 'https://downloads.example.test/job.zip?signature=example',
    });
    expect(String(fetcher.mock.calls[0]?.[0])).toContain(
      '/api/v1/download-jobs/job%2Fid/file-link',
    );
  });

  it('mantiene canónicos los endpoints de lectura y administración', async () => {
    const fetcher = vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      return Promise.resolve(new Response(
        path.endsWith('/api/v1/auth/csrf') ? JSON.stringify({ token: 'csrf' }) : '{}',
        { status: 200 },
      ));
    });
    vi.stubGlobal('fetch', fetcher);

    await fetchCatalogStats();
    await fetchAppDetails('app/id');
    await fetchDownloadJob('job/id');
    await cancelDownloadJob('job/id');
    await fetchBundle('bundle/slug');
    await createAdminBundle({ name: 'Bundle' });
    await updateAdminBundle('bundle/id', { name: 'Nuevo' });
    await login('user@example.com', 'secret');
    await adminLogin('admin', 'secret');
    await logout();
    await adminLogout();
    await createAdminApp({ name: 'App' });
    await patchAdminApp('app/id', { name: 'Changed' });
    await deleteAdminApp('app/id');
    await deleteAllAdminApps();
    await generateAdminDescription('app/id');
    await createManualInstallerInspection('app/id', {} as never);
    await fetchCurrentManualInstallerInspection('app/id');
    await fetchManualInstallerInspection('app/id', 'inspection/id');
    await applyManualInstallerInspection('app/id', 'inspection/id', {} as never);
    await createWebsiteAppDiscovery({} as never);
    await fetchWebsiteAppDiscovery('discovery/id');
    await applyWebsiteAppDiscovery('discovery/id', {} as never);
    await fetchAdminRuns();
    await fetchAdminCurrentRun();
    await createScraperRun('selected', ['app/id']);
    await fetchAbsenceVerificationSummary();
    await fetchActiveAbsenceVerification('app/id');
    await confirmInstallerAbsence('app/id', {} as never);
    await fetchAdminLogs();
    await fetchAdminQueues();
    await fetchAdminMetrics();
    await fetchAdminSnapshots();
    await recoverStuckScraperQueueItems();
    await retryFailedScraperQueueItems();
    await pruneTerminalScraperQueueItems();
    await enqueueMissingScraperDescriptions();
    await sendScraperCommand('pause');
    await fetchAdminRequests();
    await fetchAdminAudit();

    const paths = fetcher.mock.calls.map((call) => String(call[0]));
    expect(paths).toContain('/api/v1/apps/app%2Fid');
    expect(paths).toContain('/api/v1/admin/apps/app%2Fid/manual-installer-inspections/inspection%2Fid/apply');
    expect(paths).toContain('/api/v1/admin/scraper/commands');
    expect(paths.every((path) => !path.startsWith('/api/apps'))).toBe(true);
  });
});
