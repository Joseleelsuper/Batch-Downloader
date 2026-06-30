import {
  Boxes,
  ClipboardList,
  Globe2,
  Home,
  ListFilter,
  LogOut,
  PackagePlus,
  Play,
  RefreshCw,
  Shield,
  Square,
  UserCircle,
} from 'lucide-react';
import { FormEvent, useEffect, useState } from 'react';
import { Link, NavLink, Navigate, Outlet, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import {
  createAdminApp,
  createAdminBundle,
  fetchAdminApps,
  fetchAdminAudit,
  fetchAdminCurrentRun,
  fetchAdminLogs,
  fetchAdminRequests,
  fetchAdminRuns,
  fetchAppDetails,
  fetchApps,
  fetchBundle,
  fetchBundles,
  fetchCatalogStats,
  generateAdminDescription,
  login,
  logout,
  me,
  patchAdminApp,
  sendScraperCommand,
} from './api/catalog';
import { AppDetailsDrawer } from './components/AppDetailsDrawer';
import { AppFilters } from './components/AppFilters';
import { AppSearchBar } from './components/AppSearchBar';
import { AppStatusBadge } from './components/AppStatusBadge';
import { AppTable } from './components/AppTable';
import { DownloadButton } from './components/DownloadButton';
import { Pagination } from './components/Pagination';
import { t } from './services/i18n';
import type {
  AppDetails,
  AuditItem,
  AuthUser,
  BundleDetails,
  BundleSummary,
  CatalogApp,
  CatalogStats,
  FilterKey,
  ResolverLogItem,
  ScraperRunSummary,
  SoftwareRequestItem,
  SortKey,
} from './types/catalog';

const DEFAULT_COUNTS: Record<FilterKey, number> = {
  all: 0,
  available: 0,
  review: 0,
  missing: 0,
};

export default function App() {
  const [auth, setAuth] = useState<AuthUser | null>(null);
  const [checkingAuth, setCheckingAuth] = useState(true);

  useEffect(() => {
    me()
      .then(setAuth)
      .catch(() => setAuth(null))
      .finally(() => setCheckingAuth(false));
  }, []);

  return (
    <Routes>
      <Route element={<PublicLayout auth={auth} onLogout={() => handleLogout(setAuth)} />}>
        <Route index element={<HomePage />} />
        <Route path="catalog" element={<CatalogPage />} />
        <Route path="bundles/:slug" element={<BundleDetailPage />} />
        <Route path="login" element={<LoginPage onLogin={setAuth} />} />
      </Route>
      <Route
        path="admin"
        element={
          <RequireAdmin auth={auth} checking={checkingAuth}>
            <AdminLayout onLogout={() => handleLogout(setAuth)} />
          </RequireAdmin>
        }
      >
        <Route index element={<AdminDashboard />} />
        <Route path="apps" element={<AdminAppsPage />} />
        <Route path="bundles" element={<AdminBundlesPage />} />
        <Route path="scraper" element={<AdminScraperPage />} />
        <Route path="requests" element={<AdminRequestsPage />} />
        <Route path="audit" element={<AdminAuditPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function PublicLayout({ auth, onLogout }: { auth: AuthUser | null; onLogout: () => void }) {
  return (
    <div className="site-shell">
      <Topbar auth={auth} onLogout={onLogout} />
      <Outlet />
      <Footer />
    </div>
  );
}

function Topbar({ auth, onLogout }: { auth: AuthUser | null; onLogout: () => void }) {
  return (
    <header className="topbar">
      <Link className="brand" to="/">
        <img className="brand-icon" src="/assets/icon.ico" alt="" aria-hidden="true" />
        <h1>{t('app.title')}</h1>
      </Link>
      <nav className="main-nav" aria-label="Navegacion principal">
        <NavLink to="/">Inicio</NavLink>
        <NavLink to="/catalog">Catalogo</NavLink>
      </nav>
      <div className="topbar-actions">
        <button type="button" aria-label={t('language.selector')} title={t('language.selector')}>
          <Globe2 size={20} />
          ES
        </button>
        {auth ? (
          <>
            <NavLink className="admin-link" to="/admin">
              <Shield size={18} />
              Admin
            </NavLink>
            <button type="button" onClick={onLogout}>
              <LogOut size={18} />
              Salir
            </button>
          </>
        ) : (
          <NavLink className="admin-link" to="/login">
            <UserCircle size={22} />
            Entrar
          </NavLink>
        )}
      </div>
    </header>
  );
}

function HomePage() {
  const [officialBundles, setOfficialBundles] = useState<BundleSummary[]>([]);
  const [officialTotal, setOfficialTotal] = useState(0);
  const [communityBundles, setCommunityBundles] = useState<BundleSummary[]>([]);
  const [communityTotal, setCommunityTotal] = useState(0);
  const [apps, setApps] = useState<CatalogApp[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      fetchBundles({ type: 'official', pageSize: 3 }),
      fetchBundles({ type: 'community', pageSize: 3 }),
      fetchApps({ query: '', filter: 'available', sort: 'updated', page: 1, pageSize: 6 }),
    ])
      .then(([official, community, catalog]) => {
        if (cancelled) return;
        setOfficialBundles(official.data);
        setOfficialTotal(official.total);
        setCommunityBundles(community.data);
        setCommunityTotal(community.total);
        setApps(catalog.data);
      })
      .catch(() => {
        if (!cancelled) setError('No se pudo cargar la pagina principal.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <main className="home-page">
      <section className="home-hero">
        <div>
          <h2>Bundles y catalogo de instaladores verificados</h2>
          <p>
            Revisa paquetes oficiales, explora aplicaciones disponibles y comprueba el estado del
            scraper desde el panel de administracion.
          </p>
        </div>
        <Link className="primary-link" to="/catalog">
          Abrir catalogo
        </Link>
      </section>
      {error ? <p className="error-banner">{error}</p> : null}
      <BundleSection
        title="Bundles oficiales"
        bundles={officialBundles}
        total={officialTotal}
        type="official"
      />
      <BundleSection
        title="Bundles comunitarios"
        bundles={communityBundles}
        total={communityTotal}
        type="community"
      />
      <section className="home-section">
        <div className="section-heading">
          <h2>Aplicaciones recientes</h2>
          {apps.length > 5 ? <Link to="/catalog">Ver todo</Link> : null}
        </div>
        <div className="app-compact-grid">
          {apps.map((app) => (
            <AppCompactCard app={app} key={app.id} />
          ))}
        </div>
      </section>
    </main>
  );
}

function BundleSection({
  title,
  bundles,
  total,
  type,
}: {
  title: string;
  bundles: BundleSummary[];
  total: number;
  type: 'official' | 'community';
}) {
  return (
    <section className="home-section">
      <div className="section-heading">
        <h2>{title}</h2>
        {total > bundles.length ? <Link to={`/catalog?bundleType=${type}`}>Ver todo</Link> : null}
      </div>
      <div className="bundle-grid">
        {bundles.length ? (
          bundles.map((bundle) => <BundleCard bundle={bundle} key={bundle.id} />)
        ) : (
          <p className="empty-state">Todavia no hay bundles en esta seccion.</p>
        )}
      </div>
    </section>
  );
}

function BundleCard({ bundle }: { bundle: BundleSummary }) {
  return (
    <Link className="bundle-card" to={`/bundles/${bundle.slug}`}>
      <div className="bundle-card-header">
        <span className="bundle-icon">
          <Boxes size={22} />
        </span>
        <div>
          <h3>{bundle.name}</h3>
          <small>{bundle.appCount} apps</small>
        </div>
      </div>
      <p>{bundle.description || 'Bundle preparado para descarga en lote.'}</p>
      <div className="mini-apps">
        {bundle.previewApps.slice(0, 5).map((app) => (
          <AppMiniIcon app={app} key={app.id} />
        ))}
        {bundle.appCount > 5 ? <span className="mini-more">+{bundle.appCount - 5}</span> : null}
      </div>
    </Link>
  );
}

function AppCompactCard({ app }: { app: CatalogApp }) {
  return (
    <Link className="app-compact-card" to="/catalog">
      <AppMiniIcon app={app} />
      <div>
        <strong>{app.name}</strong>
        <span>{app.tags.slice(0, 3).join(' · ') || app.publisher || '-'}</span>
      </div>
    </Link>
  );
}

function AppMiniIcon({ app }: { app: CatalogApp }) {
  if (app.iconUrl) return <img className="mini-icon" src={app.iconUrl} alt="" loading="lazy" />;
  return <span className="mini-icon mini-icon-fallback">{app.name.slice(0, 1).toUpperCase()}</span>;
}

function CatalogPage() {
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
        if (!selectedId && response.data[0]) void selectApp(response.data[0]);
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
    // selectedId intentionally stays out: selecting a row should not refetch.
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

  async function selectApp(app: CatalogApp) {
    setSelectedId(app.id);
    setLoadingDetails(true);
    try {
      setSelected(await fetchAppDetails(app.id));
    } catch {
      setSelected(null);
      setError('No se pudo cargar el detalle de la aplicacion.');
    } finally {
      setLoadingDetails(false);
    }
  }

  return (
    <main className={`workspace ${filtersVisible ? '' : 'filters-hidden'}`}>
      <AppFilters
        active={filter}
        counts={stats?.filters ?? DEFAULT_COUNTS}
        onChange={(nextFilter) => {
          setFilter(nextFilter);
          setPage(1);
        }}
      />
      <section className="catalog-panel">
        <div className="catalog-header-row">
          <span>{formatLastScrape(stats)}</span>
          <button
            type="button"
            aria-label={t('app.refresh')}
            title={t('app.refresh')}
            disabled={loadingApps}
            onClick={() => setRefreshToken((value) => value + 1)}
          >
            <RefreshCw size={18} />
          </button>
        </div>
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
          onPageSizeChange={(nextPageSize) => {
            setPageSize(nextPageSize);
            setPage(1);
          }}
        />
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
  );
}

function BundleDetailPage() {
  const { slug } = useParams();
  const [bundle, setBundle] = useState<BundleDetails | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!slug) return;
    fetchBundle(slug)
      .then(setBundle)
      .catch(() => setError('No se pudo cargar el bundle.'));
  }, [slug]);

  if (error) return <main className="content-page"><p className="error-banner">{error}</p></main>;
  if (!bundle) return <main className="content-page"><p className="loading-label">Cargando bundle...</p></main>;

  return (
    <main className="content-page">
      <section className="bundle-detail-header">
        <div>
          <h2>{bundle.name}</h2>
          <p>{bundle.description || 'Bundle preparado para descarga en lote.'}</p>
          <div className="tag-list">
            {bundle.tags.map((tag) => (
              <span className="tag-chip" key={tag}>
                {tag}
              </span>
            ))}
          </div>
        </div>
        <span>{bundle.appCount} aplicaciones</span>
      </section>
      <div className="bundle-app-list">
        {bundle.apps.map((app) => (
          <div className="bundle-app-row" key={app.id}>
            <AppMiniIcon app={app} />
            <div>
              <strong>{app.name}</strong>
              <small>{app.publisher || '-'}</small>
            </div>
            <AppStatusBadge status={app.resolutionStatus} />
            <DownloadButton appId={app.id} disabled={!app.downloadable} />
          </div>
        ))}
      </div>
    </main>
  );
}

function LoginPage({ onLogin }: { onLogin: (user: AuthUser) => void }) {
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    try {
      const user = await login(username, password);
      onLogin(user);
      navigate('/admin');
    } catch {
      setError('Credenciales invalidas.');
    }
  }

  return (
    <main className="login-page">
      <form className="login-card" onSubmit={submit}>
        <h2>Acceso administrador</h2>
        <label>
          Usuario
          <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
        </label>
        <label>
          Contrasena
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
          />
        </label>
        {error ? <p className="error-banner">{error}</p> : null}
        <button className="primary-button" type="submit">Entrar</button>
      </form>
    </main>
  );
}

function Footer() {
  return (
    <footer className="site-footer">
      <Link to="/catalog">Catalogo</Link>
      <Link to="/login">Administrador</Link>
      <span>Batch Downloader MVP</span>
    </footer>
  );
}

function RequireAdmin({
  auth,
  checking,
  children,
}: {
  auth: AuthUser | null;
  checking: boolean;
  children: JSX.Element;
}) {
  if (checking) return <main className="content-page"><p className="loading-label">Comprobando sesion...</p></main>;
  if (!auth) return <Navigate to="/login" replace />;
  return children;
}

function AdminLayout({ onLogout }: { onLogout: () => void }) {
  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <Link className="brand admin-brand" to="/">
          <img className="brand-icon" src="/assets/icon.ico" alt="" aria-hidden="true" />
          <h1>{t('app.title')}</h1>
        </Link>
        <nav>
          <NavLink to="/admin" end><Home size={18} />Panel</NavLink>
          <NavLink to="/admin/apps"><PackagePlus size={18} />Aplicaciones</NavLink>
          <NavLink to="/admin/bundles"><Boxes size={18} />Bundles</NavLink>
          <NavLink to="/admin/scraper"><Play size={18} />Scraper</NavLink>
          <NavLink to="/admin/requests"><ClipboardList size={18} />Solicitudes</NavLink>
          <NavLink to="/admin/audit"><ListFilter size={18} />Auditoria</NavLink>
        </nav>
        <button type="button" onClick={onLogout}><LogOut size={18} />Salir</button>
      </aside>
      <main className="admin-content">
        <Outlet />
      </main>
    </div>
  );
}

