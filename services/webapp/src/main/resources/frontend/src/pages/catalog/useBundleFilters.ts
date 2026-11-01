import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { bundleFiltersToSearch, parseBundleFilters, type BundleCatalogFilters } from '../../bundleCatalogFilters';


export function useBundleFilters() {
  const [params, setParams] = useSearchParams();
  const key = params.toString();
  const filters = useMemo(() => parseBundleFilters(new URLSearchParams(key)), [key]);
  const update = useCallback((patch: Partial<BundleCatalogFilters>, resetPage = true) => {
    setParams(bundleFiltersToSearch({ ...filters, ...patch, page: resetPage ? 1 : patch.page ?? filters.page }));
  }, [filters, setParams]);
  return { filters, key, update };
}
