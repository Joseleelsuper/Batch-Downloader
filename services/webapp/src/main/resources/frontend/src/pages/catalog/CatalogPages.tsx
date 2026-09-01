import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import {
  connectCatalogEvents,
  fetchAppDetails,
  fetchApps,
  fetchCatalogFacets,
  fetchCatalogStats,
} from '../../api/catalogApps';
import {
  catalogFiltersToSearchParams,
  nextFilters,
  normalizeCatalogStatus,
  parseCatalogFilters,
  preferredCatalogFilter,
  preferredCatalogSearchMode,
  toggleOperatingSystem,
  toggleValue,
  type CatalogFilterState,
} from '../../catalogFilters';
import {
  inspectCatalogSelectionRefresh,
  isCatalogAppSelectable,
  validateCatalogSelection,
} from '../../catalogSelection';
import { AppFilters } from '../../components/AppFilters';
import { AppSearchBar } from '../../components/AppSearchBar';
import { AppTable } from '../../components/AppTable';
import { Pagination } from '../../components/Pagination';
import { useDownloadJob } from '../../hooks/useDownloadJob';
import { useTranslation, type Translator } from '../../services/i18n';
import type {
  AppDetails,
  CatalogAlphabetEntry,
  CatalogApp,
  CatalogFacets,
  CatalogStats,
  FacetItem,
  FilterKey,
} from '../../types/catalog';
import { formatDate } from '../../utils/date';

const DEFAULT_COUNTS: Record<FilterKey, number> = {
  all: 0,
  available: 0,
  review: 0,
  missing: 0,
};
const FACET_ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')];
const CATALOG_REFRESH_INTERVAL_MS = 5_000;

function searchNoticeFor(t: Translator, degradedReason?: string | null): string | null {
  if (!degradedReason) return null;
  return t(
    degradedReason === 'semantic_query_too_short'
      ? 'catalog.search.degraded.shortQuery'
      : 'catalog.search.degraded',
  );
}

