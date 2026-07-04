import type {
  AppDetails,
  AuditItem,
  AuthUser,
  BundleDetails,
  BundleResponse,
  CatalogChangeEvent,
  CatalogFacets,
  CatalogResponse,
  CatalogStats,
  FilterKey,
  ResolverLogItem,
  ScraperRunSummary,
  SoftwareRequestItem,
  SortKey,
} from '../types/catalog';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  });
  if (!response.ok) throw new Error(`request_failed_${response.status}`);
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
  os?: string;
  architecture?: string;
}): Promise<CatalogResponse> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  params.tags?.forEach((tag) => search.append('tag', tag));
  params.publishers?.forEach((publisher) => search.append('publisher', publisher));
  if (params.tagMatchMin) search.set('tagMatchMin', String(params.tagMatchMin));
  if (params.os) search.set('os', params.os);
  if (params.architecture) search.set('architecture', params.architecture);
  search.set('sort', params.sort);
  search.set('page', String(params.page));
  search.set('pageSize', String(params.pageSize));
  return requestJson<CatalogResponse>(`/api/apps?${search.toString()}`);
}

export async function fetchCatalogFacets(params: {
  query: string;
  filter: FilterKey;
  tags?: string[];
  publishers?: string[];
  tagMatchMin?: number;
  os?: string;
  architecture?: string;
}): Promise<CatalogFacets> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  params.tags?.forEach((tag) => search.append('tag', tag));
  params.publishers?.forEach((publisher) => search.append('publisher', publisher));
  if (params.tagMatchMin) search.set('tagMatchMin', String(params.tagMatchMin));
  if (params.os) search.set('os', params.os);
  if (params.architecture) search.set('architecture', params.architecture);
  return requestJson<CatalogFacets>(`/api/apps/facets?${search.toString()}`);
}

export async function fetchCatalogStats(): Promise<CatalogStats> {
  return requestJson<CatalogStats>('/api/apps/stats');
}

export async function fetchAppDetails(appId: string): Promise<AppDetails> {
  return requestJson<AppDetails>(`/api/apps/${encodeURIComponent(appId)}`);
}

export function downloadUrl(appId: string): string {
  return `${API_BASE}/api/apps/${encodeURIComponent(appId)}/download`;
}

export async function downloadSelectedApps(appIds: string[]): Promise<void> {
  const response = await fetch(`${API_BASE}/api/apps/downloads/zip`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ appIds }),
  });
  if (!response.ok) throw new Error(`request_failed_${response.status}`);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'batch-downloader-apps.zip';
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function catalogWebSocketUrl(): string {
  const base = API_BASE || window.location.origin;
  const url = new URL('/api/catalog/ws', base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}

export async function fetchBundles(params: {
  type?: 'official' | 'community' | 'user';
  page?: number;
  pageSize?: number;
  sort?: string;
}): Promise<BundleResponse> {
  const search = new URLSearchParams();
  if (params.type) search.set('type', params.type);
  search.set('page', String(params.page ?? 1));
  search.set('pageSize', String(params.pageSize ?? 12));
  search.set('sort', params.sort ?? 'updated');
  return requestJson<BundleResponse>(`/api/bundles?${search.toString()}`);
}

export async function fetchBundle(slug: string): Promise<BundleDetails> {
  return requestJson<BundleDetails>(`/api/bundles/${encodeURIComponent(slug)}`);
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
  return requestJson<AuthUser>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

export async function logout(): Promise<void> {
  await requestJson<void>('/api/auth/logout', { method: 'POST' });
}

export async function me(): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/auth/me');
}

export async function fetchAdminApps(params: {
  query: string;
  filter: FilterKey;
  sort: SortKey;
  page: number;
  pageSize: number;
}): Promise<CatalogResponse> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  search.set('sort', params.sort);
  search.set('page', String(params.page));
  search.set('pageSize', String(params.pageSize));
  return requestJson<CatalogResponse>(`/api/admin/apps?${search.toString()}`);
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

export async function generateAdminDescription(appId: string): Promise<{ longDescription: string }> {
  return requestJson<{ longDescription: string }>(
    `/api/admin/apps/${encodeURIComponent(appId)}/generate-description`,
    { method: 'POST' },
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

export async function sendScraperCommand(command: 'pause' | 'resume' | 'stop' | 'run_once'): Promise<void> {
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
