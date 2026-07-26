import type { FilterKey, OperatingSystem, SearchMode, SortKey } from './types/catalog';

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
  searchMode: SearchMode;
}

const filterKeys: FilterKey[] = ['all', 'available', 'review', 'missing'];
const sortKeys: SortKey[] = ['updated', 'downloads', 'name'];
const searchModes: SearchMode[] = ['lexical', 'semantic'];

export const DEFAULT_CATALOG_FILTERS: CatalogFilterState = {
  query: '',
  filter: 'available',
  sort: 'downloads',
  page: 1,
  pageSize: 12,
  tags: [],
  publishers: [],
  operatingSystems: ALL_OPERATING_SYSTEMS,
  searchMode: 'semantic',
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
    searchMode: enumParam(params.get('searchMode'), searchModes, DEFAULT_CATALOG_FILTERS.searchMode),
  };
}

export function normalizeCatalogStatus(
  search: URLSearchParams | string,
  preferredSearchMode?: SearchMode,
  preferredFilter?: FilterKey,
): string {
  const params = new URLSearchParams(typeof search === 'string' ? search : search.toString());
  const status = params.get('status');
  if (status !== null && !filterKeys.includes(status as FilterKey)) {
    params.delete('status');
  }
  const mode = params.get('searchMode');
  if (mode !== null && !searchModes.includes(mode as SearchMode)) {
    params.delete('searchMode');
  }
  if (!params.has('searchMode') && preferredSearchMode) {
    params.set('searchMode', preferredSearchMode);
  }
  if (!params.has('status') && preferredFilter && preferredFilter !== DEFAULT_CATALOG_FILTERS.filter) {
    params.set('status', preferredFilter);
  }
  return params.toString();
}

export function preferredCatalogSearchMode(
  search: URLSearchParams | string,
  storedPreference: string | null,
): SearchMode {
  const params = typeof search === 'string' ? new URLSearchParams(search) : search;
  const urlMode = params.get('searchMode');
  if (searchModes.includes(urlMode as SearchMode)) return urlMode as SearchMode;
  if (searchModes.includes(storedPreference as SearchMode)) return storedPreference as SearchMode;
  return 'semantic';
}

export function preferredCatalogFilter(
  search: URLSearchParams | string,
  storedPreference: string | null,
): FilterKey {
  const params = typeof search === 'string' ? new URLSearchParams(search) : search;
  const urlFilter = params.get('status');
  if (filterKeys.includes(urlFilter as FilterKey)) return urlFilter as FilterKey;
  if (filterKeys.includes(storedPreference as FilterKey)) return storedPreference as FilterKey;
  return DEFAULT_CATALOG_FILTERS.filter;
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
  params.set('searchMode', filters.searchMode);
  return params;
}

export function toggleOperatingSystem(
  values: OperatingSystem[],
  operatingSystem: OperatingSystem,
): OperatingSystem[] {
  if (values.includes(operatingSystem)) {
    // Turning off the last selected platform restores the other two. This keeps
    // the filter useful without trapping the user on a single operating system.
    return values.length === 1
      ? ALL_OPERATING_SYSTEMS.filter((value) => value !== operatingSystem)
      : values.filter((value) => value !== operatingSystem);
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
