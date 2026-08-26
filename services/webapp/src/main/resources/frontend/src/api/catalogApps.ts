import type {
  AppDetails,
  CatalogChangeEvent,
  CatalogFacets,
  CatalogResponse,
  CatalogStats,
  FilterKey,
  OperatingSystem,
  SearchMode,
  SortKey,
} from '../types/catalog';
import { API_BASE, requestJson } from './http';
import { apiWebSocketUrl, connectJsonWebSocket, type LiveConnectionState } from './liveConnection';

export async function fetchApps(params: {
  query: string;
  filter: FilterKey;
  sort: SortKey;
  page: number;
  pageSize: number;
  tags?: string[];
  publisher?: string;
  operatingSystems?: OperatingSystem[];
  architecture?: string;
  searchMode?: SearchMode;
}, signal?: AbortSignal): Promise<CatalogResponse> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  params.tags?.forEach((tag) => search.append('tag', tag));
  if (params.publisher) search.set('publisher', params.publisher);
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
  publisher?: string;
  operatingSystems?: OperatingSystem[];
  architecture?: string;
  searchMode?: SearchMode;
}): Promise<CatalogFacets> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  if (params.filter !== 'all') search.set('status', params.filter);
  params.tags?.forEach((tag) => search.append('tag', tag));
  if (params.publisher) search.set('publisher', params.publisher);
  params.operatingSystems?.forEach((operatingSystem) => search.append('os', operatingSystem));
  if (params.architecture) search.set('architecture', params.architecture);
  if (params.searchMode) search.set('searchMode', params.searchMode);
  return requestJson<CatalogFacets>(`/api/v1/apps/facets?${search.toString()}`);
}

export function fetchCatalogStats(signal?: AbortSignal): Promise<CatalogStats> {
  return requestJson<CatalogStats>('/api/v1/apps/stats', { signal });
}

export function fetchAppDetails(appId: string, signal?: AbortSignal): Promise<AppDetails> {
  return requestJson<AppDetails>(`/api/v1/apps/${encodeURIComponent(appId)}`, { signal });
}

export function catalogWebSocketUrl(): string {
  return apiWebSocketUrl('/api/v1/catalog/ws', API_BASE);
}

export function connectCatalogEvents(
  onEvent: (event: CatalogChangeEvent) => void,
  onState?: (state: LiveConnectionState) => void,
): () => void {
  return connectJsonWebSocket(catalogWebSocketUrl(), 'catalog.changed', onEvent, onState);
}
