import { Navigate, useSearchParams } from 'react-router-dom';
import { bundleFiltersToSearch, parseBundleFilters } from '../../bundleCatalogFilters';
import { lazyNamed } from '../../routing/lazyNamed';
const CatalogPage = lazyNamed(() => import('./CatalogPages'), 'CatalogPage');
const BASE = '/catalog/bundles';

/** Mantiene los enlaces publicados antes de que existiera el catálogo de bundles. */
export function CatalogEntry() {
  const [params] = useSearchParams();
  return params.has('bundleType')
    ? <Navigate replace to={`${BASE}?${bundleFiltersToSearch(parseBundleFilters(params))}`} />
    : <CatalogPage />;
}
