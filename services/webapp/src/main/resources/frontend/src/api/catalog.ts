import type {
  AppDetails,
  AdminAppFilter,
  AuditItem,
  AuthUser,
  BundleDetails,
  BundleResponse,
  CatalogChangeEvent,
  CatalogFacets,
  CatalogResponse,
  CatalogStats,
  ContentEnqueueResult,
  DownloadJob,
  FilterKey,
  ResolverLogItem,
  ScraperEvent,
  ScraperMetricItem,
  ScraperQueueMaintenanceResult,
  ScraperQueueState,
  ScraperRunSummary,
  ScraperSnapshotItem,
  SoftwareRequestItem,
  OperatingSystem,
  ManualInstallerApplyRequest,
  ManualInstallerApplyResponse,
  ManualInstallerInspection,
  SearchMode,
  SortKey,
} from '../types/catalog';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

let cachedCsrfToken: string | undefined;

function cookieValue(name: string): string | undefined {
  return document.cookie
    .split(';')
    .map((value) => value.trim())
    .find((value) => value.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

async function ensureCsrfToken(forceRefresh = false): Promise<string | undefined> {
  if (!forceRefresh && cachedCsrfToken) return cachedCsrfToken;
  const response = await fetch(`${API_BASE}/api/v1/auth/csrf`, { credentials: 'include' });
  if (response.ok) {
    const body = await response.json().catch(() => null) as { token?: string } | null;
    if (body?.token) {
      cachedCsrfToken = body.token;
      return cachedCsrfToken;
    }
  }
  const token = cookieValue('XSRF-TOKEN');
  cachedCsrfToken = token ? decodeURIComponent(token) : undefined;
  return cachedCsrfToken;
}

export class ApiRequestError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
  ) {
    super(code);
    this.name = 'ApiRequestError';
  }
}

export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase();
  const headers = new Headers(init?.headers);
  if (init?.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (UNSAFE_METHODS.has(method)) {
    const csrfToken = await ensureCsrfToken();
    if (csrfToken) headers.set('X-XSRF-TOKEN', csrfToken);
  }
  let response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...init,
    headers,
  });
  if (response.status === 403 && UNSAFE_METHODS.has(method)) {
    cachedCsrfToken = undefined;
    const retryHeaders = new Headers(headers);
    const csrfToken = await ensureCsrfToken(true);
    if (csrfToken) retryHeaders.set('X-XSRF-TOKEN', csrfToken);
    response = await fetch(`${API_BASE}${path}`, {
      credentials: 'include',
      ...init,
      headers: retryHeaders,
    });
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as {
      code?: string;
      detail?: { code?: string } | string;
    } | null;
    const detailCode = typeof payload?.detail === 'object' ? payload.detail.code : undefined;
    throw new ApiRequestError(
      response.status,
      payload?.code ?? detailCode ?? `request_failed_${response.status}`,
    );
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return null as T;
  return JSON.parse(text) as T;
}

export async function fetchApps(params: {
  query: string;
  filter: FilterKey;
  sort: SortKey;
  page: number;
  pageSize: number;
  tags?: string[];
  publishers?: string[];
  tagMatchMin?: number;
  operatingSystems?: OperatingSystem[];
  architecture?: string;
  searchMode?: SearchMode;
}, signal?: AbortSignal): Promise<CatalogResponse> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  params.tags?.forEach((tag) => search.append('tag', tag));
  params.publishers?.forEach((publisher) => search.append('publisher', publisher));
  if (params.tagMatchMin) search.set('tagMatchMin', String(params.tagMatchMin));
  params.operatingSystems?.forEach((operatingSystem) => search.append('os', operatingSystem));
  if (params.architecture) search.set('architecture', params.architecture);
  if (params.searchMode) search.set('searchMode', params.searchMode);
  search.set('sort', params.sort);
  search.set('page', String(params.page));
  search.set('pageSize', String(params.pageSize));
  return requestJson<CatalogResponse>(`/api/v1/apps?${search.toString()}`, { signal });
}

export async function fetchCatalogFacets(params: {
  query: string;
  filter: FilterKey;
  tags?: string[];
  publishers?: string[];
  tagMatchMin?: number;
  operatingSystems?: OperatingSystem[];
  architecture?: string;
  searchMode?: SearchMode;
}): Promise<CatalogFacets> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  params.tags?.forEach((tag) => search.append('tag', tag));
  params.publishers?.forEach((publisher) => search.append('publisher', publisher));
  if (params.tagMatchMin) search.set('tagMatchMin', String(params.tagMatchMin));
  params.operatingSystems?.forEach((operatingSystem) => search.append('os', operatingSystem));
  if (params.architecture) search.set('architecture', params.architecture);
  if (params.searchMode) search.set('searchMode', params.searchMode);
  return requestJson<CatalogFacets>(`/api/v1/apps/facets?${search.toString()}`);
}

