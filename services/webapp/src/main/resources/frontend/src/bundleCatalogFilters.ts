import { ALL_OPERATING_SYSTEMS } from './catalogFilters';
import type { BundleSortKey, OperatingSystem, SearchMode } from './types/catalog';

export interface BundleCatalogFilters {
  bundleType: 'all' | 'public' | 'official';
  query: string;
  searchMode: SearchMode;
  sort: BundleSortKey;
  page: number;
  pageSize: number;
  tags: string[];
  creator?: string;
  operatingSystems: OperatingSystem[];
}

export function parseBundleFilters(params: URLSearchParams): BundleCatalogFilters {
  const type = params.get('bundleType');
  const sort = params.get('sort');
  const page = Number(params.get('page') ?? 1);
  const size = Number(params.get('pageSize') ?? 12);
  const query = params.get('query')?.trim() ?? '';
  const searchMode = params.get('searchMode') === 'lexical' ? 'lexical' : 'semantic';
  const os = ALL_OPERATING_SYSTEMS.filter((value) => params.getAll('os').includes(value));
  return {
    bundleType: type === 'official' ? 'official' : ['public', 'community', 'user'].includes(type ?? '') ? 'public' : 'all',
    query, searchMode,
    sort: (['updated', 'stars', 'name', 'downloads', ...(query && searchMode === 'semantic' ? ['relevance'] : [])].includes(sort ?? '')
      ? sort : 'downloads') as BundleSortKey,
    page: Number.isSafeInteger(page) && page > 0 && page <= 1_000_000 ? page : 1,
    pageSize: [12, 24, 48].includes(size) ? size : 12,
    tags: [...new Set(params.getAll('tag').map((tag) => tag.trim()).filter(Boolean))],
    creator: params.get('creator') || undefined,
    operatingSystems: os.length ? os : ALL_OPERATING_SYSTEMS,
  };
}

export function bundleFiltersToSearch(filters: BundleCatalogFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.bundleType !== 'all') params.set('bundleType', filters.bundleType);
  if (filters.query.trim()) params.set('query', filters.query.trim());
  params.set('searchMode', filters.searchMode);
  params.set('sort', filters.sort);
  if (filters.page !== 1) params.set('page', String(filters.page));
  if (filters.pageSize !== 12) params.set('pageSize', String(filters.pageSize));
  filters.tags.forEach((tag) => params.append('tag', tag));
  if (filters.creator) params.set('creator', filters.creator);
  if (filters.operatingSystems.length !== 3) filters.operatingSystems.forEach((os) => params.append('os', os));
  return params;
}

export function bundleRequest(filters: BundleCatalogFilters) {
  return { ...filters, type: filters.bundleType === 'all' ? undefined : filters.bundleType,
    operatingSystems: filters.operatingSystems.length === 3 ? undefined : filters.operatingSystems };
}
