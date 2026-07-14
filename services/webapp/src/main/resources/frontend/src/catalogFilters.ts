import type { FilterKey, OperatingSystem, SortKey } from './types/catalog';

export const ALL_OPERATING_SYSTEMS: OperatingSystem[] = ['windows', 'linux', 'macos'];

export interface CatalogFilterState {
  query: string;
  filter: FilterKey;
  sort: SortKey;
  page: number;
  pageSize: number;
  tags: string[];
  publishers: string[];
  tagMatchMin?: number;
  operatingSystems: OperatingSystem[];
  architecture?: string;
}

const filterKeys: FilterKey[] = ['all', 'available', 'review', 'missing'];
const sortKeys: SortKey[] = ['updated', 'name'];

export const DEFAULT_CATALOG_FILTERS: CatalogFilterState = {
  query: '',
  filter: 'all',
  sort: 'updated',
  page: 1,
  pageSize: 12,
  tags: [],
  publishers: [],
  operatingSystems: ALL_OPERATING_SYSTEMS,
};

export function parseCatalogFilters(search: URLSearchParams | string): CatalogFilterState {
  const params = typeof search === 'string' ? new URLSearchParams(search) : search;
  const tags = uniqueValues([...params.getAll('tag'), ...csvValues(params.get('tags'))]);
  const publishers = uniqueValues(params.getAll('publisher'));
  const tagMatchMin = numberParam(params.get('tagMatchMin'));
  return {
    query: params.get('query')?.trim() ?? '',
    filter: enumParam(params.get('status'), filterKeys, DEFAULT_CATALOG_FILTERS.filter),
    sort: enumParam(params.get('sort'), sortKeys, DEFAULT_CATALOG_FILTERS.sort),
    page: positiveIntParam(params.get('page'), DEFAULT_CATALOG_FILTERS.page),
    pageSize: positiveIntParam(params.get('pageSize'), DEFAULT_CATALOG_FILTERS.pageSize),
    tags,
    publishers,
    tagMatchMin,
    operatingSystems: parseOperatingSystems(params.getAll('os')),
    architecture: optionalParam(params.get('architecture')),
  };
}

export function catalogFiltersToSearchParams(filters: CatalogFilterState): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.query.trim()) params.set('query', filters.query.trim());
  if (filters.filter !== DEFAULT_CATALOG_FILTERS.filter) params.set('status', filters.filter);
  if (filters.sort !== DEFAULT_CATALOG_FILTERS.sort) params.set('sort', filters.sort);
  if (filters.page !== DEFAULT_CATALOG_FILTERS.page) params.set('page', String(filters.page));
  if (filters.pageSize !== DEFAULT_CATALOG_FILTERS.pageSize) {
    params.set('pageSize', String(filters.pageSize));
  }
  filters.tags.forEach((tag) => params.append('tag', tag));
  filters.publishers.forEach((publisher) => params.append('publisher', publisher));
  if (filters.tags.length && filters.tagMatchMin && filters.tagMatchMin !== filters.tags.length) {
    params.set('tagMatchMin', String(clampTagMatchMin(filters.tagMatchMin, filters.tags.length)));
  }
  if (!hasAllOperatingSystems(filters.operatingSystems)) {
    filters.operatingSystems.forEach((operatingSystem) => params.append('os', operatingSystem));
  }
  if (filters.architecture) params.set('architecture', filters.architecture);
  return params;
}

export function toggleOperatingSystem(
  values: OperatingSystem[],
  operatingSystem: OperatingSystem,
): OperatingSystem[] {
  if (values.includes(operatingSystem)) {
    // At least one platform must remain selected. A completely disabled filter
    // is ambiguous and makes the bookmark state inaccessible to keyboard users.
    return values.length === 1 ? values : values.filter((value) => value !== operatingSystem);
  }
  return ALL_OPERATING_SYSTEMS.filter((value) => values.includes(value) || value === operatingSystem);
}

export function effectiveTagMatchMin(filters: Pick<CatalogFilterState, 'tags' | 'tagMatchMin'>): number {
  if (!filters.tags.length) return 0;
  return clampTagMatchMin(filters.tagMatchMin ?? filters.tags.length, filters.tags.length);
}

export function nextFilters(
  current: CatalogFilterState,
  patch: Partial<CatalogFilterState>,
  resetPage = true,
): CatalogFilterState {
  const tags = patch.tags ?? current.tags;
  const hasTagMatchMinPatch = Object.prototype.hasOwnProperty.call(patch, 'tagMatchMin');
  const tagMatchMin = tags.length
    ? (hasTagMatchMinPatch ? patch.tagMatchMin : current.tagMatchMin)
    : undefined;
  return {
    ...current,
    ...patch,
    page: resetPage ? DEFAULT_CATALOG_FILTERS.page : patch.page ?? current.page,
    tags,
    tagMatchMin: tagMatchMin ? clampTagMatchMin(tagMatchMin, tags.length) : undefined,
  };
}

export function toggleValue(values: string[], value: string): string[] {
  return values.includes(value) ? values.filter((item) => item !== value) : [...values, value];
}

function csvValues(value: string | null): string[] {
  return value ? value.split(',') : [];
}

function uniqueValues(values: string[]): string[] {
  return Array.from(new Set(values.map((value) => value.trim()).filter(Boolean)));
}

function optionalParam(value: string | null): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function parseOperatingSystems(values: string[]): OperatingSystem[] {
  const selected = uniqueValues(values)
    .filter((value): value is OperatingSystem => ALL_OPERATING_SYSTEMS.includes(value as OperatingSystem));
  return selected.length ? ALL_OPERATING_SYSTEMS.filter((value) => selected.includes(value)) : ALL_OPERATING_SYSTEMS;
}

function hasAllOperatingSystems(values: OperatingSystem[]): boolean {
  return ALL_OPERATING_SYSTEMS.every((operatingSystem) => values.includes(operatingSystem));
}

function numberParam(value: string | null): number | undefined {
  if (!value) return undefined;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

function positiveIntParam(value: string | null, fallback: number): number {
  return numberParam(value) ?? fallback;
}

function enumParam<T extends string>(value: string | null, allowed: T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback;
}

function clampTagMatchMin(value: number, selectedTags: number): number {
  return Math.max(1, Math.min(value, selectedTags));
}