function AdminDashboard() {
  const [stats, setStats] = useState<CatalogStats | null>(null);
  const [current, setCurrent] = useState<ScraperRunSummary | null>(null);

  useEffect(() => {
    fetchCatalogStats().then(setStats).catch(() => setStats(null));
    fetchAdminCurrentRun().then(setCurrent).catch(() => setCurrent(null));
  }, []);

  return (
    <section className="admin-panel">
      <h2>Panel administrador</h2>
      <div className="metric-grid">
        <Metric label="Aplicaciones" value={stats?.total ?? 0} />
        <Metric label="Disponibles" value={stats?.filters.available ?? 0} />
        <Metric label="Revision" value={stats?.filters.review ?? 0} />
        <Metric label="Sin instalador" value={stats?.filters.missing ?? 0} />
      </div>
      <div className="admin-card">
        <h3>Scraper actual</h3>
        <p>{current?.currentAppName || current?.status || 'Sin ejecucion registrada'}</p>
        <small>{current?.currentPhase || '-'}</small>
      </div>
    </section>
  );
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="metric-card">
      <span>{label}</span>
      <strong>{value.toLocaleString('es-ES')}</strong>
    </div>
  );
}

function AdminAppsPage() {
  const [query, setQuery] = useState('');
  const [apps, setApps] = useState<CatalogApp[]>([]);
  const [selected, setSelected] = useState<AppDetails | null>(null);
  const [form, setForm] = useState({ name: '', publisher: '', officialUrl: '', description: '' });
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    fetchAdminApps({ query, filter: 'all', sort: 'updated', page: 1, pageSize: 30 })
      .then((response) => setApps(response.data))
      .catch(() => setMessage('No se pudieron cargar las aplicaciones.'));
  }, [query]);

  async function select(app: CatalogApp) {
    setSelected(await fetchAppDetails(app.id));
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    try {
      const payload = {
        name: form.name || selected?.name,
        publisher: form.publisher || selected?.publisher,
        officialUrl: form.officialUrl || selected?.officialUrl,
        description: form.description || selected?.description,
      };
      if (selected) {
        setSelected(await patchAdminApp(selected.id, payload));
      } else {
        setSelected(await createAdminApp(payload));
      }
      setMessage('Aplicacion guardada.');
    } catch {
      setMessage('No se pudo guardar la aplicacion.');
    }
  }

  async function generateDescription() {
    if (!selected) return;
    setMessage('Generando descripcion...');
    try {
      const result = await generateAdminDescription(selected.id);
      setSelected({ ...selected, longDescription: result.longDescription });
      setMessage('Descripcion generada.');
    } catch {
      setMessage('No se pudo generar la descripcion.');
    }
  }

  return (
    <section className="admin-panel two-column-admin">
      <div>
        <h2>Aplicaciones</h2>
        <input
          className="admin-search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Buscar aplicaciones"
        />
        <div className="admin-list">
          {apps.map((app) => (
            <button type="button" key={app.id} onClick={() => void select(app)}>
              <AppMiniIcon app={app} />
              <span>{app.name}</span>
              <AppStatusBadge status={app.resolutionStatus} />
            </button>
          ))}
        </div>
      </div>
      <form className="admin-card editor-form" onSubmit={save}>
        <h3>{selected ? selected.name : 'Nueva aplicacion'}</h3>
        <label>Nombre<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder={selected?.name} /></label>
        <label>Editor<input value={form.publisher} onChange={(e) => setForm({ ...form, publisher: e.target.value })} placeholder={selected?.publisher ?? ''} /></label>
        <label>Web oficial<input value={form.officialUrl} onChange={(e) => setForm({ ...form, officialUrl: e.target.value })} placeholder={selected?.officialUrl ?? ''} /></label>
        <label>Descripcion corta<textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder={selected?.description ?? ''} /></label>
        <p>{selected?.longDescription || selected?.description || 'Sin descripcion larga.'}</p>
        {message ? <span className="form-message">{message}</span> : null}
        <div className="button-row">
          <button className="primary-button" type="submit">Guardar</button>
          <button type="button" className="secondary-button" onClick={generateDescription} disabled={!selected}>Generar IA</button>
        </div>
      </form>
    </section>
  );
}

