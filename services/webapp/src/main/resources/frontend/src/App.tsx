import {
  ArrowDown,
  ArrowUp,
  BrainCircuit,
  Building2,
  Boxes,
  ClipboardList,
  ChevronLeft,
  ChevronRight,
  Globe2,
  Github,
  Home,
  ListFilter,
  LogOut,
  PackagePlus,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  Shield,
  Square,
  Tags,
  Trash2,
  UserCircle,
  Wand2,
  X,
} from 'lucide-react';
import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { Link, NavLink, Navigate, Outlet, Route, Routes, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  connectCatalogEvents,
  connectScraperEvents,
  clearAllScraperQueueItems,
  clearPendingScraperQueueItems,
  createScraperRun,
  createAdminBundle,
  enqueueMissingScraperDescriptions,
  fetchAdminApps,
  fetchAdminAudit,
  fetchAdminCurrentRun,
  fetchAdminLogs,
  fetchAdminMetrics,
  fetchAdminQueues,
  fetchAdminRequests,
  fetchAdminRuns,
  fetchAdminSnapshots,
  fetchAppDetails,
  fetchApps,
  fetchBundle,
  fetchBundles,
  fetchCatalogFacets,
  fetchCatalogStats,
  pruneTerminalScraperQueueItems,
  recoverStuckScraperQueueItems,
  retryFailedScraperQueueItems,
  sendScraperCommand,
  updateAdminBundle,
} from './api/catalog';
import {
  catalogFiltersToSearchParams,
  DEFAULT_CATALOG_FILTERS,
  nextFilters,
  normalizeCatalogStatus,
  parseCatalogFilters,
  preferredCatalogFilter,
  preferredCatalogSearchMode,
  toggleValue,
  toggleOperatingSystem,
  type CatalogFilterState,
} from './catalogFilters';
import {
  inspectCatalogSelectionRefresh,
  isCatalogAppSelectable,
  validateCatalogSelection,
} from './catalogSelection';
import { AppFilters } from './components/AppFilters';
import { AppSearchBar } from './components/AppSearchBar';
import { AppStatusBadge } from './components/AppStatusBadge';
import { AppTable } from './components/AppTable';
import { DownloadButton } from './components/DownloadButton';
import { BundleDownloadButton } from './components/BundleDownloadButton';
import { OperatingSystemList } from './components/OperatingSystemIcons';
import { Pagination } from './components/Pagination';
import { DownloadJobsProvider } from './downloads/DownloadJobsContext';
import { GlobalDownloadJobOverlay } from './downloads/GlobalDownloadJobOverlay';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { useDownloadJob } from './hooks/useDownloadJob';
import { AdminAppsPage as AdminAppsWorkbenchPage } from './pages/admin/AdminAppsPage';
import { SemanticAiPage } from './pages/admin/SemanticAiPage';
import {
  AccountBundlesPage,
  AccountLayout,
  AdminLoginPage,
  BundleEditorPage,
  DashboardPage,
  ForgotPasswordPage,
  ProfilePage,
  RegisterPage,
  ResetPasswordPage,
  UserLoginPage,
  VerifyEmailPage,
} from './pages/account/AccountPages';
import { t } from './services/i18n';
import type {
  AppDetails,
  AuditItem,
  AuthUser,
  BundleDetails,
  BundleSummary,
  CatalogFacets,
  CatalogAlphabetEntry,
  CatalogApp,
  CatalogStats,
  FacetItem,
  FilterKey,
  ResolverLogItem,
  ScraperMetricItem,
  ScraperQueueState,
  ScraperRunSummary,
  ScraperSnapshotItem,
  ScrapeScope,
  SoftwareRequestItem,
  SortKey,
} from './types/catalog';

const DEFAULT_COUNTS: Record<FilterKey, number> = {
  all: 0,
  available: 0,
  review: 0,
  missing: 0,
};

const FACET_ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')];
const CATALOG_REFRESH_INTERVAL_MS = 5_000;

export default function App() {
  return <AuthProvider><AppRoutes /></AuthProvider>;
}

