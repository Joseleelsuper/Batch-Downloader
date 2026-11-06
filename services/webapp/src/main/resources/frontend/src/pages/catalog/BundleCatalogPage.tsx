import { AsyncContent } from '../../components/AsyncContent';
import { Boxes, ChevronLeft, ChevronRight, Grid2X2, ShieldCheck, SlidersHorizontal, Tags, Users, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { bundleFiltersToSearch, bundleRequest } from '../../bundleCatalogFilters';
import { toggleOperatingSystem } from '../../catalogFilters';
import { AppSearchBar } from '../../components/AppSearchBar';
import { BundleCard } from '../../components/BundleCard';
import { OperatingSystemIcon, operatingSystemLabel } from '../../components/OperatingSystemIcons';
import { Pagination } from '../../components/Pagination';
import { ResponsivePanel } from '../../components/ResponsivePanel';
import { useCompactLayout } from '../../hooks/useCompactLayout';
import { useBundleResults } from '../../hooks/useBundleResults';
import { useSwipePagination } from '../../hooks/useSwipePagination';
import { useLocale, useTranslation } from '../../services/i18n';
import type { BundleSortKey } from '../../types/catalog';

import { useBundleFilters } from './useBundleFilters';

const ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'];
const BASE = '/catalog/bundles';

export function BundleCatalogPage() {
  const { filters, update } = useBundleFilters();
  const t = useTranslation();
  const locale = useLocale();
  const compact = useCompactLayout();
  const { response, isCurrent, error, loading, refresh: changedStar, scope } = useBundleResults(
    bundleRequest(filters), true,
  );
  const [draft, setDraft] = useState(filters.query);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [filtersVisible, setFiltersVisible] = useState(true);

  useEffect(() => { setDraft(filters.query); }, [filters.query]);
  useEffect(() => {
    if (draft.trim() === filters.query) return;
    const timer = window.setTimeout(() => update({ query: draft }), 250);
    return () => window.clearTimeout(timer);
  }, [draft, filters.query, update]);

  useEffect(() => {
    if (!response || !isCurrent) return;
    const last = Math.max(1, Math.ceil(response.total / filters.pageSize));
    if (filters.page > last) update({ page: last }, false);
  }, [response, isCurrent, filters.page, filters.pageSize, update]);

  const swipe = useSwipePagination({ enabled: compact && !mobileOpen, page: filters.page,
    pageCount: Math.max(1, Math.ceil((response?.total ?? 0) / filters.pageSize)),
    onPageChange: (next) => update({ page: next }, false) });
  const sortOptions: BundleSortKey[] = ['downloads', 'updated', 'stars', 'name',
    ...(filters.query && filters.searchMode === 'semantic' ? ['relevance' as const] : [])];
  const facetSearch = bundleFiltersToSearch(filters).toString();

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
              <strong>{response ? (response.counts?.[type] ?? 0).toLocaleString(locale) : '—'}</strong>
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
        onSortChange={(sort) => update({ sort, direction: undefined })} onSearchModeChange={(searchMode) => update({ searchMode,
          sort: searchMode === 'lexical' && filters.sort === 'relevance' ? 'downloads' : filters.sort })}
        sortOptions={sortOptions} labelPrefix="bundle.sort" placeholder={t('bundle.catalog.search')} />
      {response?.degradedReason ? <p className="semantic-notice" role="status">{t('bundle.catalog.semanticFallback')}</p> : null}
      {filters.sort === 'name' && response ? <nav className="catalog-alphabet" aria-label={t('catalog.alphabet.aria')}>
        {ALPHABET.map((letter) => { const entry = response.alphabet?.find((value) => value.letter === letter);
          return <button type="button" key={letter} disabled={!entry} onClick={() => { if (entry) update({ page: entry.page }, false); }}>{letter}</button>;
        })}
      </nav> : null}
      <div className="catalog-apps-swipe" {...swipe} aria-busy={loading}>
        <AsyncContent loading={loading} error={error} hasData={response !== null} onRetry={changedStar} errorMessage={t('bundle.loadError')}>
        <div className="bundle-grid bundle-catalog-grid">
          {response?.data.map((bundle) => <BundleCard bundle={bundle} key={`${scope}:${bundle.id}`} onStarChange={changedStar} />)}
        </div>
        {response && !response.data.length && !error ? <p className="empty-state">{t('home.emptyBundles')}</p> : null}
        </AsyncContent>
      </div>
      {response ? <Pagination page={filters.page} pageSize={filters.pageSize} total={response.total}
        pageHref={(next) => `${BASE}?${bundleFiltersToSearch({ ...filters, page: next })}`}
        onPageChange={(next) => update({ page: next }, false)} onPageSizeChange={(pageSize) => update({ pageSize })} /> : null}
    </section>
  </main>;
}