export function CatalogPage() {
  const t = useTranslation();
  const { appId } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const searchKey = searchParams.toString();
  const preferredSearchMode = useMemo(
    () => preferredCatalogSearchMode(
      searchKey,
      localStorage.getItem('catalog.search.mode'),
    ),
    [searchKey],
  );
  const preferredFilter = useMemo(
    () => preferredCatalogFilter(
      searchKey,
      localStorage.getItem('catalog.filter.status'),
    ),
    [searchKey],
  );
  const canonicalSearchKey = useMemo(
    () => normalizeCatalogStatus(searchKey, preferredSearchMode, preferredFilter),
    [preferredFilter, preferredSearchMode, searchKey],
  );
  const catalogStatusCanonical = canonicalSearchKey === searchKey;
  const filters = useMemo(() => parseCatalogFilters(canonicalSearchKey), [canonicalSearchKey]);
  const [query, setQuery] = useState(filters.query);
  const [apps, setApps] = useState<CatalogApp[]>([]);
  const [total, setTotal] = useState(0);
  const [alphabet, setAlphabet] = useState<CatalogAlphabetEntry[]>([]);
  const [loadingApps, setLoadingApps] = useState(true);
  const [stats, setStats] = useState<CatalogStats | null>(null);
  const [selected, setSelected] = useState<AppDetails | null>(null);
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchNotice, setSearchNotice] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [filtersVisible, setFiltersVisible] = useState(() => localStorage.getItem('catalog.filters.open') !== 'false');
  const [selectedDownloadIds, setSelectedDownloadIds] = useState<Set<string>>(new Set());
  const [selectedDownloadApps, setSelectedDownloadApps] = useState<CatalogApp[]>([]);
  const [validatingSelection, setValidatingSelection] = useState(false);
  const downloadJob = useDownloadJob();
  const selectedDownloadIdsRef = useRef<Set<string>>(new Set());
  const lastLoadedPage = useRef<{ searchKey: string; apps: CatalogApp[] } | null>(null);

  useEffect(() => {
    if (!catalogStatusCanonical) {
      setSearchParams(canonicalSearchKey, { replace: true });
    }
  }, [canonicalSearchKey, catalogStatusCanonical, setSearchParams]);

  useEffect(() => {
    setQuery(filters.query);
  }, [filters.query]);

  const updateFilters = useCallback((patch: Partial<CatalogFilterState>, resetPage = true, replace = false) => {
    const params = catalogFiltersToSearchParams(nextFilters(filters, patch, resetPage));
    setSearchParams(params, { replace });
  }, [filters, setSearchParams]);

  const commitDownloadSelection = useCallback((next: Set<string>, addedApp?: CatalogApp) => {
    selectedDownloadIdsRef.current = next;
    setSelectedDownloadIds(next);
    setSelectedDownloadApps((current) => {
      const retained = current.filter((app) => next.has(app.id));
      if (addedApp && next.has(addedApp.id) && !retained.some((app) => app.id === addedApp.id)) {
        return [...retained, addedApp];
      }
      if (retained.length === current.length) return current;
      return retained;
    });
  }, []);

  const removeDownloadSelections = useCallback((ids: readonly string[]) => {
    if (ids.length === 0) return;
    const next = new Set(selectedDownloadIdsRef.current);
    let changed = false;
    ids.forEach((id) => {
      changed = next.delete(id) || changed;
    });
    if (changed) commitDownloadSelection(next);
  }, [commitDownloadSelection]);

  const clearDownloadSelection = useCallback(() => {
    commitDownloadSelection(new Set());
  }, [commitDownloadSelection]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (query !== filters.query) {
        updateFilters({ query }, true, true);
      }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [filters.query, query, updateFilters]);

  useEffect(() => {
    let refreshTimer: number | undefined;
    const disconnect = connectCatalogEvents(() => {
      if (refreshTimer !== undefined) return;
      refreshTimer = window.setTimeout(() => {
        refreshTimer = undefined;
        setRefreshToken((value) => value + 1);
      }, CATALOG_REFRESH_INTERVAL_MS);
    });
    return () => {
      disconnect();
      if (refreshTimer !== undefined) window.clearTimeout(refreshTimer);
    };
  }, []);

  useEffect(() => {
    if (!catalogStatusCanonical) {
      setApps([]);
      setTotal(0);
      setAlphabet([]);
      setLoadingApps(true);
      return undefined;
    }
    let cancelled = false;
    const controller = new AbortController();
    const lastPage = lastLoadedPage.current;
    const previousPage = lastPage?.searchKey === canonicalSearchKey
      ? lastPage.apps
      : null;
    const replacingQuery = previousPage === null;
    if (replacingQuery) {
      setApps([]);
      setTotal(0);
      setAlphabet([]);
      setLoadingApps(true);
    }
    setError(null);
    fetchApps({
      query: filters.query,
      filter: filters.filter,
      sort: filters.sort,
      page: filters.page,
      pageSize: filters.pageSize,
      tags: filters.tags,
      publisher: filters.publisher,
      operatingSystems: filters.operatingSystems.length === 3 ? undefined : filters.operatingSystems,
      architecture: filters.architecture,
      searchMode: filters.searchMode,
    }, controller.signal)
      .then(async (response) => {
        if (cancelled) return;
        const refreshInspection = previousPage
          ? inspectCatalogSelectionRefresh(
            selectedDownloadIdsRef.current,
            previousPage,
            response.data,
          )
          : null;
        if (refreshInspection) removeDownloadSelections(refreshInspection.invalidIds);
        setApps(response.data);
        setTotal(response.total);
        setAlphabet(response.alphabet ?? []);
        setSearchNotice(searchNoticeFor(t, response.degradedReason));
        lastLoadedPage.current = { searchKey: canonicalSearchKey, apps: response.data };
        if (refreshInspection?.missingIds.length) {
          const validation = await validateCatalogSelection(
            refreshInspection.missingIds,
            (id) => fetchAppDetails(id, controller.signal),
          );
          if (!cancelled) removeDownloadSelections(validation.invalidIds);
        }
      })
      .catch((requestError: unknown) => {
        if (!cancelled && !isAbortError(requestError)) setError(t('catalog.error.load'));
      })
      .finally(() => {
        if (!cancelled && replacingQuery) setLoadingApps(false);
      });
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [canonicalSearchKey, catalogStatusCanonical, filters, refreshToken, removeDownloadSelections, t]);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    fetchCatalogStats(controller.signal)
      .then((response) => {
        if (!cancelled) setStats(response);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [refreshToken]);

  useEffect(() => {
    let cancelled = false;
    if (!appId) {
      setSelectedId(undefined);
      setLoadingDetails(false);
      return () => {
        cancelled = true;
      };
    }
    setSelectedId(appId);
    setLoadingDetails(true);
    fetchAppDetails(appId)
      .then((details) => {
        if (cancelled) return;
        setSelected(details);
        setSelectedId(details.id);
      })
      .catch(() => {
        if (cancelled) return;
        setSelected(null);
        setError(t('catalog.error.detail'));
      })
      .finally(() => {
        if (!cancelled) setLoadingDetails(false);
      });
    return () => {
      cancelled = true;
    };
  }, [appId, refreshToken, t]);

  function toggleAppDetails(app: CatalogApp) {
    if (selectedId === app.id) {
      setSelectedId(undefined);
      setLoadingDetails(false);
      navigate({ pathname: '/catalog', search: searchKey });
      return;
    }
    setSelectedId(app.id);
    navigate({ pathname: `/catalog/app/${app.id}`, search: searchKey });
  }

  function toggleDownloadSelection(app: CatalogApp) {
    if (!isCatalogAppSelectable(app)) return;
    const next = new Set(selectedDownloadIdsRef.current);
    if (next.has(app.id)) {
      next.delete(app.id);
    } else {
      if (next.size >= 100) return;
      next.add(app.id);
    }
    commitDownloadSelection(next, app);
  }

  async function downloadSelection() {
    if (validatingSelection || selectedDownloadIdsRef.current.size < 1) return;
    setValidatingSelection(true);
    setError(null);
    try {
      const validation = await validateCatalogSelection(
        Array.from(selectedDownloadIdsRef.current),
        fetchAppDetails,
      );
      removeDownloadSelections(validation.invalidIds);
      if (validation.unresolvedIds.length > 0) {
        setError(t('catalog.error.zip'));
        return;
      }
      const validIds = validation.validIds
        .filter((id) => selectedDownloadIdsRef.current.has(id));
      if (validIds.length < 1) {
        setError(t('catalog.error.zip'));
        return;
      }
      await downloadJob.start({
        appIds: validIds,
        operatingSystems: filters.operatingSystems.length === 3 ? undefined : filters.operatingSystems,
      }, t('download.job.selectionLabel', { count: validIds.length }));
    } catch {
      setError(t('catalog.error.zip'));
    } finally {
      setValidatingSelection(false);
    }
  }

  return (
    <main className={`workspace ${filtersVisible ? '' : 'filters-hidden'}`}>
      <div className="filter-rail-shell" hidden={!filtersVisible}>
        <AppFilters
          active={filters.filter}
          counts={stats?.filters ?? DEFAULT_COUNTS}
          selectedTagCount={filters.tags.length}
          selectedPublisherCount={filters.publisher ? 1 : 0}
          catalogSearch={searchKey}
          selectedApps={selectedDownloadApps}
          downloading={downloadJob.starting || validatingSelection}
          operatingSystems={filters.operatingSystems}
          onChange={(nextFilter) => {
            localStorage.setItem('catalog.filter.status', nextFilter);
            updateFilters({ filter: nextFilter });
          }}
          onToggleOperatingSystem={(operatingSystem) => {
            updateFilters({ operatingSystems: toggleOperatingSystem(filters.operatingSystems, operatingSystem) });
          }}
          onClearTags={() => updateFilters({ tags: [] })}
          onClearPublisher={() => updateFilters({ publisher: undefined })}
          onDownloadSelected={() => void downloadSelection()}
          onClearSelection={clearDownloadSelection}
          onRemoveSelected={(id) => removeDownloadSelections([id])}
        />
      </div>
      <button
        className="filter-bookmark"
        type="button"
        aria-controls="catalog-filters"
        aria-expanded={filtersVisible}
        aria-label={filtersVisible ? t('catalog.filters.close') : t('catalog.filters.open')}
        title={filtersVisible ? t('catalog.filters.close') : t('catalog.filters.open')}
        onClick={() => setFiltersVisible((visible) => {
          const next = !visible;
          localStorage.setItem('catalog.filters.open', String(next));
          return next;
        })}
      >
        {filtersVisible ? <ChevronLeft size={18} /> : <ChevronRight size={18} />}
      </button>
      <section className="catalog-panel">
        <div className="catalog-header-row">
          <span>{formatLastScrape(t, stats)}</span>
        </div>
        <AppSearchBar
          value={query}
          sort={filters.sort}
          searchMode={filters.searchMode}
          onChange={setQuery}
          onSortChange={(nextSort) => {
            updateFilters({ sort: nextSort });
          }}
          onSearchModeChange={(nextMode) => {
            localStorage.setItem('catalog.search.mode', nextMode);
            updateFilters({ searchMode: nextMode });
          }}
        />
        {searchNotice ? <p className="semantic-notice">{searchNotice}</p> : null}
        {error ? <p className="error-banner">{error}</p> : null}
        {filters.sort === 'name' ? (
          <nav className="catalog-alphabet" aria-label={t('catalog.alphabet.aria')}>
            {FACET_ALPHABET.map((letter) => {
              const entry = alphabet.find((candidate) => candidate.letter === letter);
              return (
                <button
                  key={letter}
                  type="button"
                  disabled={!entry}
                  aria-label={entry
                    ? t('catalog.alphabet.letter', { letter, count: entry.count })
                    : t('catalog.alphabet.empty', { letter })}
                  onClick={() => {
                    if (entry) updateFilters({ page: entry.page }, false);
                  }}
                >
                  {letter}
                </button>
              );
            })}
          </nav>
        ) : null}
        <AppTable
          apps={apps}
          loading={loadingApps}
          selectedId={selectedId}
          selectedIds={selectedDownloadIds}
          selectedCount={selectedDownloadIds.size}
          details={selected}
          loadingDetails={loadingDetails}
          onToggleDetails={toggleAppDetails}
          onToggleSelection={toggleDownloadSelection}
        />
        <Pagination
          page={filters.page}
          pageSize={filters.pageSize}
          total={total}
          onPageChange={(nextPage) => updateFilters({ page: nextPage }, false)}
          onPageSizeChange={(nextPageSize) => {
            updateFilters({ pageSize: nextPageSize });
          }}
        />
      </section>
    </main>
  );
}

export function FacetDirectoryPage({ kind }: { kind: 'tags' | 'publishers' }) {
  const t = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const searchKey = searchParams.toString();
  const preferredSearchMode = useMemo(
    () => preferredCatalogSearchMode(
      searchKey,
      localStorage.getItem('catalog.search.mode'),
    ),
    [searchKey],
  );
  const preferredFilter = useMemo(
    () => preferredCatalogFilter(
      searchKey,
      localStorage.getItem('catalog.filter.status'),
    ),
    [searchKey],
  );
  const canonicalSearchKey = useMemo(
    () => normalizeCatalogStatus(searchKey, preferredSearchMode, preferredFilter),
    [preferredFilter, preferredSearchMode, searchKey],
  );
  const catalogStatusCanonical = canonicalSearchKey === searchKey;
  const filters = useMemo(() => parseCatalogFilters(canonicalSearchKey), [canonicalSearchKey]);
  const [facets, setFacets] = useState<CatalogFacets>({ tags: [], publishers: [] });
  const [activeLetter, setActiveLetter] = useState('A');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const items = kind === 'tags' ? facets.tags : facets.publishers;
  const selectedValues = kind === 'tags' ? filters.tags : filters.publisher ? [filters.publisher] : [];
  const selectedSet = new Set(selectedValues);
  const lettersWithItems = useMemo(() => new Set(items.map((item) => item.letter)), [items]);
  const visibleItems = items.filter((item) => item.letter === activeLetter);
  const title = kind === 'tags' ? t('facet.tags.title') : t('facet.publishers.title');
  const subtitle = kind === 'tags' ? t('facet.tags.subtitle') : t('facet.publishers.subtitle');

  useEffect(() => {
    if (!catalogStatusCanonical) {
      setSearchParams(canonicalSearchKey, { replace: true });
    }
  }, [canonicalSearchKey, catalogStatusCanonical, setSearchParams]);

  useEffect(() => {
    if (!catalogStatusCanonical) {
      setFacets({ tags: [], publishers: [] });
      setLoading(true);
      return undefined;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchCatalogFacets({
      query: filters.query,
      filter: filters.filter,
      tags: filters.tags,
      publisher: filters.publisher,
      operatingSystems: filters.operatingSystems.length === 3 ? undefined : filters.operatingSystems,
      architecture: filters.architecture,
      searchMode: filters.searchMode,
    })
      .then((response) => {
        if (!cancelled) setFacets(response);
      })
      .catch(() => {
        if (!cancelled) setError(t('facet.loadError'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [catalogStatusCanonical, filters, t]);

  useEffect(() => {
    if (!items.length || lettersWithItems.has(activeLetter)) return;
    const firstLetter = FACET_ALPHABET.find((letter) => lettersWithItems.has(letter)) ?? 'A';
    setActiveLetter(firstLetter);
  }, [activeLetter, items.length, lettersWithItems]);

  function updateFilters(patch: Partial<CatalogFilterState>) {
    setSearchParams(catalogFiltersToSearchParams(nextFilters(filters, patch)));
  }

  function toggleFacet(item: FacetItem) {
    if (kind === 'tags') {
      updateFilters({ tags: toggleValue(filters.tags, item.value) });
      return;
    }
    updateFilters({ publisher: filters.publisher === item.value ? undefined : item.value });
  }

  return (
    <main className="facet-page">
      <section className="facet-header">
        <div>
          <span>{t('facet.header')}</span>
          <h2>{title}</h2>
          <p>{subtitle}</p>
        </div>
        <Link className="secondary-button facet-back-link" to={{ pathname: '/catalog', search: canonicalSearchKey }}>
          {t('facet.backToCatalog')}
        </Link>
      </section>

      {error ? <p className="error-banner">{error}</p> : null}
      {loading ? <p className="loading-label">{t('facet.loading')}</p> : null}
      {!loading && !error && !items.length ? (
        <section className="facet-empty-state">
          <p>{t('facet.emptyCompatible')}</p>
          {filters.tags.length || filters.publisher ? (
            <button
              className="secondary-button"
              type="button"
              onClick={() => updateFilters({ tags: [], publisher: undefined })}
            >
              {t('facet.resetFilters')}
            </button>
          ) : null}
        </section>
      ) : null}
      {items.length ? (
        <>
          <nav className="facet-letter-nav" aria-label={t('facet.lettersAria', { title })}>
            {FACET_ALPHABET.map((letter) => (
              <button
                key={letter}
                className={activeLetter === letter ? 'facet-letter-active' : ''}
                type="button"
                disabled={!lettersWithItems.has(letter)}
                onClick={() => setActiveLetter(letter)}
              >
                {letter}
              </button>
            ))}
          </nav>
          <section className="facet-chip-grid" aria-label={t('facet.availableAria', { title })}>
            {visibleItems.map((item) => (
              <button
                key={`${kind}-${item.normalizedValue}`}
                className={`facet-chip ${selectedSet.has(item.value) ? 'facet-chip-active' : ''}`}
                type="button"
                onClick={() => toggleFacet(item)}
              >
                <span>{item.label}</span>
                <strong>{item.count.toLocaleString('es-ES')}</strong>
              </button>
            ))}
            {!visibleItems.length ? (
              <p className="empty-state">{t('facet.emptyLetter')}</p>
            ) : null}
          </section>
        </>
      ) : null}
    </main>
  );
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function formatLastScrape(t: Translator, stats: CatalogStats | null): string {
  if (!stats?.lastScrape) return t('app.lastScrape.empty');
  const date = stats.lastScrape.finishedAt ?? stats.lastScrape.heartbeatAt ?? stats.lastScrape.startedAt;
  return `${t('app.lastScrape')}: ${formatDate(date)}`;
}