function AdminBundlesPage() {
  const [official, setOfficial] = useState<BundleSummary[]>([]);
  const [form, setForm] = useState({ name: '', description: '', tags: '' });
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    fetchBundles({ type: 'official', pageSize: 30 })
      .then((response) => setOfficial(response.data))
      .catch(() => setOfficial([]));
  }, []);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    try {
      const created = await createAdminBundle({
        name: form.name,
        description: form.description,
        type: 'official',
        visibility: 'official',
        tags: form.tags.split(',').map((tag) => tag.trim()).filter(Boolean),
        appIds: [],
      });
      setOfficial((items) => [created, ...items]);
      setForm({ name: '', description: '', tags: '' });
      setMessage('Bundle guardado.');
    } catch {
      setMessage('No se pudo guardar el bundle.');
    }
  }

  return (
    <section className="admin-panel two-column-admin">
      <div>
        <h2>Bundles oficiales</h2>
        <div className="bundle-grid admin-bundles">
          {official.map((bundle) => <BundleCard bundle={bundle} key={bundle.id} />)}
        </div>
      </div>
      <form className="admin-card editor-form" onSubmit={save}>
        <h3>Editor de bundle</h3>
        <label>Nombre<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label>Descripcion<textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
        <label>Tags<input value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} placeholder="utilidades, trabajo" /></label>
        {message ? <span className="form-message">{message}</span> : null}
        <button className="primary-button" type="submit">Guardar bundle</button>
      </form>
    </section>
  );
}

