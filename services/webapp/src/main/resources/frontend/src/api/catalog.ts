import type {
  AppDetails,
  AuditItem,
  AuthUser,
  BundleDetails,
  BundleResponse,
  CatalogResponse,
  CatalogStats,
  FilterKey,
  ResolverLogItem,
  ScraperRunSummary,
  SoftwareRequestItem,
  SortKey,
} from '../types/catalog';

const API_BASE = import.meta.env.VITE_API_BASE_URL;

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
  os?: string;
  architecture?: string;
}): Promise<CatalogResponse> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  if (params.tags?.length) search.set('tags', params.tags.join(','));
  if (params.os) search.set('os', params.os);
  if (params.architecture) search.set('architecture', params.architecture);
  search.set('sort', params.sort);
  search.set('page', String(params.page));
  search.set('pageSize', String(params.pageSize));
  return requestJson<CatalogResponse>(`/api/apps?${search.toString()}`);
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
