import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, Download, SlidersHorizontal } from 'lucide-react';
import {
  useNavigate,
  Link,
  useParams,
  useSearchParams
} from 'react-router-dom';
import {
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
import { ResponsivePanel } from '../../components/ResponsivePanel';
import { useCompactLayout } from '../../hooks/useCompactLayout';
import { useDownloadJob } from '../../hooks/useDownloadJob';
import { useSwipePagination } from '../../hooks/useSwipePagination';
import { chooseDownloadDestination, isDestinationCancelled } from '../../downloads/delivery';
import { useLocale, useTranslation, type Translator } from '../../services/i18n';
import type {
  AppDetails,
  CatalogApp, CatalogResponse, FilterKey,
  SortKey
} from '../../types/catalog';

import { appRequest, cachePage, cachedPage, enqueuePrefetch, pageCache, pendingPages, prefetchCatalogPage } from './catalogCache';
export { clearCatalogPageCache } from './catalogCache';

const FACET_ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')];
const PREFETCH_SORTS: SortKey[] = ['downloads', 'updated', 'name', 'relevance'];
const PREFETCH_FILTERS: FilterKey[] = ['all', 'available', 'review', 'missing'];

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
  const locale = useLocale();
  const compact = useCompactLayout();
  const [mobileFiltersOpen, setMobileFiltersOpen] = useState(false);
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
  const filterKey = catalogFiltersToSearchParams(filters).toString();
  const paginationParams = catalogFiltersToSearchParams({ ...filters, page: 1 });
  paginationParams.delete('searchMode');
  const crawlablePagination = paginationParams.toString() === '';
  const [query, setQuery] = useState(filters.query);
  const [activePage, setActivePage] = useState<{ key: string; response: CatalogResponse } | null>(null);
  const displayedPage = cachedPage(filterKey)
    ?? (activePage?.key === filterKey ? activePage.response : null);
  const apps = displayedPage?.data ?? [];
  const total = displayedPage?.total ?? 0;
  const alphabet = displayedPage?.alphabet ?? [];
  const statsResource = useAsyncResource((signal) => fetchCatalogStats(signal));
  const stats = statsResource.data;
  const [selected, setSelected] = useState<AppDetails | null>(null);
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [detailsError, setDetailsError] = useState<string | null>(null);
  const [catalogRevision, setCatalogRevision] = useState(0);
  const [detailsRevision, setDetailsRevision] = useState(0);
  const loadingApps = !displayedPage && !catalogError;
  const searchNotice = searchNoticeFor(t, displayedPage?.degradedReason);
  const [filtersVisible, setFiltersVisible] = useState(() => localStorage.getItem('catalog.filters.open') !== 'false');
  const [selectedDownloadIds, setSelectedDownloadIds] = useState<Set<string>>(new Set());
  const [selectedDownloadApps, setSelectedDownloadApps] = useState<CatalogApp[]>([]);
  const [validatingSelection, setValidatingSelection] = useState(false);
  const downloadJob = useDownloadJob();
  const selectedDownloadIdsRef = useRef<Set<string>>(new Set());
  const lastLoadedPage = useRef<{ searchKey: string; apps: CatalogApp[] } | null>(null);
  const prefetchController = useRef(new AbortController());
  const previousQuery = useRef(filters.query);

  const prefetchPage = useCallback((candidate: CatalogFilterState) => {
    prefetchCatalogPage(candidate, prefetchController.current.signal);
  }, []);

  useEffect(() => {
    if (previousQuery.current === filters.query) return;
    previousQuery.current = filters.query;
    prefetchController.current.abort();
    prefetchController.current = new AbortController();
    pendingPages.clear();
  }, [filters.query]);

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

  const swipePagination = useSwipePagination({
    enabled: compact && !!displayedPage && !mobileFiltersOpen,
    page: filters.page,
    pageCount: Math.max(1, Math.ceil(total / filters.pageSize)),
    onPageChange: (page) => updateFilters({ page }, false),
  });

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
    if (!catalogStatusCanonical) return undefined;
    setError(null);
    setCatalogError(null);
    const cached = cachedPage(filterKey);
    if (cached) {
      // La página visible debe sobrevivir a la caducidad o expulsión de la caché.
      setActivePage({ key: filterKey, response: cached });
      lastLoadedPage.current = { searchKey: filterKey, apps: cached.data };
      return undefined;
    }
    let cancelled = false;
    const controller = new AbortController();
    const lastPage = lastLoadedPage.current;
    const previousPage = lastPage?.searchKey === filterKey
      ? lastPage.apps
      : null;
    async function prefetchFacetPages() {
      try {
        const facets = await enqueuePrefetch(() => cancelled
          ? Promise.resolve(null)
          : fetchCatalogFacets(appRequest(filters)));
        if (cancelled || !facets) return;
        facets.tags.slice(0, 2).forEach((tag) => prefetchPage(nextFilters(filters, { tags: toggleValue(filters.tags, tag.value) })));
        facets.publishers.slice(0, 2).forEach((publisher) => prefetchPage(nextFilters(filters, { publisher: publisher.value })));
      } catch {
        return; // La precarga es opcional; el catálogo principal ya está disponible.
      }
    }
    const pending = pendingPages.get(filterKey);
    (pending ? pending.catch(() => fetchApps(appRequest(filters), controller.signal))
      : fetchApps(appRequest(filters), controller.signal))
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
        cachePage(filterKey, response);
        setActivePage({ key: filterKey, response });
        lastLoadedPage.current = { searchKey: filterKey, apps: response.data };
        if (response.total > 0) {
          // ponytail: precarga opciones cercanas; ampliar según fallos de caché medidos.
          const candidates: CatalogFilterState[] = [
            ...(filters.query ? [{ ...filters, searchMode: filters.searchMode === 'semantic' ? 'lexical' as const : 'semantic' as const, page: 1 }] : []),
            ...PREFETCH_SORTS.filter((sort) => sort !== filters.sort && (sort !== 'relevance' || filters.query)).map((sort) => ({ ...filters, sort, page: 1 })),
            ...PREFETCH_FILTERS.filter((filter) => filter !== filters.filter).map((filter) => ({ ...filters, filter, page: 1 })),
            ...(filters.page * filters.pageSize < response.total ? [{ ...filters, page: filters.page + 1 }] : []),
          ];
          candidates.forEach(prefetchPage);
          void prefetchFacetPages();
        }
        if (refreshInspection?.missingIds.length) {
          const validation = await validateCatalogSelection(
            refreshInspection.missingIds,
            (id) => fetchAppDetails(id, controller.signal),
          );
          if (!cancelled) removeDownloadSelections(validation.invalidIds);
        }
      })
      .catch((requestError: unknown) => {
        if (!cancelled && !isAbortError(requestError)) setCatalogError(t('catalog.error.load'));
      })
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [catalogStatusCanonical, catalogRevision, filterKey, filters, prefetchPage, removeDownloadSelections, t]);

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
    setDetailsError(null);
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
        setDetailsError(t('catalog.error.detail'));
      })
      .finally(() => {
        if (!cancelled) setLoadingDetails(false);
      });
    return () => {
      cancelled = true;
    };
  }, [appId, detailsRevision, t]);

  function toggleAppDetails(app: CatalogApp) {
    if (selectedId === app.id) {
      setSelectedId(undefined);
      setLoadingDetails(false);
      void navigate({ pathname: '/catalog', search: searchKey });
      return;
    }
    setSelectedId(app.id);
    void navigate({ pathname: `/catalog/app/${app.id}`, search: searchKey });
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
      const destination = chooseDownloadDestination();
      await destination;
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
      }, t('download.job.selectionLabel', { count: validIds.length }), destination);
    } catch (cause) {
      if (!isDestinationCancelled(cause)) setError(t('catalog.error.zip'));
    } finally {
      setValidatingSelection(false);
    }
  }

  return (
    <main className={`workspace ${filtersVisible ? '' : 'filters-hidden'}`}>
      <ResponsivePanel id="catalog-filter-panel" title={t('catalog.filters')} open={mobileFiltersOpen}
        onClose={() => setMobileFiltersOpen(false)} desktopClassName="filter-rail-shell" desktopHidden={!filtersVisible}
        footer={<button type="button" className="primary-button" onClick={() => setMobileFiltersOpen(false)}>
          {t('catalog.filters.showResults')}
        </button>}>
        <AppFilters
          active={filters.filter}
          counts={stats?.filters}
          countsLoading={statsResource.loading}
          countsError={Boolean(statsResource.error)}
          onRetryCounts={statsResource.refresh}
          selectedTagCount={filters.tags.length}
          selectedPublisherCount={filters.publisher ? 1 : 0}
          catalogSearch={searchKey}
          selectedApps={selectedDownloadApps}
          downloading={downloadJob.starting || validatingSelection}
          operatingSystems={filters.operatingSystems}
          architecture={filters.architecture}
          onArchitectureChange={(architecture) => updateFilters({ architecture })}
          minimumTags={filters.minimumTags}
          onMinimumTagsChange={(minimumTags) => updateFilters({ minimumTags })}
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
      </ResponsivePanel>
      <button
        className="filter-bookmark"
        type="button"
        aria-controls="catalog-filter-panel"
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
        {compact ? <div className="mobile-catalog-toolbar">
          <div>
            <h1>{t('nav.catalog')}</h1>
            <p>{t(`catalog.filter.${filters.filter}`)}{displayedPage ? ` · ${t('catalog.results', { count: total.toLocaleString(locale) })}` : ''}</p>
          </div>
          <button type="button" className="secondary-button mobile-filter-button" aria-controls="catalog-filter-panel"
            aria-expanded={mobileFiltersOpen} onClick={() => setMobileFiltersOpen(true)}>
            <SlidersHorizontal size={18} aria-hidden="true" />{t('catalog.filters')}
          </button>
        </div> : <h1 className="sr-only">{selected?.name ?? t('nav.catalog')}</h1>}
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
        {filters.searchMode === 'semantic' && filters.query.trim().length >= 3 ? <p>
          <Link className="secondary-button" to={`/bundle-proposal?${catalogFiltersToSearchParams(filters)}`}>{t('bundle.draft.propose')}</Link>
        </p> : null}
        {error ? <p className="error-banner" role="alert">{error}</p> : null}
        {catalogError ? <AsyncContent loading={false} error={catalogError} hasData={false} errorMessage={catalogError}
          onRetry={() => { pageCache.delete(filterKey); setCatalogError(null); setCatalogRevision((value) => value + 1); }}>{null}</AsyncContent> : null}
        {detailsError ? <AsyncContent loading={false} error={detailsError} hasData={false} errorMessage={detailsError}
          onRetry={() => setDetailsRevision((value) => value + 1)}>{null}</AsyncContent> : null}
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
        <div className="catalog-apps-swipe" {...swipePagination}>
          {loadingApps ? <span className="sr-only" role="status">{t('common.loading')}</span> : null}
          <AppTable
            apps={selected && selected.id === selectedId && !apps.some((app) => app.id === selected.id)
              ? [selected, ...apps] : apps}
            loading={loadingApps}
            showLoadingLabel={false}
            showEmptyState={!error && !catalogError}
            selectedId={selectedId}
            selectedIds={selectedDownloadIds}
            selectedCount={selectedDownloadIds.size}
            details={selected}
            loadingDetails={loadingDetails}
            literalQuery={filters.searchMode === 'lexical' ? filters.query : undefined}
            onToggleDetails={toggleAppDetails}
            onToggleSelection={toggleDownloadSelection}
          />
        </div>
        {displayedPage ? <Pagination
          page={filters.page}
          pageSize={filters.pageSize}
          total={total}
          pageHref={crawlablePagination
            ? (nextPage) => nextPage === 1 ? '/catalog' : `/catalog?page=${nextPage}`
            : undefined}
          onPageChange={(nextPage) => updateFilters({ page: nextPage }, false)}
          onPageSizeChange={(nextPageSize) => {
            updateFilters({ pageSize: nextPageSize });
          }}
        /> : null}
        {compact && selectedDownloadIds.size > 0 ? <div className="mobile-selection-bar">
          <button type="button" className="mobile-selection-summary" onClick={() => setMobileFiltersOpen(true)}
            aria-controls="catalog-filter-panel" aria-expanded={mobileFiltersOpen}>
            <strong>{selectedDownloadIds.size}<span>/100</span></strong>
            <span>{t('catalog.selection.manage')}</span>
          </button>
          <button type="button" className="primary-button" disabled={downloadJob.starting || validatingSelection}
            onClick={() => void downloadSelection()}>
            <Download size={18} aria-hidden="true" />
            {downloadJob.starting || validatingSelection ? t('catalog.selection.downloading') : t('catalog.selection.downloadZip')}
          </button>
        </div> : null}
      </section>
    </main>
  );
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