function AdminScraperPage() {
  const [current, setCurrent] = useState<ScraperRunSummary | null>(null);
  const [runs, setRuns] = useState<ScraperRunSummary[]>([]);
  const [logs, setLogs] = useState<ResolverLogItem[]>([]);
  const [message, setMessage] = useState<string | null>(null);

  async function load() {
    const [nextCurrent, nextRuns, nextLogs] = await Promise.all([
      fetchAdminCurrentRun(),
      fetchAdminRuns(),
      fetchAdminLogs(),
    ]);
    setCurrent(nextCurrent);
    setRuns(nextRuns);
    setLogs(nextLogs);
  }

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), 10000);
    return () => window.clearInterval(timer);
  }, []);

  async function command(value: 'pause' | 'resume' | 'stop' | 'run_once') {
    setMessage(null);
    try {
      await sendScraperCommand(value);
      setMessage('Comando aceptado.');
      await load();
    } catch {
      setMessage('No se pudo enviar el comando.');
    }
  }

  return (
    <section className="admin-panel">
      <h2>Scraper</h2>
      <div className="scraper-status admin-card">
        <div>
          <span>Estado</span>
          <strong>{current?.status ?? '-'}</strong>
        </div>
        <div>
          <span>App actual</span>
          <strong>{current?.currentAppName ?? '-'}</strong>
        </div>
        <div>
          <span>Fase</span>
          <strong>{current?.currentPhase ?? '-'}</strong>
        </div>
        <div>
          <span>Progreso</span>
          <strong>{current ? `${current.appsResolved}/${current.appsDiscovered}` : '-'}</strong>
        </div>
      </div>
      <div className="button-row">
        <button className="secondary-button" type="button" onClick={() => command('pause')}>Pausar</button>
        <button className="secondary-button" type="button" onClick={() => command('resume')}>Continuar</button>
        <button className="secondary-button" type="button" onClick={() => command('stop')}><Square size={16} />Parar</button>
        <button className="primary-button" type="button" onClick={() => command('run_once')}>Ejecutar ahora</button>
      </div>
      {message ? <p className="form-message">{message}</p> : null}
      <div className="admin-grid-two">
        <AdminTable title="Ejecuciones" rows={runs.map((run) => [run.status, run.currentAppName || '-', formatDate(run.startedAt)])} />
        <AdminTable title="Logs recientes" rows={logs.map((log) => [log.phase, log.status, log.message || '-'])} />
      </div>
    </section>
  );
}

