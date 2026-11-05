import {
  fetchApps
} from '../../api/catalogApps';
import {
  catalogFiltersToSearchParams, type CatalogFilterState
} from '../../catalogFilters';
import type {
  CatalogResponse
} from '../../types/catalog';

const PAGE_CACHE_TTL_MS = 15_000;
export const pageCache = new Map<string, { response: CatalogResponse; loadedAt: number }>();
export const pendingPages = new Map<string, Promise<CatalogResponse>>();
const queuedPages = new Set<string>();
let prefetchTail: Promise<unknown> = Promise.resolve();

export function enqueuePrefetch<T>(task: () => Promise<T>): Promise<T> {
  const queued = prefetchTail.then(task);
  prefetchTail = queued.catch(() => undefined);
  return queued;
}

export function clearCatalogPageCache() {
  pageCache.clear();
  pendingPages.clear();
}

export function cachedPage(key: string): CatalogResponse | null {
  const entry = pageCache.get(key);
  if (!entry) return null;
  if (Date.now() - entry.loadedAt <= PAGE_CACHE_TTL_MS) return entry.response;
  pageCache.delete(key);
  return null;
}

export function cachePage(key: string, response: CatalogResponse) {
  pageCache.set(key, { response, loadedAt: Date.now() });
  if (pageCache.size > 40) pageCache.delete(pageCache.keys().next().value!);
}

export function prefetchCatalogPage(filters: CatalogFilterState, signal?: AbortSignal) {
  const key = catalogFiltersToSearchParams(filters).toString();
  if (cachedPage(key) || pendingPages.has(key) || queuedPages.has(key)) return;
  queuedPages.add(key);
  void enqueuePrefetch(async () => {
    try {
      if (signal?.aborted || cachedPage(key) || pendingPages.has(key)) return;
      const pending = fetchApps(appRequest(filters), signal);
      pendingPages.set(key, pending);
      try {
        cachePage(key, await pending);
      } finally {
        if (pendingPages.get(key) === pending) pendingPages.delete(key);
      }
    } finally {
      queuedPages.delete(key);
    }
  }).catch(() => undefined);
}

export function appRequest(filters: CatalogFilterState) {
  return {
    query: filters.query,
    filter: filters.filter,
    sort: filters.sort,
    page: filters.page,
    pageSize: filters.pageSize,
    tags: filters.tags,
    minimumTags: filters.minimumTags,
    publisher: filters.publisher,
    operatingSystems: filters.operatingSystems.length === 3 ? undefined : filters.operatingSystems,
    architecture: filters.architecture,
    searchMode: filters.searchMode,
  };
}