function AppRoutes() {
  const { account: auth, status, signOut } = useAuth();
  const checkingAuth = status === 'checking';
  const onLogout = () => { void signOut(); };

  return (
    <DownloadJobsProvider>
      <Routes>
        <Route element={<PublicLayout auth={auth} onLogout={onLogout} />}>
          <Route index element={<HomePage />} />
          <Route path="catalog" element={<CatalogPage />} />
          <Route path="catalog/app/:appId" element={<CatalogPage />} />
          <Route path="catalog/tags" element={<FacetDirectoryPage kind="tags" />} />
          <Route path="catalog/editors" element={<FacetDirectoryPage kind="publishers" />} />
          <Route path="bundles/:slug" element={<BundleDetailPage />} />
          <Route path="login" element={<UserLoginPage />} />
          <Route path="register" element={<RegisterPage />} />
          <Route path="verify-email" element={<VerifyEmailPage />} />
          <Route path="forgot-password" element={<ForgotPasswordPage />} />
          <Route path="reset-password" element={<ResetPasswordPage />} />
          <Route path="error" element={<PublicErrorPage />} />
          <Route path="terms" element={<LegalPage kind="terms" />} />
          <Route path="privacy" element={<LegalPage kind="privacy" />} />
          <Route path="admin/login" element={<AdminLoginPage />} />
        </Route>
        <Route
          element={
            <RequireAccount auth={auth} checking={checkingAuth}>
              <AccountLayout />
            </RequireAccount>
          }
        >
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="dashboard/bundles" element={<AccountBundlesPage />} />
          <Route path="dashboard/bundles/new" element={<BundleEditorPage />} />
          <Route path="dashboard/bundles/:id/edit" element={<BundleEditorPage />} />
          <Route path="profile" element={<ProfilePage />} />
        </Route>
        <Route
          path="admin"
          element={
            <RequireAdmin auth={auth} checking={checkingAuth}>
              <AdminLayout onLogout={onLogout} />
            </RequireAdmin>
          }
        >
          <Route index element={<AdminDashboard />} />
          <Route path="apps" element={<AdminAppsWorkbenchPage />} />
          <Route path="bundles" element={<AdminBundlesPage />} />
          <Route path="scraper" element={<AdminScraperPage />} />
          <Route path="semantic" element={<Navigate to="/admin/semantic/models" replace />} />
          <Route path="semantic/:semanticSection" element={<SemanticAiPage />} />
          <Route path="requests" element={<AdminRequestsPage />} />
          <Route path="audit" element={<AdminAuditPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <GlobalDownloadJobOverlay />
    </DownloadJobsProvider>
  );
}

function PublicLayout({ auth, onLogout }: { auth: AuthUser | null; onLogout: () => void }) {
  const location = useLocation();
  const lockedCatalogSurface = location.pathname === '/catalog' || location.pathname.startsWith('/catalog/app/');

  return (
    <div className={`site-shell ${lockedCatalogSurface ? 'site-shell-app' : ''}`}>
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
      <nav className="main-nav" aria-label={t('nav.main')}>
        <NavLink to="/">{t('nav.home')}</NavLink>
        <NavLink to="/catalog">{t('nav.catalog')}</NavLink>
      </nav>
      <div className="topbar-actions">
        <button type="button" aria-label={t('language.selector')} title={t('language.selector')}>
          <Globe2 size={20} />
          {t('language.current')}
        </button>
        {auth?.role === 'ADMIN' ? (
          <>
            <NavLink className="admin-link" to="/admin">
              <Shield size={18} />
              {t('nav.admin')}
            </NavLink>
            <button type="button" onClick={onLogout}>
              <LogOut size={18} />
              {t('nav.logout')}
            </button>
          </>
        ) : auth?.role === 'USER' ? (
          <>
            <NavLink className="admin-link" to="/dashboard">
              <UserCircle size={22} />
              {auth.username}
            </NavLink>
            <button type="button" onClick={onLogout}><LogOut size={18} />{t('nav.logout')}</button>
          </>
        ) : (
          <NavLink className="admin-link" to="/login">
            <UserCircle size={22} />
            {t('nav.login')}
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
  const [loadingOfficial, setLoadingOfficial] = useState(true);
  const [loadingCommunity, setLoadingCommunity] = useState(true);
  const [loadingApps, setLoadingApps] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    const handleError = (requestError: unknown) => {
      if (!cancelled && !isAbortError(requestError)) setError(t('home.loadError'));
    };

    setError(null);
    void fetchBundles({ type: 'official', pageSize: 3 }, controller.signal)
      .then((official) => {
        if (cancelled) return;
        setOfficialBundles(official.data);
        setOfficialTotal(official.total);
      })
      .catch(handleError)
      .finally(() => {
        if (!cancelled) setLoadingOfficial(false);
      });

    void fetchBundles({ type: 'community', pageSize: 3 }, controller.signal)
      .then((community) => {
        if (cancelled) return;
        setCommunityBundles(community.data);
        setCommunityTotal(community.total);
      })
      .catch(handleError)
      .finally(() => {
        if (!cancelled) setLoadingCommunity(false);
      });

    void fetchApps(
      { query: '', filter: 'available', sort: 'updated', page: 1, pageSize: 6 },
      controller.signal,
    )
      .then((catalog) => {
        if (cancelled) return;
        setApps(catalog.data);
      })
      .catch(handleError)
      .finally(() => {
        if (!cancelled) setLoadingApps(false);
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, []);

  return (
    <main className="home-page">
      <section className="home-hero">
        <div>
          <h2>{t('home.hero.title')}</h2>
          <p>{t('home.hero.body')}</p>
        </div>
        <Link className="primary-link" to="/catalog">
          {t('home.hero.cta')}
        </Link>
      </section>
      {error ? <p className="error-banner">{error}</p> : null}
      <BundleSection
        title={t('home.bundleOfficial')}
        bundles={officialBundles}
        total={officialTotal}
        type="official"
        loading={loadingOfficial}
      />
      <BundleSection
        title={t('home.bundleCommunity')}
        bundles={communityBundles}
        total={communityTotal}
        type="community"
        loading={loadingCommunity}
      />
      <section className="home-section">
        <div className="section-heading">
          <h2>{t('home.appsRecent')}</h2>
          {apps.length > 5 ? <Link to="/catalog?sort=updated">{t('common.viewAll')}</Link> : null}
        </div>
        {loadingApps ? <p className="loading-label">{t('common.loading')}</p> : null}
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
  loading,
}: {
  title: string;
  bundles: BundleSummary[];
  total: number;
  type: 'official' | 'community';
  loading: boolean;
}) {
  return (
    <section className="home-section">
      <div className="section-heading">
        <h2>{title}</h2>
        {total > bundles.length ? <Link to={`/catalog?bundleType=${type}`}>{t('common.viewAll')}</Link> : null}
      </div>
      <div className="bundle-grid">
        {loading ? (
          <p className="loading-label">{t('common.loading')}</p>
        ) : bundles.length ? (
          bundles.map((bundle) => <BundleCard bundle={bundle} key={bundle.id} />)
        ) : (
          <p className="empty-state">{t('home.emptyBundles')}</p>
        )}
      </div>
    </section>
  );
}

function BundleCard({ bundle }: { bundle: BundleSummary }) {
  const availability = bundle.platformAvailability.length
    ? bundle.platformAvailability
    : bundle.operatingSystems.map((operatingSystem) => ({
      operatingSystem,
      downloadableAppCount: bundle.appCount,
      previewApps: bundle.previewApps,
    }));
  const [selectedOperatingSystem, setSelectedOperatingSystem] = useState(
    availability[0]?.operatingSystem ?? null,
  );
  const selectedAvailability = availability.find(
    (item) => item.operatingSystem === selectedOperatingSystem,
  ) ?? availability[0];
  const previewApps = selectedAvailability?.previewApps ?? [];
  const visibleApps = previewApps.slice(0, 5);
  const hiddenAppCount = Math.max(
    0,
    (selectedAvailability?.downloadableAppCount ?? 0) - visibleApps.length,
  );

  return (
    <article className="bundle-card bundle-card-home">
      <Link className="bundle-card-link" to={`/bundles/${bundle.id}`}>
        <div className="bundle-card-header bundle-card-header-home">
          <span className="bundle-icon">
            <Boxes size={22} />
          </span>
          <h3>{bundle.name}</h3>
          <small className="bundle-card-count">
            {t('bundle.appCount', { count: bundle.appCount })}
          </small>
        </div>
        <p className="bundle-card-description">
          {bundle.description || t('bundle.fallbackDescription')}
        </p>
        <div className="mini-apps bundle-card-preview">
          {visibleApps.map((app) => (
            <AppMiniIcon app={app} key={app.id} />
          ))}
          {hiddenAppCount > 0 ? <span className="mini-more">+{hiddenAppCount}</span> : null}
        </div>
      </Link>
      <BundleDownloadButton
        bundleId={bundle.id}
        bundleName={bundle.name}
        appCount={bundle.appCount}
        operatingSystems={bundle.operatingSystems}
        platformAvailability={availability}
        selectedOperatingSystem={selectedAvailability?.operatingSystem ?? null}
        onOperatingSystemChange={setSelectedOperatingSystem}
        compact
      />
    </article>
  );
}

function AppCompactCard({ app }: { app: CatalogApp }) {
  return (
    <Link className="app-compact-card" to={`/catalog/app/${app.id}?sort=updated`}>
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

  function updateFilters(patch: Partial<CatalogFilterState>, resetPage = true, replace = false) {
    const params = catalogFiltersToSearchParams(nextFilters(filters, patch, resetPage));
    setSearchParams(params, { replace });
  }

  function commitDownloadSelection(next: Set<string>, addedApp?: CatalogApp) {
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
  }

  function removeDownloadSelections(ids: readonly string[]) {
    if (ids.length === 0) return;
    const next = new Set(selectedDownloadIdsRef.current);
    let changed = false;
    ids.forEach((id) => {
      changed = next.delete(id) || changed;
    });
    if (changed) commitDownloadSelection(next);
  }

  function clearDownloadSelection() {
    commitDownloadSelection(new Set());
  }

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (query !== filters.query) {
        updateFilters({ query }, true, true);
      }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query, filters]);

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
    const previousPage = lastLoadedPage.current?.searchKey === canonicalSearchKey
      ? lastLoadedPage.current.apps
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
        setSearchNotice(response.degradedReason ? t('catalog.search.degraded') : null);
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
  }, [canonicalSearchKey, catalogStatusCanonical, filters, refreshToken]);

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
  }, [appId, refreshToken]);

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
          <span>{formatLastScrape(stats)}</span>
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

function FacetDirectoryPage({ kind }: { kind: 'tags' | 'publishers' }) {
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
  }, [catalogStatusCanonical, filters]);

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

