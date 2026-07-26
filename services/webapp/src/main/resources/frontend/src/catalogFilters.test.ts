import { describe, expect, it } from 'vitest';
import {
  catalogFiltersToSearchParams,
  effectiveTagMatchMin,
  nextFilters,
  normalizeCatalogStatus,
  parseCatalogFilters,
  preferredCatalogFilter,
  preferredCatalogSearchMode,
  toggleOperatingSystem,
  toggleValue,
} from './catalogFilters';

describe('catalogFilters', () => {
  it('parses repeated tags, legacy csv tags, publishers and tagMatchMin', () => {
    const filters = parseCatalogFilters(
      'query=editor&status=available&tag=.NET&tag=runtime&tags=Windows,Desktop&publisher=ACME%2C%20Inc.&tagMatchMin=2&page=3&pageSize=24',
    );

    expect(filters.tags).toEqual(['.NET', 'runtime', 'Windows', 'Desktop']);
    expect(filters.publishers).toEqual(['ACME, Inc.']);
    expect(filters.tagMatchMin).toBe(2);
    expect(filters.filter).toBe('available');
    expect(filters.page).toBe(3);
    expect(filters.pageSize).toBe(24);
  });

  it('serializes filters with repeated params and omits default tag minimum', () => {
    const params = catalogFiltersToSearchParams({
      query: '',
      filter: 'all',
      sort: 'updated',
      page: 1,
      pageSize: 12,
      tags: ['.NET', 'runtime'],
      publishers: ['ACME, Inc.'],
      tagMatchMin: 2,
      operatingSystems: ['windows', 'linux'],
      searchMode: 'semantic',
    });

    expect(params.getAll('tag')).toEqual(['.NET', 'runtime']);
    expect(params.getAll('publisher')).toEqual(['ACME, Inc.']);
    expect(params.has('tagMatchMin')).toBe(false);
    expect(params.getAll('os')).toEqual(['windows', 'linux']);
  });

  it('clamps tagMatchMin when a selected tag is removed', () => {
    const filters = nextFilters({
      query: '',
      filter: 'all',
      sort: 'updated',
      page: 4,
      pageSize: 12,
      tags: ['a', 'b', 'c'],
      publishers: [],
      tagMatchMin: 3,
      operatingSystems: ['windows', 'linux', 'macos'],
      searchMode: 'semantic',
    }, { tags: ['a', 'b'] });

    expect(filters.page).toBe(1);
    expect(effectiveTagMatchMin(filters)).toBe(2);
  });

  it('toggles values preserving order for selected facets', () => {
    expect(toggleValue(['a'], 'b')).toEqual(['a', 'b']);
    expect(toggleValue(['a', 'b'], 'a')).toEqual(['b']);
  });

  it('normalizes removed and unknown public statuses without discarding other filters', () => {
    expect(normalizeCatalogStatus('query=editor&status=pending&tag=.NET')).toBe('query=editor&tag=.NET');
    expect(normalizeCatalogStatus('status=unknown&page=2')).toBe('page=2');
    expect(normalizeCatalogStatus('status=review&page=2')).toBe('status=review&page=2');
    expect(parseCatalogFilters('status=pending').filter).toBe('available');
    expect(parseCatalogFilters('status=unknown').filter).toBe('available');
  });

  it('parses repeated operating systems, omits all active systems and restores alternatives from one active system', () => {
    expect(parseCatalogFilters('os=linux&os=windows').operatingSystems).toEqual(['windows', 'linux']);
    expect(catalogFiltersToSearchParams({
      query: '',
      filter: 'all',
      sort: 'updated',
      page: 1,
      pageSize: 12,
      tags: [],
      publishers: [],
      operatingSystems: ['windows', 'linux', 'macos'],
      searchMode: 'semantic',
    }).getAll('os')).toEqual([]);
    expect(toggleOperatingSystem(['windows'], 'windows')).toEqual(['linux', 'macos']);
    expect(toggleOperatingSystem(['windows'], 'linux')).toEqual(['windows', 'linux']);
  });

  it('prioritizes URL mode, then the stored choice, then semantic for a first visit', () => {
    expect(preferredCatalogSearchMode('searchMode=lexical', 'semantic')).toBe('lexical');
    expect(preferredCatalogSearchMode('', 'lexical')).toBe('lexical');
    expect(preferredCatalogSearchMode('', null)).toBe('semantic');
    expect(normalizeCatalogStatus('query=editor', 'semantic')).toBe(
      'query=editor&searchMode=semantic',
    );
  });

  it('defaults to available and prioritizes the URL over the stored status choice', () => {
    expect(preferredCatalogFilter('status=review', 'all')).toBe('review');
    expect(preferredCatalogFilter('', 'all')).toBe('all');
    expect(preferredCatalogFilter('', null)).toBe('available');
    expect(normalizeCatalogStatus('query=editor', 'semantic', 'all')).toBe(
      'query=editor&searchMode=semantic&status=all',
    );
    expect(catalogFiltersToSearchParams({
      ...parseCatalogFilters(''),
      filter: 'available',
    }).has('status')).toBe(false);
  });

  it('parses and serializes the most-downloaded sort order', () => {
    expect(parseCatalogFilters('sort=downloads').sort).toBe('downloads');
    expect(catalogFiltersToSearchParams({
      ...parseCatalogFilters(''),
      sort: 'downloads',
    }).get('sort')).toBe('downloads');
  });
});
