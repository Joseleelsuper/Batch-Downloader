import { describe, expect, it } from 'vitest';
import {
  catalogFiltersToSearchParams,
  effectiveTagMatchMin,
  nextFilters,
  parseCatalogFilters,
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
    });

    expect(params.getAll('tag')).toEqual(['.NET', 'runtime']);
    expect(params.getAll('publisher')).toEqual(['ACME, Inc.']);
    expect(params.has('tagMatchMin')).toBe(false);
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
    }, { tags: ['a', 'b'] });

    expect(filters.page).toBe(1);
    expect(effectiveTagMatchMin(filters)).toBe(2);
  });

  it('toggles values preserving order for selected facets', () => {
    expect(toggleValue(['a'], 'b')).toEqual(['a', 'b']);
    expect(toggleValue(['a', 'b'], 'a')).toEqual(['b']);
  });
});
