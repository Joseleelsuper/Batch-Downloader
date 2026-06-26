import { Globe2, RefreshCw, UserCircle } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { fetchAppDetails, fetchApps, fetchCatalogStats } from './api/catalog';
import { AppDetailsDrawer } from './components/AppDetailsDrawer';
import { AppFilters } from './components/AppFilters';
import { AppSearchBar } from './components/AppSearchBar';
import { AppTable } from './components/AppTable';
import { Pagination } from './components/Pagination';
import { t } from './services/i18n';
import type { AppDetails, CatalogApp, CatalogStats, FilterKey, SortKey } from './types/catalog';

const DEFAULT_COUNTS: Record<FilterKey, number> = {
  all: 0,
  available: 0,
  review: 0,
  missing: 0,
};

export default function App() {
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [filter, setFilter] = useState<FilterKey>('all');
  const [sort, setSort] = useState<SortKey>('updated');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(12);
  const [apps, setApps] = useState<CatalogApp[]>([]);
  const [total, setTotal] = useState(0);
  const [stats, setStats] = useState<CatalogStats | null>(null);
  const [selected, setSelected] = useState<AppDetails | null>(null);
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [loadingApps, setLoadingApps] = useState(false);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [filtersVisible, setFiltersVisible] = useState(true);
  const [languageOpen, setLanguageOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [language, setLanguage] = useState(() => localStorage.getItem('batch.language') ?? 'es');

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebouncedQuery(query);
      setPage(1);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    let cancelled = false;
    setLoadingApps(true);
    setError(null);
    fetchApps({ query: debouncedQuery, filter, sort, page, pageSize })
      .then((response) => {
        if (cancelled) return;
        setApps(response.data);
        setTotal(response.total);
        if (!selectedId && response.data[0]) {
          void selectApp(response.data[0]);
        }
      })
      .catch(() => {
        if (!cancelled) setError('No se pudo cargar el catalogo.');
      })
      .finally(() => {
        if (!cancelled) setLoadingApps(false);
      });
    return () => {
      cancelled = true;
    };
    // selectedId is intentionally excluded: changing selection should not reload the catalog.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedQuery, filter, sort, page, pageSize, refreshToken]);

  useEffect(() => {
    let cancelled = false;
    fetchCatalogStats()
      .then((response) => {
        if (!cancelled) setStats(response);
      })
      .catch(() => {
        if (!cancelled) setStats(null);
      });
    return () => {
      cancelled = true;
    };
  }, [refreshToken]);

  const filterTotal = useMemo(() => total.toLocaleString('es-ES'), [total]);
  const filterCounts = stats?.filters ?? DEFAULT_COUNTS;
  const lastScrapeLabel = useMemo(() => formatLastScrape(stats), [stats]);

  function handleFilterChange(nextFilter: FilterKey) {
    setFilter(nextFilter);
    setPage(1);
  }

  function handlePageSizeChange(nextPageSize: number) {
    setPageSize(nextPageSize);
    setPage(1);
  }

  function selectLanguage(nextLanguage: string) {
    setLanguage(nextLanguage);
    localStorage.setItem('batch.language', nextLanguage);
    setLanguageOpen(false);
  }

  async function selectApp(app: CatalogApp) {
    setSelectedId(app.id);
    setLoadingDetails(true);
    try {
      const details = await fetchAppDetails(app.id);
      setSelected(details);
    } catch {
      setSelected(null);
      setError('No se pudo cargar el detalle de la aplicacion.');
    } finally {
      setLoadingDetails(false);
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            <span />
          </span>
          <h1>{t('app.title')}</h1>
        </div>
        <div className="topbar-actions">
          <span>{lastScrapeLabel}</span>
          <button
            type="button"
            aria-label={t('app.refresh')}
            title={t('app.refresh')}
            disabled={loadingApps}
            onClick={() => setRefreshToken((value) => value + 1)}
          >
            <RefreshCw size={19} />
          </button>
          <div className="menu-wrapper">
            <button
              type="button"
              aria-label={t('language.selector')}
              aria-expanded={languageOpen}
              onClick={() => {
                setLanguageOpen((value) => !value);
                setProfileOpen(false);
              }}
            >
              <Globe2 size={20} />
              {language.toUpperCase()}
            </button>
            {languageOpen ? (
              <div className="topbar-menu" role="menu">
                <button type="button" role="menuitem" onClick={() => selectLanguage('es')}>
                  <span>{t('language.spanish')}</span>
                  <strong>{t('language.active')}</strong>
                </button>
              </div>
            ) : null}
          </div>
          <div className="menu-wrapper">
            <button
              type="button"
              aria-label={t('profile.menu')}
              aria-expanded={profileOpen}
              onClick={() => {
                setProfileOpen((value) => !value);
                setLanguageOpen(false);
              }}
            >
              <UserCircle size={25} />
            </button>
            {profileOpen ? (
              <div className="topbar-menu profile-menu" role="menu">
                <strong>{t('profile.localMode')}</strong>
                <span>
                  {t('profile.catalogSize')}: {filterCounts.all.toLocaleString('es-ES')}
                </span>
                <span>{t('profile.authPending')}</span>
              </div>
            ) : null}
          </div>
        </div>
      </header>
      <main className={`workspace ${filtersVisible ? '' : 'filters-hidden'}`}>
        <AppFilters active={filter} counts={filterCounts} onChange={handleFilterChange} />
        <section className="catalog-panel">
          <AppSearchBar
            value={query}
            sort={sort}
            onChange={setQuery}
            onSortChange={(nextSort) => {
              setSort(nextSort);
              setPage(1);
            }}
            onToggleFilters={() => setFiltersVisible((value) => !value)}
          />
          {error ? <p className="error-banner">{error}</p> : null}
          {loadingApps ? <p className="loading-label">Cargando aplicaciones...</p> : null}
          <AppTable apps={apps} selectedId={selectedId} onSelect={selectApp} />
          <Pagination
            page={page}
            pageSize={pageSize}
            total={total}
            onPageChange={setPage}
            onPageSizeChange={handlePageSizeChange}
          />
          <span className="sr-only">{filterTotal}</span>
        </section>
        <AppDetailsDrawer
          app={selected}
          loading={loadingDetails}
          onClose={() => {
            setSelected(null);
            setSelectedId(undefined);
          }}
        />
      </main>
    </div>
  );
}

function formatLastScrape(stats: CatalogStats | null): string {
  if (!stats?.lastScrape) return t('app.lastScrape.empty');
  const date = stats.lastScrape.finishedAt ?? stats.lastScrape.heartbeatAt ?? stats.lastScrape.startedAt;
  const formatted = new Intl.DateTimeFormat('es-ES', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(date));
  return `${t('app.lastScrape')}: ${formatted}`;
}