function AdminRequestsPage() {
  const [requests, setRequests] = useState<SoftwareRequestItem[]>([]);
  useEffect(() => {
    fetchAdminRequests().then(setRequests).catch(() => setRequests([]));
  }, []);
  return (
    <section className="admin-panel">
      <h2>Solicitudes de software</h2>
      <AdminTable title="Pendientes" rows={requests.map((request) => [request.requestedName, request.officialUrl, request.status])} />
    </section>
  );
}

function AdminAuditPage() {
  const [items, setItems] = useState<AuditItem[]>([]);
  useEffect(() => {
    fetchAdminAudit().then(setItems).catch(() => setItems([]));
  }, []);
  return (
    <section className="admin-panel">
      <h2>Auditoria</h2>
      <AdminTable title="Acciones recientes" rows={items.map((item) => [item.actor, item.action, item.targetId || '-', formatDate(item.createdAt)])} />
    </section>
  );
}

function AdminTable({ title, rows }: { title: string; rows: string[][] }) {
  return (
    <div className="admin-card admin-table-card">
      <h3>{title}</h3>
      <div className="admin-table">
        {rows.length ? rows.map((row, index) => (
          <div className="admin-table-row" key={`${title}-${index}`}>
            {row.map((cell, cellIndex) => <span key={`${title}-${index}-${cellIndex}`}>{cell}</span>)}
          </div>
        )) : <p className="empty-state">Sin registros.</p>}
      </div>
    </div>
  );
}

async function handleLogout(setAuth: (value: AuthUser | null) => void) {
  await logout().catch(() => undefined);
  setAuth(null);
}

function formatLastScrape(stats: CatalogStats | null): string {
  if (!stats?.lastScrape) return t('app.lastScrape.empty');
  const date = stats.lastScrape.finishedAt ?? stats.lastScrape.heartbeatAt ?? stats.lastScrape.startedAt;
  return `${t('app.lastScrape')}: ${formatDate(date)}`;
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('es-ES', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}