function BundleDetailPage() {
  const { slug } = useParams();
  const [bundle, setBundle] = useState<BundleDetails | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!slug) return;
    fetchBundle(slug)
      .then(setBundle)
      .catch(() => setError(t('bundle.loadError')));
  }, [slug]);

  if (error) return <main className="content-page"><p className="error-banner">{error}</p></main>;
  if (!bundle) return <main className="content-page"><p className="loading-label">{t('bundle.loading')}</p></main>;

  return (
    <main className="content-page">
      <section className="bundle-detail-header">
        <div>
          <h2>{bundle.name}</h2>
          <p>{bundle.description || t('bundle.fallbackDescription')}</p>
          <div className="tag-list">
            {bundle.tags.map((tag) => (
              <span className="tag-chip" key={tag}>
                {tag}
              </span>
            ))}
          </div>
        </div>
        <div className="bundle-detail-actions">
          <span>{t('bundle.appsCount', { count: bundle.appCount })}</span>
          <BundleDownloadButton
            bundleId={bundle.id}
            bundleName={bundle.name}
            appCount={bundle.appCount}
            operatingSystems={bundle.operatingSystems}
            platformAvailability={bundle.platformAvailability}
          />
        </div>
      </section>
      <div className="bundle-app-list">
        {bundle.apps.map((app) => (
          <div className="bundle-app-row" key={app.id}>
            <AppMiniIcon app={app} />
            <div>
              <strong>{app.name}</strong>
              <small>{app.publisher || '-'}</small>
            </div>
            <OperatingSystemList operatingSystems={app.operatingSystems} />
            <AppStatusBadge status={app.resolutionStatus} />
            <DownloadButton appId={app.id} appName={app.name} disabled={!isCatalogAppSelectable(app)} />
          </div>
        ))}
      </div>
    </main>
  );
}

function Footer() {
  return (
    <footer className="site-footer">
      <div className="site-footer-grid">
        <nav className="site-footer-column" aria-labelledby="footer-pages-title">
          <h2 id="footer-pages-title">{t('footer.pages')}</h2>
          <Link to="/">{t('nav.home')}</Link>
          <Link to="/catalog">{t('nav.catalog')}</Link>
          <Link to="/login">{t('footer.login')}</Link>
          <Link to="/register">{t('footer.register')}</Link>
        </nav>
        <nav className="site-footer-column" aria-labelledby="footer-legal-title">
          <h2 id="footer-legal-title">{t('footer.legal')}</h2>
          <Link to="/terms">{t('footer.terms')}</Link>
          <Link to="/privacy">{t('footer.privacy')}</Link>
        </nav>
        <section className="site-footer-column" aria-labelledby="footer-project-title">
          <h2 id="footer-project-title">{t('footer.project')}</h2>
          <a href="https://joseleelportfolio.vercel.app/" target="_blank" rel="noreferrer">
            <img className="site-footer-icon" src="/assets/google-material-language.svg" alt="" aria-hidden="true" />
            <span>{t('footer.portfolio')}</span>
          </a>
          <a href="https://github.com/Joseleelsuper/Batch-Downloader" target="_blank" rel="noreferrer">
            <Github aria-hidden="true" />
            <span>{t('footer.github')}</span>
          </a>
        </section>
      </div>
      <p className="site-footer-meta">Batch Downloader MVP</p>
    </footer>
  );
}

