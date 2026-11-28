import { useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  normalizeCatalogStatus,
  parseCatalogFilters,
  preferredCatalogFilter,
  preferredCatalogSearchMode,
} from '../../catalogFilters';

export function useCatalogFilters() {
  const [searchParams, setSearchParams] = useSearchParams();
  const searchKey = searchParams.toString();
  const canonicalSearchKey = useMemo(() => normalizeCatalogStatus(
    searchKey,
    preferredCatalogSearchMode(searchKey, localStorage.getItem('catalog.search.mode')),
    preferredCatalogFilter(searchKey, localStorage.getItem('catalog.filter.status')),
  ), [searchKey]);
  const catalogStatusCanonical = canonicalSearchKey === searchKey;
  const filters = useMemo(() => parseCatalogFilters(canonicalSearchKey), [canonicalSearchKey]);

  useEffect(() => {
    if (!catalogStatusCanonical) setSearchParams(canonicalSearchKey, { replace: true });
  }, [canonicalSearchKey, catalogStatusCanonical, setSearchParams]);

  return { searchKey, canonicalSearchKey, catalogStatusCanonical, filters, setSearchParams };
}
