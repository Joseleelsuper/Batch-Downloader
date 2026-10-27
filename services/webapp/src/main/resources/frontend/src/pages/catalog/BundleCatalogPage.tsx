import { Boxes, ChevronLeft, ChevronRight, Grid2X2, ShieldCheck, SlidersHorizontal, Tags, Users, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import { fetchBundleFacets, fetchBundles } from '../../api/bundles';
import { useAuth } from '../../auth/AuthContext';
import { bundleFiltersToSearch, bundleRequest, parseBundleFilters, type BundleCatalogFilters } from '../../bundleCatalogFilters';
import { toggleOperatingSystem, toggleValue } from '../../catalogFilters';
import { AppSearchBar } from '../../components/AppSearchBar';
import { BundleCard } from '../../components/BundleCard';
import { OperatingSystemIcon, operatingSystemLabel } from '../../components/OperatingSystemIcons';
import { Pagination } from '../../components/Pagination';
import { ResponsivePanel } from '../../components/ResponsivePanel';
import { useCompactLayout } from '../../hooks/useCompactLayout';
import { useSwipePagination } from '../../hooks/useSwipePagination';
import { useLocale, useTranslation } from '../../services/i18n';
import type { BundleFacets, BundleResponse, BundleSortKey } from '../../types/catalog';
import { CatalogPage } from './CatalogPages';

const ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'];
const cache = new Map<string, { response: BundleResponse; time: number }>();
const BASE = '/catalog/bundles';

/** Mantiene los enlaces publicados antes de que existiera el catálogo de bundles. */
export function CatalogEntry() {
  const [params] = useSearchParams();
  return params.has('bundleType')
    ? <Navigate replace to={`${BASE}?${bundleFiltersToSearch(parseBundleFilters(params))}`} />
    : <CatalogPage />;
}

function useBundleFilters() {
  const [params, setParams] = useSearchParams();
  const key = params.toString();
  const filters = useMemo(() => parseBundleFilters(new URLSearchParams(key)), [key]);
  function update(patch: Partial<BundleCatalogFilters>, resetPage = true) {
    setParams(bundleFiltersToSearch({ ...filters, ...patch, page: resetPage ? 1 : patch.page ?? filters.page }));
  }
  return { filters, key, update };
}

export function BundleCatalogPage() {
  const { filters, key, update } = useBundleFilters();
  const { account, status } = useAuth();
  const scope = status === 'checking' ? 'checking' : account?.id ?? 'anonymous';
  const t = useTranslation();
  const locale = useLocale();
  const compact = useCompactLayout();
  const [page, setPage] = useState<{ scope: string; response: BundleResponse } | null>(null);
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const [draft, setDraft] = useState(filters.query);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [filtersVisible, setFiltersVisible] = useState(true);
  const response = page?.scope === scope ? page.response : null;

  useEffect(() => { setDraft(filters.query); }, [filters.query]);
  useEffect(() => {
    if (draft.trim() === filters.query) return;
    const timer = window.setTimeout(() => update({ query: draft }), 250);
    return () => window.clearTimeout(timer);
    // La búsqueda conserva el resto de los filtros vigentes cuando cambia la URL.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, key]);

  useEffect(() => {
    if (scope === 'checking') return;
    const controller = new AbortController();
    const cacheKey = `${scope}:${key}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.time < 15_000) setPage({ scope, response: cached.response });
    setLoading(true);
    setError(false);
    void fetchBundles(bundleRequest(filters), controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      cache.set(cacheKey, { response: result, time: Date.now() });
      if (cache.size > 32) cache.delete(cache.keys().next().value!);
      setPage({ scope, response: result });
      const last = Math.max(1, Math.ceil(result.total / filters.pageSize));
      if (filters.page > last) update({ page: last }, false);
    }).catch(() => { if (!controller.signal.aborted) setError(true); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
    // filters y update proceden de la misma clave URL; el ámbito impide reutilizar votos de otra cuenta.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, scope, refresh]);

  const swipe = useSwipePagination({ enabled: compact && !mobileOpen, page: filters.page,
    pageCount: Math.max(1, Math.ceil((response?.total ?? 0) / filters.pageSize)),
    onPageChange: (next) => update({ page: next }, false) });
  const sortOptions: BundleSortKey[] = ['downloads', 'updated', 'stars', 'name',
    ...(filters.query && filters.searchMode === 'semantic' ? ['relevance' as const] : [])];
  const facetSearch = bundleFiltersToSearch(filters).toString();

  function changedStar() {
    cache.clear();
    setRefresh((value) => value + 1);
  }

  return <main className={`workspace ${filtersVisible ? '' : 'filters-hidden'}`}>
    <ResponsivePanel id="bundle-filter-panel" title={t('catalog.filters')} open={mobileOpen}
      onClose={() => setMobileOpen(false)} desktopClassName="filter-rail-shell" desktopHidden={!filtersVisible}
      footer={<button type="button" className="primary-button" onClick={() => setMobileOpen(false)}>{t('catalog.filters.showResults')}</button>}>
      <aside className="filter-rail">
        <div className="filter-header"><span>{t('catalog.filters')}</span><SlidersHorizontal size={18} /></div>
        <nav aria-label={t('catalog.filters')}>
          {(['all', 'public', 'official'] as const).map((type) => {
            const Icon = type === 'all' ? Grid2X2 : type === 'official' ? ShieldCheck : Boxes;
            return <button type="button" key={type} className={`filter-item ${filters.bundleType === type ? 'filter-item-active' : ''}`}
              aria-pressed={filters.bundleType === type} onClick={() => update({ bundleType: type })}>
              <Icon size={20} aria-hidden="true" /><span>{t(`bundle.catalog.${type}`)}</span>
              <strong>{(response?.counts?.[type] ?? 0).toLocaleString(locale)}</strong>
            </button>;
          })}
        </nav>
        <section className="facet-links-panel">
          {(['tags', 'creators'] as const).map((kind) => {
            const count = kind === 'tags' ? filters.tags.length : filters.creator ? 1 : 0;
            const Icon = kind === 'tags' ? Tags : Users;
            return <div className={`facet-link-row ${count ? 'facet-link-row-clearable' : ''}`} key={kind}>
              <Link className="facet-link-button" to={`${BASE}/${kind}?${facetSearch}`}>
                <Icon size={18} aria-hidden="true" /><span>{t(kind === 'tags' ? 'facet.tags.title' : 'bundle.catalog.creators')}</span><strong>{count}</strong>
              </Link>
              {count ? <button type="button" className="facet-clear-button" aria-label={t(kind === 'tags' ? 'catalog.filters.clearTags' : 'bundle.catalog.clearCreator')}
                onClick={() => update(kind === 'tags' ? { tags: [] } : { creator: undefined })}><X size={16} /></button> : null}
            </div>;
          })}
        </section>
        <section className="platform-filter" aria-label={t('catalog.platforms')}>
          <span>S.O.</span><div>{(['windows', 'linux', 'macos'] as const).map((os) => <button key={os} type="button"
            className={`platform-filter-button ${filters.operatingSystems.includes(os) ? 'platform-filter-button-active' : ''}`}
            aria-pressed={filters.operatingSystems.includes(os)} title={operatingSystemLabel(os)} aria-label={operatingSystemLabel(os)}
            onClick={() => update({ operatingSystems: toggleOperatingSystem(filters.operatingSystems, os) })}>
            <OperatingSystemIcon operatingSystem={os} size={21} />
          </button>)}</div>
        </section>
      </aside>
    </ResponsivePanel>
    <button type="button" className="filter-bookmark" aria-controls="bundle-filter-panel" aria-expanded={filtersVisible}
      aria-label={t(filtersVisible ? 'catalog.filters.close' : 'catalog.filters.open')}
      onClick={() => setFiltersVisible((value) => !value)}>{filtersVisible ? <ChevronLeft size={18} /> : <ChevronRight size={18} />}</button>
    <section className="catalog-panel">
      {compact ? <div className="mobile-catalog-toolbar"><div><h1>{t('bundle.catalog.title')}</h1>
        <p>{t(`bundle.catalog.${filters.bundleType}`)}{response ? ` · ${t('bundle.catalog.results', { count: response.total.toLocaleString(locale) })}` : ''}</p></div>
        <button type="button" className="secondary-button mobile-filter-button" aria-controls="bundle-filter-panel" aria-expanded={mobileOpen}
          onClick={() => setMobileOpen(true)}><SlidersHorizontal size={18} />{t('catalog.filters')}</button></div>
        : <h1 className="sr-only">{t('bundle.catalog.title')}</h1>}
      <AppSearchBar value={draft} sort={filters.sort} searchMode={filters.searchMode} onChange={setDraft}
        onSortChange={(sort) => update({ sort })} onSearchModeChange={(searchMode) => update({ searchMode,
          sort: searchMode === 'lexical' && filters.sort === 'relevance' ? 'downloads' : filters.sort })}
        sortOptions={sortOptions} labelPrefix="bundle.sort" placeholder={t('bundle.catalog.search')} showSortDescriptions={false} />
      {response?.degradedReason ? <p className="semantic-notice" role="status">{t('bundle.catalog.semanticFallback')}</p> : null}
      {error ? <p className="error-banner" role="alert">{t('bundle.loadError')}</p> : null}
      {filters.sort === 'name' && response ? <nav className="catalog-alphabet" aria-label={t('catalog.alphabet.aria')}>
        {ALPHABET.map((letter) => { const entry = response.alphabet?.find((value) => value.letter === letter);
          return <button type="button" key={letter} disabled={!entry} onClick={() => { if (entry) update({ page: entry.page }, false); }}>{letter}</button>;
        })}
      </nav> : null}
      <div className="catalog-apps-swipe" {...swipe} aria-busy={loading}>
        <div className="bundle-grid bundle-catalog-grid">
          {response?.data.map((bundle) => <BundleCard bundle={bundle} key={`${scope}:${bundle.id}`} onStarChange={changedStar} />)}
        </div>
        {!response && loading ? <p className="loading-label">{t('common.loading')}</p> : null}
        {response && !response.data.length && !error ? <p className="empty-state">{t('home.emptyBundles')}</p> : null}
      </div>
      {response ? <Pagination page={filters.page} pageSize={filters.pageSize} total={response.total}
        pageHref={(next) => `${BASE}?${bundleFiltersToSearch({ ...filters, page: next })}`}
        onPageChange={(next) => update({ page: next }, false)} onPageSizeChange={(pageSize) => update({ pageSize })} /> : null}
    </section>
  </main>;
}

export function BundleFacetDirectoryPage({ kind }: { kind: 'tags' | 'creators' }) {
  const { filters, key, update } = useBundleFilters();
  const t = useTranslation();
  const [facets, setFacets] = useState<BundleFacets>({ tags: [], creators: [] });
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [letter, setLetter] = useState('A');
  useEffect(() => {
    const controller = new AbortController();
    setError(false); setLoading(true);
    void fetchBundleFacets(bundleRequest(filters), controller.signal).then((result) => {
      if (!controller.signal.aborted) setFacets(result);
    }).catch(() => { if (!controller.signal.aborted) setError(true); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  const title = t(kind === 'tags' ? 'facet.tags.title' : 'bundle.catalog.creators');
  const items = facets[kind];
  const activeLetter = items.some((item) => item.letter === letter) ? letter
    : ALPHABET.find((candidate) => items.some((item) => item.letter === candidate)) ?? 'A';
  return <main className="facet-page content-page">
    <section className="facet-header"><div><h1>{title}</h1></div>
      <Link className="secondary-button facet-back-link" to={`${BASE}?${bundleFiltersToSearch(filters)}`}>{t('facet.backToCatalog')}</Link>
    </section>
    {error ? <p className="error-banner" role="alert">{t('bundle.loadError')}</p> : null}
    {loading ? <p className="loading-label">{t('facet.loading')}</p> : null}
    <nav className="facet-letter-nav" aria-label={t('facet.lettersAria', { title })}>
      {ALPHABET.map((value) => <button type="button" key={value} disabled={!items.some((item) => item.letter === value)}
        className={activeLetter === value ? 'facet-letter-active' : ''} aria-pressed={activeLetter === value} onClick={() => setLetter(value)}>{value}</button>)}
    </nav>
    <section className="facet-list-panel"><div className="facet-chip-grid">
      {items.filter((item) => item.letter === activeLetter).map((item) => {
        const selected = kind === 'tags' ? filters.tags.includes(item.value) : filters.creator === item.value;
        return <button type="button" key={item.value} className={`facet-chip ${selected ? 'facet-chip-active' : ''}`} aria-pressed={selected}
          onClick={() => update(kind === 'tags' ? { tags: toggleValue(filters.tags, item.value) } : { creator: selected ? undefined : item.value })}>
          <span>{item.label}</span><strong>{item.count}</strong>
        </button>;
      })}
    </div>{!loading && !error && !items.some((item) => item.letter === activeLetter) ? <p className="empty-state">{t('facet.emptyLetter')}</p> : null}</section>
  </main>;
}
