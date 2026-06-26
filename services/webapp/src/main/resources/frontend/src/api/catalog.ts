import type { AppDetails, CatalogResponse, FilterKey } from '../types/catalog';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export async function fetchApps(params: {
  query: string;
  filter: FilterKey;
  page: number;
  pageSize: number;
}): Promise<CatalogResponse> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  search.set('page', String(params.page));
  search.set('page_size', String(params.pageSize));

  const response = await fetch(`${API_BASE}/api/apps?${search.toString()}`);
  if (!response.ok) throw new Error(`apps_request_failed_${response.status}`);
  return response.json();
}

export async function fetchAppDetails(appId: string): Promise<AppDetails> {
  const response = await fetch(`${API_BASE}/api/apps/${encodeURIComponent(appId)}`);
  if (!response.ok) throw new Error(`app_details_failed_${response.status}`);
  return response.json();
}

export function downloadUrl(appId: string): string {
  return `${API_BASE}/api/apps/${encodeURIComponent(appId)}/download`;
}
