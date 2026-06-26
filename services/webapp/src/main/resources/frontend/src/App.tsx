import { Globe2, RefreshCw, UserCircle } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { fetchAppDetails, fetchApps } from './api/catalog';
import { AppDetailsDrawer } from './components/AppDetailsDrawer';
import { AppFilters } from './components/AppFilters';
import { AppSearchBar } from './components/AppSearchBar';
import { AppTable } from './components/AppTable';
import { Pagination } from './components/Pagination';
import { t } from './services/i18n';
import type { AppDetails, CatalogApp, FilterKey } from './types/catalog';

const PAGE_SIZE = 12;

export default function App() {
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [filter, setFilter] = useState<FilterKey>('all');
  const [page, setPage] = useState(1);
  const [apps, setApps] = useState<CatalogApp[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<AppDetails | null>(null);
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [loadingApps, setLoadingApps] = useState(false);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
    fetchApps({ query: debouncedQuery, filter, page, pageSize: PAGE_SIZE })
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
  }, [debouncedQuery, filter, page]);

  const filterTotal = useMemo(() => total.toLocaleString('es-ES'), [total]);

  function handleFilterChange(nextFilter: FilterKey) {
    setFilter(nextFilter);
    setPage(1);
  }

  async function selectApp(app: CatalogApp) {
    setSelectedId(app.id);
    setLoadingDetails(true);
    try {
      const details = await fetchAppDetails(app.id);
      setSelected(details);
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
          <span>{t('app.lastScrape')}</span>
          <button type="button" aria-label="Actualizar">
            <RefreshCw size={19} />
          </button>
          <button type="button" aria-label="Idioma">
            <Globe2 size={20} />
            ES
          </button>
          <button type="button" aria-label="Perfil">
            <UserCircle size={25} />
          </button>
        </div>
      </header>
      <main className="workspace">
        <AppFilters active={filter} onChange={handleFilterChange} />
        <section className="catalog-panel">
          <AppSearchBar value={query} onChange={setQuery} />
          {error ? <p className="error-banner">{error}</p> : null}
          {loadingApps ? <p className="loading-label">Cargando aplicaciones...</p> : null}
          <AppTable apps={apps} selectedId={selectedId} onSelect={selectApp} />
          <Pagination page={page} pageSize={PAGE_SIZE} total={total} onPageChange={setPage} />
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
