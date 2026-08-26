import type { BundleDetails, BundleResponse } from '../types/catalog';
import { requestJson } from './http';

export function fetchBundles(params: {
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
