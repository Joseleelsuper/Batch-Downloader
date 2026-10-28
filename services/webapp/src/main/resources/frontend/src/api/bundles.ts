import type { BundleDetails, BundleResponse, BundleStarState, BundleFacets, SearchMode, OperatingSystem } from '../types/catalog';
import { requestJson } from './http';

export interface BundleSearchParams {
  type?: 'official' | 'community' | 'public' | 'user';
  page?: number;
  pageSize?: number;
  sort?: string;
  query?: string;
  searchMode?: SearchMode;
  tags?: string[];
  creator?: string;
  operatingSystems?: OperatingSystem[];
}

export function bundleSearch(params: BundleSearchParams): URLSearchParams {
  const search = new URLSearchParams();
  if (params.type) search.set('type', params.type);
  search.set('page', String(params.page ?? 1));
  search.set('pageSize', String(params.pageSize ?? 12));
  search.set('sort', params.sort ?? 'updated');
  if (params.query) search.set('query', params.query);
  if (params.searchMode) search.set('searchMode', params.searchMode);
  params.tags?.forEach((tag) => search.append('tag', tag));
  if (params.creator) search.set('creator', params.creator);
  params.operatingSystems?.forEach((os) => search.append('os', os));
  return search;
}

export function fetchBundles(params: BundleSearchParams, signal?: AbortSignal): Promise<BundleResponse> {
  const search = bundleSearch(params);
  return requestJson<BundleResponse>(`/api/v1/bundles?${search.toString()}`, { signal });
}

export function fetchBundleFacets(params: BundleSearchParams, signal?: AbortSignal): Promise<BundleFacets> {
  return requestJson<BundleFacets>(`/api/v1/bundles/catalog/facets?${bundleSearch(params)}`, { signal });
}

export function setBundleStar(id: string, starred: boolean): Promise<BundleStarState> {
  return requestJson<BundleStarState>(`/api/v1/bundles/${encodeURIComponent(id)}/star`, { method: starred ? 'PUT' : 'DELETE' });
}

export function fetchBundle(slug: string): Promise<BundleDetails> {
  return requestJson<BundleDetails>(`/api/v1/bundles/${encodeURIComponent(slug)}`);
}

export function createAdminBundle(payload: Record<string, unknown>): Promise<BundleDetails> {
  return requestJson<BundleDetails>('/api/v1/admin/bundles', {
    method: 'POST', body: JSON.stringify(payload),
  });
}

export function updateAdminBundle(
  bundleId: string,
  payload: Record<string, unknown>,
): Promise<BundleDetails> {
  return requestJson<BundleDetails>(`/api/v1/admin/bundles/${encodeURIComponent(bundleId)}`, {
    method: 'PATCH', body: JSON.stringify(payload),
  });
}

export function fetchAdminBundleCustomTags(): Promise<string[]> {
  return requestJson<string[]>('/api/v1/admin/bundles/custom-tags');
}

export function createAdminBundleCustomTag(tag: string): Promise<{ tag: string }> {
  return requestJson<{ tag: string }>('/api/v1/admin/bundles/custom-tags', {
    method: 'POST', body: JSON.stringify({ tag }),
  });
}