export async function fetchCatalogStats(signal?: AbortSignal): Promise<CatalogStats> {
  return requestJson<CatalogStats>('/api/v1/apps/stats', { signal });
}

export async function fetchAppDetails(appId: string, signal?: AbortSignal): Promise<AppDetails> {
  return requestJson<AppDetails>('/api/v1/apps/' + encodeURIComponent(appId), { signal });
}

export async function createDownloadJob(
  request: { appIds: string[]; operatingSystems?: OperatingSystem[]; notifyWhenReady?: boolean }
    | { bundleId: string; operatingSystems?: OperatingSystem[]; notifyWhenReady?: boolean },
): Promise<DownloadJob> {
  return requestJson<DownloadJob>('/api/v1/download-jobs', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export async function fetchDownloadJob(jobId: string): Promise<DownloadJob> {
  return requestJson<DownloadJob>(`/api/v1/download-jobs/${encodeURIComponent(jobId)}`);
}

export async function cancelDownloadJob(jobId: string): Promise<DownloadJob> {
  return requestJson<DownloadJob>(`/api/v1/download-jobs/${encodeURIComponent(jobId)}`, {
    method: 'DELETE',
  });
}

export function downloadJobFileUrl(jobId: string): string {
  return `${API_BASE}/api/v1/download-jobs/${encodeURIComponent(jobId)}/file`;
}

const TERMINAL_DOWNLOAD_STATUSES = new Set(['READY', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED']);

export function connectDownloadJobEvents(
  jobId: string,
  onJob: (job: DownloadJob) => void,
  onError?: () => void,
): () => void {
  let stopped = false;
  let pollingTimer: number | undefined;
  let source: EventSource | undefined;

  const accept = (job: DownloadJob) => {
    if (stopped) return;
    onJob(job);
    if (TERMINAL_DOWNLOAD_STATUSES.has(job.status)) {
      source?.close();
      if (pollingTimer) window.clearTimeout(pollingTimer);
    }
  };

  const poll = async () => {
    if (stopped) return;
    try {
      const job = await fetchDownloadJob(jobId);
      accept(job);
      if (!TERMINAL_DOWNLOAD_STATUSES.has(job.status)) {
        pollingTimer = window.setTimeout(poll, 2500);
      }
    } catch {
      onError?.();
      pollingTimer = window.setTimeout(poll, 4000);
    }
  };

  const consume = (event: MessageEvent<string>) => {
    try {
      accept(JSON.parse(event.data) as DownloadJob);
    } catch {
      onError?.();
    }
  };

  if (typeof EventSource === 'undefined') {
    void poll();
  } else {
    source = new EventSource(
      `${API_BASE}/api/v1/download-jobs/${encodeURIComponent(jobId)}/events`,
      { withCredentials: true },
    );
    source.addEventListener('message', consume as EventListener);
    source.addEventListener('job', consume as EventListener);
    source.addEventListener('error', () => {
      source?.close();
      void poll();
    });
  }

  return () => {
    stopped = true;
    source?.close();
    if (pollingTimer) window.clearTimeout(pollingTimer);
  };
}

export function catalogWebSocketUrl(): string {
  const base = API_BASE || window.location.origin;
  const url = new URL('/api/catalog/ws', base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}

export function scraperWebSocketUrl(): string {
  const base = API_BASE || window.location.origin;
  const url = new URL('/api/admin/scraper/ws', base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}

export async function fetchBundles(params: {
  type?: 'official' | 'community' | 'user';
  page?: number;
  pageSize?: number;
  sort?: string;
}, signal?: AbortSignal): Promise<BundleResponse> {
  const search = new URLSearchParams();
  if (params.type) search.set('type', params.type);
  search.set('page', String(params.page ?? 1));
  search.set('pageSize', String(params.pageSize ?? 12));
  search.set('sort', params.sort ?? 'updated');
  return requestJson<BundleResponse>(`/api/v1/bundles?${search.toString()}`, { signal });
}

export async function fetchBundle(slug: string): Promise<BundleDetails> {
  return requestJson<BundleDetails>(`/api/v1/bundles/${encodeURIComponent(slug)}`);
}

export async function createAdminBundle(payload: Record<string, unknown>): Promise<BundleDetails> {
  return requestJson<BundleDetails>('/api/admin/bundles', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function updateAdminBundle(bundleId: string, payload: Record<string, unknown>): Promise<BundleDetails> {
  return requestJson<BundleDetails>(`/api/admin/bundles/${encodeURIComponent(bundleId)}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

export async function login(username: string, password: string): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

export async function logout(): Promise<void> {
  await requestJson<void>('/api/v1/auth/logout', { method: 'POST' });
}

export async function me(): Promise<AuthUser | null> {
  const identity = await requestJson<AuthUser | undefined>('/api/v1/auth/me');
  return identity ?? null;
}

export async function fetchAdminApps(params: {
  query: string;
  filter: AdminAppFilter;
  sort: SortKey;
  page: number;
  pageSize: number;
}, signal?: AbortSignal): Promise<CatalogResponse> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  // The admin endpoint defaults to `unresolved`, so `all` must be explicit.
  search.set('status', params.filter);
  search.set('sort', params.sort);
  search.set('page', String(params.page));
  search.set('pageSize', String(params.pageSize));
  return requestJson<CatalogResponse>(`/api/admin/apps?${search.toString()}`, { signal });
}

export async function createAdminApp(payload: Record<string, unknown>): Promise<AppDetails> {
  return requestJson<AppDetails>('/api/admin/apps', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function patchAdminApp(appId: string, payload: Record<string, unknown>): Promise<AppDetails> {
  return requestJson<AppDetails>(`/api/admin/apps/${encodeURIComponent(appId)}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

export async function deleteAdminApp(appId: string): Promise<void> {
  await requestJson<void>(`/api/admin/apps/${encodeURIComponent(appId)}`, { method: 'DELETE' });
}

export async function deleteAllAdminApps(): Promise<{ deleted: number }> {
  return requestJson<{ deleted: number }>('/api/admin/apps?confirm=DELETE_ALL', { method: 'DELETE' });
}

export async function exportAdminAppsCsv(): Promise<void> {
  const response = await fetch(`${API_BASE}/api/admin/apps/export.csv`, {
    credentials: 'include',
  });
  if (!response.ok) throw new Error(`request_failed_${response.status}`);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'batch-downloader-apps.csv';
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function connectCatalogEvents(onEvent: (event: CatalogChangeEvent) => void, onState?: (state: 'live' | 'reconnecting' | 'offline') => void): () => void {
  let socket: WebSocket | null = null;
  let stopped = false;
  let reconnectTimer: number | undefined;

  function connect() {
    if (stopped) return;
    onState?.('reconnecting');
    socket = new WebSocket(catalogWebSocketUrl());
    socket.addEventListener('open', () => onState?.('live'));
    socket.addEventListener('message', (event) => {
      try {
        const payload = JSON.parse(event.data) as CatalogChangeEvent;
        if (payload.type === 'catalog.changed') onEvent(payload);
      } catch {
        // Ignore non-catalog messages.
      }
    });
    socket.addEventListener('close', () => {
      if (stopped) return;
      onState?.('offline');
      reconnectTimer = window.setTimeout(connect, 2500);
    });
    socket.addEventListener('error', () => {
      onState?.('offline');
      socket?.close();
    });
  }

  connect();

  return () => {
    stopped = true;
    if (reconnectTimer) window.clearTimeout(reconnectTimer);
    socket?.close();
  };
}

export async function generateAdminDescription(appId: string): Promise<{ jobId: string; status: string }> {
  return requestJson<{ jobId: string; status: string }>(
    `/api/admin/apps/${encodeURIComponent(appId)}/generate-description`,
    { method: 'POST' },
  );
}

export async function createManualInstallerInspection(
  appId: string,
  payload: { installerUrl: string; sourcePageUrl: string },
): Promise<ManualInstallerInspection> {
  return requestJson<ManualInstallerInspection>(
    `/api/admin/apps/${encodeURIComponent(appId)}/manual-installer-inspections`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  );
}

export async function fetchCurrentManualInstallerInspection(
  appId: string,
  signal?: AbortSignal,
): Promise<ManualInstallerInspection> {
  return requestJson<ManualInstallerInspection>(
    `/api/admin/apps/${encodeURIComponent(appId)}/manual-installer-inspections/current`,
    { signal },
  );
}

export async function fetchManualInstallerInspection(
  appId: string,
  inspectionId: string,
  signal?: AbortSignal,
): Promise<ManualInstallerInspection> {
  return requestJson<ManualInstallerInspection>(
    `/api/admin/apps/${encodeURIComponent(appId)}/manual-installer-inspections/${encodeURIComponent(inspectionId)}`,
    { signal },
  );
}

export async function applyManualInstallerInspection(
  appId: string,
  inspectionId: string,
  payload: ManualInstallerApplyRequest,
): Promise<ManualInstallerApplyResponse> {
  return requestJson<ManualInstallerApplyResponse>(
    `/api/admin/apps/${encodeURIComponent(appId)}/manual-installer-inspections/${encodeURIComponent(inspectionId)}/apply`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  );
}

export async function fetchAdminRuns(): Promise<ScraperRunSummary[]> {
  return requestJson<ScraperRunSummary[]>('/api/admin/scraper/runs');
}

export async function fetchAdminCurrentRun(): Promise<ScraperRunSummary | null> {
  return requestJson<ScraperRunSummary | null>('/api/admin/scraper/current');
}

export async function fetchAdminLogs(): Promise<ResolverLogItem[]> {
  return requestJson<ResolverLogItem[]>('/api/admin/scraper/logs');
}

export async function fetchAdminQueues(): Promise<ScraperQueueState[]> {
  return requestJson<ScraperQueueState[]>('/api/admin/scraper/queues');
}

export async function fetchAdminMetrics(): Promise<ScraperMetricItem[]> {
  return requestJson<ScraperMetricItem[]>('/api/admin/scraper/metrics');
}

export async function fetchAdminSnapshots(): Promise<ScraperSnapshotItem[]> {
  return requestJson<ScraperSnapshotItem[]>('/api/admin/scraper/snapshots');
}

export async function recoverStuckScraperQueueItems(): Promise<ScraperQueueMaintenanceResult> {
  return requestJson<ScraperQueueMaintenanceResult>(
    '/api/admin/scraper/queues/recover-stuck',
    { method: 'POST' },
  );
}

export async function retryFailedScraperQueueItems(): Promise<ScraperQueueMaintenanceResult> {
  return requestJson<ScraperQueueMaintenanceResult>(
    '/api/admin/scraper/queues/retry-failed',
    { method: 'POST' },
  );
}

export async function pruneTerminalScraperQueueItems(): Promise<ScraperQueueMaintenanceResult> {
  return requestJson<ScraperQueueMaintenanceResult>(
    '/api/admin/scraper/queues/prune-terminal',
    { method: 'POST' },
  );
}

export async function clearPendingScraperQueueItems(): Promise<ScraperQueueMaintenanceResult> {
  return requestJson<ScraperQueueMaintenanceResult>(
    '/api/admin/scraper/queues/clear-pending',
    { method: 'POST' },
  );
}

export async function clearAllScraperQueueItems(): Promise<ScraperQueueMaintenanceResult> {
  return requestJson<ScraperQueueMaintenanceResult>(
    '/api/admin/scraper/queues/clear-all',
    { method: 'POST' },
  );
}

export async function enqueueMissingScraperDescriptions(): Promise<ContentEnqueueResult> {
  return requestJson<ContentEnqueueResult>(
    '/api/admin/scraper/descriptions/enqueue-missing',
    { method: 'POST' },
  );
}

export function connectScraperEvents(onEvent: (event: ScraperEvent) => void, onState?: (state: 'live' | 'reconnecting' | 'offline') => void): () => void {
  let socket: WebSocket | null = null;
  let stopped = false;
  let reconnectTimer: number | undefined;

  function connect() {
    if (stopped) return;
    onState?.('reconnecting');
    socket = new WebSocket(scraperWebSocketUrl());
    socket.addEventListener('open', () => onState?.('live'));
    socket.addEventListener('message', (event) => {
      try {
        const payload = JSON.parse(event.data) as ScraperEvent;
        if (payload.type === 'scraper.changed') onEvent(payload);
      } catch {
        // Ignore non-scraper messages.
      }
    });
    socket.addEventListener('close', () => {
      if (stopped) return;
      onState?.('offline');
      reconnectTimer = window.setTimeout(connect, 2500);
    });
    socket.addEventListener('error', () => {
      onState?.('offline');
      socket?.close();
    });
  }

  connect();

  return () => {
    stopped = true;
    if (reconnectTimer) window.clearTimeout(reconnectTimer);
    socket?.close();
  };
}

export async function sendScraperCommand(command: 'pause' | 'resume' | 'stop' | 'force_stop' | 'run_once'): Promise<void> {
  await requestJson<void>('/api/admin/scraper/commands', {
    method: 'POST',
    body: JSON.stringify({ command }),
  });
}

export async function fetchAdminRequests(): Promise<SoftwareRequestItem[]> {
  return requestJson<SoftwareRequestItem[]>('/api/admin/requests');
}

export async function fetchAdminAudit(): Promise<AuditItem[]> {
  return requestJson<AuditItem[]>('/api/admin/audit');
}