function PublicErrorPage() {
  const [search] = useSearchParams();
  const code = search.get('code');
  const knownError = code === 'google_oauth_not_configured' || code === 'oauth_failed' ? code : 'unexpected_error';
  const status = knownError === 'google_oauth_not_configured' ? '503' : knownError === 'oauth_failed' ? '401' : null;

  return (
    <main className="content-page public-message-page">
      <section className="public-message-card" role="alert">
        {status ? <span className="public-message-status">{t('error.status', { status })}</span> : null}
        <h2>{t(`error.${knownError}.title`)}</h2>
        <p>{t(`error.${knownError}.body`)}</p>
        <div className="public-message-actions">
          <Link className="primary-button" to="/login">{t('error.backToLogin')}</Link>
          <Link className="secondary-button" to="/">{t('error.backToHome')}</Link>
        </div>
      </section>
    </main>
  );
}

function LegalPage({ kind }: { kind: 'terms' | 'privacy' }) {
  const sections = kind === 'terms'
    ? ['use', 'sources', 'availability']
    : ['data', 'purpose', 'rights'];
  return (
    <main className="content-page legal-page">
      <header className="legal-page-header">
        <span>{t('legal.eyebrow')}</span>
        <h2>{t(`legal.${kind}.title`)}</h2>
        <p>{t(`legal.${kind}.intro`)}</p>
      </header>
      <div className="legal-sections">
        {sections.map((section) => (
          <section key={section}>
            <h3>{t(`legal.${kind}.${section}.title`)}</h3>
            <p>{t(`legal.${kind}.${section}.body`)}</p>
          </section>
        ))}
      </div>
    </main>
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
  const location = useLocation();
  if (checking) return <main className="content-page"><p className="loading-label">{t('login.checkingSession')}</p></main>;
  if (!auth || auth.role !== 'ADMIN') return <Navigate to="/admin/login" replace state={{ from: location }} />;
  return children;
}

function RequireAccount({
  auth,
  checking,
  children,
}: {
  auth: AuthUser | null;
  checking: boolean;
  children: JSX.Element;
}) {
  const location = useLocation();
  if (checking) return <main className="content-page"><p className="loading-label">{t('login.checkingSession')}</p></main>;
  if (!auth || auth.role !== 'USER') return <Navigate to="/login" replace state={{ from: location }} />;
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
          <NavLink to="/admin" end><Home size={18} />{t('admin.layout.dashboard')}</NavLink>
          <NavLink to="/admin/apps"><PackagePlus size={18} />{t('admin.layout.apps')}</NavLink>
          <NavLink to="/admin/bundles"><Boxes size={18} />{t('admin.layout.bundles')}</NavLink>
          <NavLink to="/admin/scraper"><Play size={18} />{t('admin.layout.scraper')}</NavLink>
          <NavLink to="/admin/semantic"><BrainCircuit size={18} />{t('admin.layout.semantic')}</NavLink>
          <NavLink to="/admin/requests"><ClipboardList size={18} />{t('admin.layout.requests')}</NavLink>
          <NavLink to="/admin/audit"><ListFilter size={18} />{t('admin.layout.audit')}</NavLink>
        </nav>
        <button type="button" onClick={onLogout}><LogOut size={18} />{t('admin.layout.logout')}</button>
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
      <h2>{t('admin.dashboard.title')}</h2>
      <div className="metric-grid">
        <Metric label={t('admin.layout.apps')} value={stats?.total ?? 0} />
        <Metric label={t('catalog.filter.available')} value={stats?.filters.available ?? 0} />
        <Metric label={t('catalog.filter.review')} value={stats?.filters.review ?? 0} />
        <Metric label={t('catalog.filter.missing')} value={stats?.filters.missing ?? 0} />
      </div>
      <div className="admin-card">
        <h3>{t('admin.dashboard.currentScraper')}</h3>
        <p>{current?.currentAppName || current?.status || t('admin.dashboard.noCurrentRun')}</p>
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

function AdminBundlesPage() {
  const [official, setOfficial] = useState<BundleSummary[]>([]);
  const [selected, setSelected] = useState<BundleDetails | null>(null);
  const [form, setForm] = useState({ name: '', description: '', tags: '' });
  const [bundleApps, setBundleApps] = useState<CatalogApp[]>([]);
  const [appQuery, setAppQuery] = useState('');
  const [appResults, setAppResults] = useState<CatalogApp[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const availableAppResults = useMemo(() => {
    const selectedAppIds = new Set(bundleApps.map((app) => app.id));
    return appResults.filter((app) => !selectedAppIds.has(app.id));
  }, [appResults, bundleApps]);

  useEffect(() => {
    void loadBundles();
  }, []);

  useEffect(() => {
    let cancelled = false;
    fetchAdminApps({ query: appQuery, filter: 'all', sort: 'updated', page: 1, pageSize: 12 })
      .then((response) => {
        if (!cancelled) setAppResults(response.data);
      })
      .catch(() => {
        if (!cancelled) setAppResults([]);
      });
    return () => {
      cancelled = true;
    };
  }, [appQuery]);

  async function loadBundles() {
    try {
      const response = await fetchBundles({ type: 'official', pageSize: 30 });
      setOfficial(response.data);
    } catch {
      setOfficial([]);
    }
  }

  function resetBundleEditor() {
    setSelected(null);
    setForm({ name: '', description: '', tags: '' });
    setBundleApps([]);
    setMessage(null);
  }

  async function selectBundle(bundle: BundleSummary) {
    const details = await fetchBundle(bundle.id);
    setSelected(details);
    setForm({
      name: details.name,
      description: details.description ?? '',
      tags: details.tags.join(', '),
    });
    setBundleApps(details.apps);
    setMessage(null);
  }

  function addBundleApp(app: CatalogApp) {
    setBundleApps((current) => (current.some((item) => item.id === app.id) ? current : [...current, app]));
  }

  function removeBundleApp(appId: string) {
    setBundleApps((current) => current.filter((app) => app.id !== appId));
  }

  function moveBundleApp(appId: string, direction: -1 | 1) {
    setBundleApps((current) => {
      const index = current.findIndex((app) => app.id === appId);
      const nextIndex = index + direction;
      if (index < 0 || nextIndex < 0 || nextIndex >= current.length) return current;
      const next = [...current];
      [next[index], next[nextIndex]] = [next[nextIndex], next[index]];
      return next;
    });
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    try {
      const payload = {
        name: form.name,
        description: form.description,
        type: 'official',
        visibility: 'official',
        tags: form.tags.split(',').map((tag) => tag.trim()).filter(Boolean),
        appIds: bundleApps.map((app) => app.id),
      };
      const saved = selected
        ? await updateAdminBundle(selected.id, payload)
        : await createAdminBundle(payload);
      setSelected(saved);
      setBundleApps(saved.apps);
      setForm({
        name: saved.name,
        description: saved.description ?? '',
        tags: saved.tags.join(', '),
      });
      await loadBundles();
      setMessage(t('admin.message.bundleSaved'));
    } catch {
      setMessage(t('admin.message.saveBundleError'));
    }
  }

  return (
    <section className="admin-panel two-column-admin">
      <div>
        <div className="admin-section-heading">
          <h2>{t('admin.bundle.official')}</h2>
          <button className="secondary-button compact-button" type="button" onClick={resetBundleEditor}>
            <Plus size={17} />
            {t('admin.bundle.new')}
          </button>
        </div>
        <div className="bundle-grid admin-bundles">
          {official.map((bundle) => (
            <button
              className={`bundle-card admin-bundle-button ${selected?.id === bundle.id ? 'admin-list-active' : ''}`}
              type="button"
              key={bundle.id}
              onClick={() => void selectBundle(bundle)}
            >
              <div className="bundle-card-header">
                <span className="bundle-icon"><Boxes size={22} /></span>
                <div>
                  <h3>{bundle.name}</h3>
                  <small>{t('bundle.appCount', { count: bundle.appCount })}</small>
                </div>
              </div>
              <p>{bundle.description || t('bundle.fallbackDescription')}</p>
            </button>
          ))}
        </div>
      </div>
      <form className="admin-card editor-form" onSubmit={save}>
        <div className="editor-header">
          <div>
            <span>{selected ? t('admin.bundle.editing') : t('admin.bundle.newBundle')}</span>
            <h3>{selected ? selected.name : t('admin.bundle.editor')}</h3>
          </div>
          {selected ? <small>{selected.id}</small> : null}
        </div>
        <label>{t('admin.field.name')}<input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label>{t('admin.field.description')}<textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
        <label>{t('admin.field.tags')}<input value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} placeholder={t('admin.bundle.placeholder.tags')} /></label>
        <div className="bundle-app-editor">
          <h4>{t('admin.bundle.apps')}</h4>
          <div className="bundle-app-selected">
            {bundleApps.length ? bundleApps.map((app, index) => (
              <div className="bundle-app-edit-row" key={app.id}>
                <AppMiniIcon app={app} />
                <span>{app.name}</span>
                <button type="button" onClick={() => moveBundleApp(app.id, -1)} disabled={index === 0} title={t('admin.bundle.moveUp')}>
                  <ArrowUp size={16} />
                </button>
                <button type="button" onClick={() => moveBundleApp(app.id, 1)} disabled={index === bundleApps.length - 1} title={t('admin.bundle.moveDown')}>
                  <ArrowDown size={16} />
                </button>
                <button type="button" onClick={() => removeBundleApp(app.id)} title={t('admin.bundle.removeApp')}>
                  <X size={16} />
                </button>
              </div>
            )) : <p className="empty-state">{t('admin.bundle.addAppsEmpty')}</p>}
          </div>
          <input
            value={appQuery}
            onChange={(event) => setAppQuery(event.target.value)}
            placeholder={t('admin.bundle.searchApps')}
          />
          <div className="app-picker-results">
            {availableAppResults.map((app) => (
              <button type="button" key={app.id} onClick={() => addBundleApp(app)}>
                <AppMiniIcon app={app} />
                <span>{app.name}</span>
                <Plus size={16} />
              </button>
            ))}
          </div>
        </div>
        {message ? <span className="form-message">{message}</span> : null}
        <button className="primary-button" type="submit">
          <Save size={17} />
          {selected ? t('common.saveChanges') : t('admin.bundle.create')}
        </button>
      </form>
    </section>
  );
}

function AdminScraperPage() {
  const [current, setCurrent] = useState<ScraperRunSummary | null>(null);
  const [runs, setRuns] = useState<ScraperRunSummary[]>([]);
  const [logs, setLogs] = useState<ResolverLogItem[]>([]);
  const [queues, setQueues] = useState<ScraperQueueState[]>([]);
  const [metrics, setMetrics] = useState<ScraperMetricItem[]>([]);
  const [snapshots, setSnapshots] = useState<ScraperSnapshotItem[]>([]);
  const [socketState, setSocketState] = useState<'live' | 'reconnecting' | 'offline'>('offline');
  const [message, setMessage] = useState<string | null>(null);
  const [enrichmentAction, setEnrichmentAction] = useState<'descriptions' | null>(null);

  async function load() {
    const [nextCurrent, nextRuns, nextLogs, nextQueues, nextMetrics, nextSnapshots] = await Promise.all([
      fetchAdminCurrentRun(),
      fetchAdminRuns(),
      fetchAdminLogs(),
      fetchAdminQueues(),
      fetchAdminMetrics(),
      fetchAdminSnapshots(),
    ]);
    setCurrent(nextCurrent);
    setRuns(nextRuns);
    setLogs(nextLogs);
    setQueues(nextQueues);
    setMetrics(nextMetrics);
    setSnapshots(nextSnapshots);
  }

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), 10000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => connectScraperEvents((event) => {
    setQueues(event.queues);
    setMetrics(event.metrics);
    setSnapshots(event.snapshots);
  }, setSocketState), []);

  async function command(value: 'pause' | 'resume' | 'stop' | 'force_stop') {
    setMessage(null);
    try {
      await sendScraperCommand(value);
      setMessage(t('admin.message.acceptedCommand'));
      await load();
    } catch {
      setMessage(t('admin.message.sendCommandError'));
    }
  }

  async function requestRun(scope: Exclude<ScrapeScope, 'selected'>) {
    setMessage(null);
    try {
      const request = await createScraperRun(scope);
      setMessage(t('admin.message.acceptedRun', {
        scope: request.scope,
        requestId: request.requestId,
      }));
      await load();
    } catch {
      setMessage(t('admin.message.sendCommandError'));
    }
  }

  async function maintainQueue(action: 'recover_stuck' | 'retry_failed' | 'prune_terminal' | 'clear_pending' | 'clear_all') {
    if (action === 'clear_all' && !window.confirm(t('admin.scraper.confirm.clearAll'))) return;
    setMessage(null);
    try {
      const result = action === 'recover_stuck'
        ? await recoverStuckScraperQueueItems()
        : action === 'retry_failed'
          ? await retryFailedScraperQueueItems()
          : action === 'prune_terminal'
            ? await pruneTerminalScraperQueueItems()
            : action === 'clear_pending'
              ? await clearPendingScraperQueueItems()
              : await clearAllScraperQueueItems();
      setMessage(t(`admin.message.queueMaintenance.${action}`, { count: result.affected }));
      await load();
    } catch {
      setMessage(t('admin.message.queueMaintenanceError'));
    }
  }

  async function enqueueMissingDescriptions() {
    setMessage(null);
    setEnrichmentAction('descriptions');
    try {
      const result = await enqueueMissingScraperDescriptions();
      if (result.matched === 0) {
        setMessage(t('admin.message.noMissingDescriptions'));
      } else {
        setMessage(t('admin.message.enrichmentQueued', {
          enqueued: result.enqueued,
          active: result.alreadyActive,
        }));
      }
      await load();
    } catch {
      setMessage(t('admin.message.enrichmentQueueError'));
    } finally {
      setEnrichmentAction(null);
    }
  }

  const controlState = scraperControlState(current);

  return (
    <section className="admin-panel">
      <h2>{t('admin.layout.scraper')}</h2>
      <div className="scraper-status admin-card">
        <div>
          <span>{t('admin.scraper.status')}</span>
          <strong>{current?.status ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.scope')}</span>
          <strong>{current?.scope ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.app')}</span>
          <strong>{current?.currentAppName ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.currentPhase')}</span>
          <strong>{current?.currentPhase ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.progress')}</span>
          <strong>{current ? formatScrapeProgress(current) : '-'}</strong>
        </div>
      </div>
      <div className="button-row">
        <button className="secondary-button" type="button" disabled={!controlState.pause.enabled} title={controlState.pause.reason} onClick={() => command('pause')}>{t('admin.scraper.pause')}</button>
        <button className="secondary-button" type="button" disabled={!controlState.resume.enabled} title={controlState.resume.reason} onClick={() => command('resume')}>{t('admin.scraper.resume')}</button>
        <button className="secondary-button" type="button" disabled={!controlState.stop.enabled} title={controlState.stop.reason} onClick={() => command('stop')}><Square size={16} />{t('admin.scraper.stop')}</button>
        <button className="secondary-button danger-button" type="button" disabled={!controlState.forceStop.enabled} title={controlState.forceStop.reason} onClick={() => command('force_stop')}><Square size={16} />{t('admin.scraper.forceStop')}</button>
        <button className="primary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('incremental')}>{t('admin.scraper.runIncremental')}</button>
        <button className="secondary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('unresolved')}>{t('admin.scraper.runUnresolved')}</button>
        <button className="secondary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('full')}>{t('admin.scraper.runFull')}</button>
      </div>
      <div className="button-row queue-maintenance-row">
        <button className="secondary-button" type="button" onClick={() => maintainQueue('recover_stuck')}><RotateCcw size={16} />{t('admin.scraper.recoverStuck')}</button>
        <button className="secondary-button" type="button" onClick={() => maintainQueue('retry_failed')}><RefreshCw size={16} />{t('admin.scraper.retryFailed')}</button>
        <button className="secondary-button" type="button" onClick={() => maintainQueue('prune_terminal')}><Trash2 size={16} />{t('admin.scraper.pruneTerminal')}</button>
        <button className="secondary-button" type="button" onClick={() => maintainQueue('clear_pending')}><Trash2 size={16} />{t('admin.scraper.clearPending')}</button>
        <button className="secondary-button danger-button" type="button" onClick={() => maintainQueue('clear_all')}><Trash2 size={16} />{t('admin.scraper.clearAll')}</button>
      </div>
      <div className="button-row queue-maintenance-row">
        <button
          className="secondary-button"
          type="button"
          disabled={enrichmentAction !== null}
          onClick={() => enqueueMissingDescriptions()}
        >
          <Wand2 size={16} />
          {t('admin.scraper.enqueueMissingDescriptions')}
        </button>
      </div>
      {message ? <p className="form-message">{message}</p> : null}
      <div className="scraper-live-line">
        <span>{t('admin.scraper.liveState')}</span>
        <strong>{socketState}</strong>
      </div>
      <ScraperQueues queues={queues} />
      <ScraperMetricsChart metrics={metrics} />
      <ScraperSnapshots snapshots={snapshots} />
      <div className="admin-grid-two">
        <AdminTable
          title={t('admin.scraper.runs')}
          rows={runs.map((run) => [
            run.status,
            `${run.scope} · ${run.targetCount}`,
            formatScrapeProgress(run),
            run.currentAppName || run.currentPackageId || t('admin.scraper.noApp'),
            run.currentPhase || run.errorSummary || t('admin.scraper.noPhase'),
            formatDate(run.startedAt),
          ])}
        />
        <AdminTable
          title={t('admin.scraper.logs')}
          rows={logs.map((log) => [
            log.phase,
            log.status,
            formatLogDetails(log),
            formatDate(log.createdAt),
          ])}
        />
      </div>
    </section>
  );
}

export function ScraperQueues({ queues }: Readonly<{ queues: ScraperQueueState[] }>) {
  const searcherFilter = queues.find((queue) => queue.queue === 'searcher_filter');
  const filterScraper = queues.find((queue) => queue.queue === 'filter_scraper');
  const scraperSoFilter = queues.find((queue) => (
    queue.queue === 'scraper_so_filter' || queue.queue === 'scraper_os_filter'
  ));
  const soFilterDescriptor = queues.find((queue) => (
    queue.queue === 'so_filter_descriptor'
    || queue.queue === 'os_filter_descriptor'
    || queue.queue === 'scraper_descriptor'
  ));
  return (
    <div className="scraper-pipeline admin-card">
      <PipelineStage title={t('admin.scraper.stage.searcher')} count={searcherFilter?.queued ?? 0} />
      <QueueColumn title={t('admin.scraper.queue.searcherFilter')} queue={searcherFilter} />
      <PipelineStage title={t('admin.scraper.stage.filter')} count={filterScraper?.queued ?? 0} />
      <QueueColumn title={t('admin.scraper.queue.filterScraper')} queue={filterScraper} />
      <PipelineStage title={t('admin.scraper.stage.scraper')} count={filterScraper?.inProgress ?? 0} />
      <QueueColumn title={t('admin.scraper.queue.scraperSoFilter')} queue={scraperSoFilter} />
      <PipelineStage title={t('admin.scraper.stage.soFilter')} count={scraperSoFilter?.inProgress ?? 0} />
      <QueueColumn title={t('admin.scraper.queue.soFilterDescriptor')} queue={soFilterDescriptor} />
      <PipelineStage title={t('admin.scraper.stage.descriptor')} count={soFilterDescriptor?.inProgress ?? 0} />
    </div>
  );
}

function PipelineStage({ title, count }: Readonly<{ title: string; count: number }>) {
  return (
    <div className="pipeline-stage">
      <strong>{title}</strong>
      <span>{count}</span>
    </div>
  );
}

function QueueColumn({ title, queue }: Readonly<{ title: string; queue?: ScraperQueueState }>) {
  const items = queue?.items ?? [];
  return (
    <div className="pipeline-queue">
      <div className="pipeline-queue-heading">
        <strong>{title}</strong>
        <span>{queue ? `${queue.queued}/${queue.inProgress}` : '0/0'}</span>
      </div>
      <div className="pipeline-queue-list">
        {items.length ? items.slice(0, 5).map((item) => (
          <span className={`pipeline-token pipeline-token-${item.status}`} key={item.id}>
            {item.appName || item.packageId}
          </span>
        )) : <span className="pipeline-token">{t('admin.table.empty')}</span>}
      </div>
    </div>
  );
}

function ScraperMetricsChart({ metrics }: Readonly<{ metrics: ScraperMetricItem[] }>) {
  const width = 720;
  const height = 190;
  const maxValue = Math.max(1, ...metrics.flatMap((item) => [item.available, item.review, item.unavailable]));
  return (
    <div className="admin-card scraper-chart-card">
      <div className="scraper-section-heading">
        <h3>{t('admin.scraper.metrics')}</h3>
      </div>
      {metrics.length ? (
        <svg className="scraper-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={t('admin.scraper.metrics')}>
          <polyline points={metricPoints(metrics, 'available', width, height, maxValue)} className="metric-line metric-line-available" />
          <polyline points={metricPoints(metrics, 'review', width, height, maxValue)} className="metric-line metric-line-review" />
          <polyline points={metricPoints(metrics, 'unavailable', width, height, maxValue)} className="metric-line metric-line-unavailable" />
        </svg>
      ) : <p className="empty-state">{t('admin.table.empty')}</p>}
      <div className="metric-legend">
        <span className="legend-available">{t('catalog.filter.available')}</span>
        <span className="legend-review">{t('catalog.filter.review')}</span>
        <span className="legend-unavailable">{t('catalog.filter.missing')}</span>
      </div>
    </div>
  );
}

function ScraperSnapshots({ snapshots }: Readonly<{ snapshots: ScraperSnapshotItem[] }>) {
  const [selectedStage, setSelectedStage] = useState<string | null>(null);
  const selected = snapshots.find((snapshot) => snapshot.stage === selectedStage) ?? snapshots[0];
  useEffect(() => {
    if (!selectedStage && snapshots[0]) setSelectedStage(snapshots[0].stage);
  }, [selectedStage, snapshots]);
  return (
    <div className="admin-card scraper-snapshot-card">
      <div className="scraper-section-heading">
        <h3>{t('admin.scraper.snapshot')}</h3>
        <div className="snapshot-tabs">
          {snapshots.map((snapshot) => (
            <button
              className={snapshot.stage === selected?.stage ? 'snapshot-tab-active' : ''}
              key={snapshot.stage}
              onClick={() => setSelectedStage(snapshot.stage)}
              type="button"
            >
              {snapshot.stage}
            </button>
          ))}
        </div>
      </div>
      {selected ? (
        <>
          <div className="snapshot-meta">
            <span>{selected.appName || selected.packageId || '-'}</span>
            <span>{selected.url || '-'}</span>
          </div>
          <iframe
            className="snapshot-frame"
            sandbox=""
            srcDoc={selected.html || `<p>${t('admin.scraper.snapshotEmpty')}</p>`}
            title={t('admin.scraper.snapshot')}
          />
        </>
      ) : <p className="empty-state">{t('admin.table.empty')}</p>}
    </div>
  );
}

function AdminRequestsPage() {
  const [requests, setRequests] = useState<SoftwareRequestItem[]>([]);
  useEffect(() => {
    fetchAdminRequests().then(setRequests).catch(() => setRequests([]));
  }, []);
  return (
    <section className="admin-panel">
      <h2>{t('admin.request.title')}</h2>
      <AdminTable title={t('admin.request.pending')} rows={requests.map((request) => [request.requestedName, request.officialUrl, request.status])} />
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
      <h2>{t('admin.audit.title')}</h2>
      <AdminTable title={t('admin.audit.recentActions')} rows={items.map((item) => [item.actor, item.action, item.targetId || '-', formatDate(item.createdAt)])} />
    </section>
  );
}

function AdminTable({ title, rows }: { title: string; rows: string[][] }) {
  return (
    <div className="admin-card admin-table-card">
      <h3>{title}</h3>
      <div className="admin-table">
        {rows.length ? rows.map((row, index) => (
          <div
            className="admin-table-row"
            key={`${title}-${index}`}
            style={{ gridTemplateColumns: `repeat(${row.length}, minmax(0, 1fr))` }}
          >
            {row.map((cell, cellIndex) => <span key={`${title}-${index}-${cellIndex}`}>{cell}</span>)}
          </div>
        )) : <p className="empty-state">{t('admin.table.empty')}</p>}
      </div>
    </div>
  );
}

function formatScrapeProgress(run: ScraperRunSummary): string {
  return [
    t('admin.scraper.progress.resolved', { count: run.appsResolved }),
    t('admin.scraper.progress.discovered', { count: run.appsDiscovered }),
    t('admin.scraper.progress.failed', { count: run.appsFailed }),
    t('admin.scraper.progress.skipped', { count: run.appsSkipped }),
    t('admin.scraper.progress.review', { count: run.appsNeedsReview }),
    t('admin.scraper.progress.confirmedMissing', { count: run.appsConfirmedMissing }),
    t('admin.scraper.progress.transient', { count: run.appsTransientFailed }),
  ].join(' · ');
}

function metricPoints(
  metrics: ScraperMetricItem[],
  key: 'available' | 'review' | 'unavailable',
  width: number,
  height: number,
  maxValue: number,
): string {
  if (!metrics.length) return '';
  const xStep = metrics.length === 1 ? 0 : width / (metrics.length - 1);
  return metrics.map((item, index) => {
    const x = Math.round(index * xStep);
    const y = Math.round(height - (item[key] / maxValue) * (height - 24) - 12);
    return `${x},${y}`;
  }).join(' ');
}

function scraperControlState(current: ScraperRunSummary | null) {
  const running = current?.status === 'running';
  const paused = running && Boolean(current?.pausedAt || current?.currentPhase === 'paused');
  const stopping = Boolean(current?.stopRequested || current?.currentPhase === 'stopping');
  return {
    pause: {
      enabled: running && !paused && !stopping,
      reason: running && !paused && !stopping ? t('admin.scraper.reason.pause') : t('admin.scraper.reason.pauseUnavailable'),
    },
    resume: {
      enabled: paused && !stopping,
      reason: paused && !stopping ? t('admin.scraper.reason.resume') : t('admin.scraper.reason.resumeUnavailable'),
    },
    stop: {
      enabled: running && !stopping,
      reason: running && !stopping ? t('admin.scraper.reason.stop') : t('admin.scraper.reason.stopUnavailable'),
    },
    forceStop: {
      enabled: Boolean(current),
      reason: current ? t('admin.scraper.reason.forceStop') : t('admin.scraper.reason.forceStopUnavailable'),
    },
    runOnce: {
      enabled: !running,
      reason: !running ? t('admin.scraper.reason.runOnce') : t('admin.scraper.reason.runOnceUnavailable'),
    },
  };
}

function formatLogDetails(log: ResolverLogItem): string {
  const metadata = parseSafeMetadata(log.safeMetadata);
  const domain = metadataString(metadata, 'domain');
  const reason = metadataString(metadata, 'reason');
  const extension = metadataString(metadata, 'extension');
  const assetKind = metadataString(metadata, 'asset_kind');
  const source = metadataString(metadata, 'source');
  const error = metadataString(metadata, 'error');
  const detail = metadataString(metadata, 'detail');
  const statement = metadataString(metadata, 'statement');
  const winstallId = metadataString(metadata, 'winstall_id');
  const appName = metadataString(metadata, 'app_name');
  const currentPhase = metadataString(metadata, 'current_phase');
  const lastKnownStep = metadataString(metadata, 'last_known_step');
  const officialDomain = metadataString(metadata, 'official_domain');
  const score = metadataNumber(metadata, 'score');
  const elapsedSeconds = metadataNumber(metadata, 'elapsed_seconds');
  const timeoutSeconds = metadataNumber(metadata, 'timeout_seconds');
  const isPrimary = metadataBoolean(metadata, 'is_primary');
  const details = [
    error ? t('admin.log.error', { value: error }) : null,
    detail ? t('admin.log.detail', { value: detail }) : null,
    appName ? t('admin.log.app', { value: appName }) : null,
    winstallId ? t('admin.log.winstallId', { value: winstallId }) : null,
    currentPhase ? t('admin.log.phase', { value: currentPhase }) : null,
    lastKnownStep && lastKnownStep !== currentPhase ? t('admin.log.lastStep', { value: lastKnownStep }) : null,
    domain ? t('admin.log.domain', { value: domain }) : null,
    officialDomain ? t('admin.log.officialDomain', { value: officialDomain }) : null,
    reason ? t('admin.log.reason', { value: reason }) : null,
    elapsedSeconds !== undefined ? t('admin.log.elapsedSeconds', { value: elapsedSeconds }) : null,
    timeoutSeconds !== undefined ? t('admin.log.timeoutSeconds', { value: timeoutSeconds }) : null,
    score !== undefined ? t('admin.log.score', { value: score }) : null,
    extension ? t('admin.log.extension', { value: extension }) : null,
    assetKind ? t('admin.log.type', { value: assetKind }) : null,
    source ? t('admin.log.source', { value: source }) : null,
    statement ? t('admin.log.sqlStatement') : null,
    isPrimary !== undefined ? (isPrimary ? t('admin.log.primary') : t('admin.log.alternate')) : null,
  ].filter(Boolean);
  if (log.message && details.length) return `${log.message} - ${details.join('; ')}`;
  if (details.length) return details.join('; ');
  return log.message || t('admin.log.noDetails');
}

function parseSafeMetadata(value?: string | null): Record<string, unknown> {
  if (!value) return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {};
  } catch {
    return {};
  }
}

function metadataString(metadata: Record<string, unknown>, key: string): string | undefined {
  const value = metadata[key];
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function metadataNumber(metadata: Record<string, unknown>, key: string): number | undefined {
  const value = metadata[key];
  return typeof value === 'number' ? value : undefined;
}

function metadataBoolean(metadata: Record<string, unknown>, key: string): boolean | undefined {
  const value = metadata[key];
  return typeof value === 'boolean' ? value : undefined;
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
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

