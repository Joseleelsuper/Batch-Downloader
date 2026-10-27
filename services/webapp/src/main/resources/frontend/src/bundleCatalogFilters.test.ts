import { expect, it } from 'vitest';
import { bundleFiltersToSearch, bundleRequest, parseBundleFilters } from './bundleCatalogFilters';

it('normaliza enlaces comunitarios antiguos y conserva todos los filtros compartibles', () => {
  const filters = parseBundleFilters(new URLSearchParams('bundleType=community&query=dev&searchMode=semantic&sort=stars&page=2&pageSize=24&tag=work&creator=uuid&os=linux'));
  const restored = parseBundleFilters(bundleFiltersToSearch(filters));
  expect(restored).toEqual(filters);
  expect(bundleRequest(restored)).toMatchObject({ type: 'public', tags: ['work'], operatingSystems: ['linux'], page: 2 });
});

it('no hereda los estados de apps y descarta relevancia sin búsqueda semántica', () => {
  const filters = parseBundleFilters(new URLSearchParams('status=review&sort=relevance&page=-1&pageSize=999&os=other'));
  expect(filters).toMatchObject({ bundleType: 'all', sort: 'downloads', page: 1, pageSize: 12 });
  expect(bundleRequest(filters).operatingSystems).toBeUndefined();
});
