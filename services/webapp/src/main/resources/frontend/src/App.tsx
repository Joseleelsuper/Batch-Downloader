import {
  BrainCircuit,
  Boxes,
  ClipboardList,
  Globe2,
  Github,
  Home,
  ListFilter,
  LogOut,
  PackagePlus,
  Play,
  Shield,
  UserCircle,
} from 'lucide-react';
import { Suspense } from 'react';
import { Link, NavLink, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { DownloadJobsProvider } from './downloads/DownloadJobsContext';
import { GlobalDownloadJobOverlay } from './downloads/GlobalDownloadJobOverlay';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { useTranslation } from './services/i18n';
import { lazyNamed } from './routing/lazyNamed';
import {
  AdminAuditPage,
  AdminBundlesPage,
  AdminDashboard,
  AdminRequestsPage,
} from './pages/admin/AdminOverviewPages';
import { CatalogPage, FacetDirectoryPage } from './pages/catalog/CatalogPages';
import { BundleDetailPage } from './pages/public/BundleDetailPage';
import { HomePage } from './pages/public/HomePage';
import type { AuthUser } from './types/catalog';

const AdminAppsWorkbenchPage = lazyNamed(
  () => import('./pages/admin/AdminAppsPage'),
  'AdminAppsPage',
);
const SemanticAiPage = lazyNamed(
  () => import('./pages/admin/SemanticAiPage'),
  'SemanticAiPage',
);
const AdminScraperPage = lazyNamed(
  () => import('./pages/admin/AdminScraperPage'),
  'AdminScraperPage',
);
const AccountLayout = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'AccountLayout',
);
const AccountBundlesPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'AccountBundlesPage',
);
const AdminLoginPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'AdminLoginPage',
);
const BundleEditorPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'BundleEditorPage',
);
const DashboardPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'DashboardPage',
);
const ForgotPasswordPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'ForgotPasswordPage',
);
const ProfilePage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'ProfilePage',
);
const RegisterPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'RegisterPage',
);
const ResetPasswordPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'ResetPasswordPage',
);
const UserLoginPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'UserLoginPage',
);
const VerifyEmailPage = lazyNamed(
  () => import('./pages/account/AccountPages'),
  'VerifyEmailPage',
);

export default function App() {
  return <AuthProvider><AppRoutes /></AuthProvider>;
}

function AppRoutes() {
  const { account: auth, status, signOut } = useAuth();
  const checkingAuth = status === 'checking';
  const onLogout = () => { void signOut(); };

  return (
    <DownloadJobsProvider>
      <Suspense fallback={<RouteLoading />}>
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
      </Suspense>
      <GlobalDownloadJobOverlay />
    </DownloadJobsProvider>
  );
}

function RouteLoading() {
  const t = useTranslation();
  return (
    <main className="public-page">
      <p className="loading-label" role="status" aria-live="polite">
        {t('common.loading')}
      </p>
    </main>
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
  const t = useTranslation();
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

function Footer() {
  const t = useTranslation();
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
            <Globe2 aria-hidden="true" />
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
  const t = useTranslation();
  const knownError = 'unexpected_error';

  return (
    <main className="content-page public-message-page">
      <section className="public-message-card" role="alert">
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
  const t = useTranslation();
  const lastUpdated = new Date(__LEGAL_LAST_UPDATED__);
  const formattedLastUpdated = new Intl.DateTimeFormat('es-ES', {
    dateStyle: 'long',
  }).format(lastUpdated);
  const sections = kind === 'terms'
    ? ['use', 'sources', 'availability']
    : ['data', 'purpose', 'rights'];
  return (
    <main className="content-page legal-page">
      <header className="legal-page-header">
        <span>{t('legal.eyebrow')}</span>
        <h2>{t(`legal.${kind}.title`)}</h2>
        <p>{t(`legal.${kind}.intro`)}</p>
        <p className="legal-last-updated">
          <span>{t('legal.lastUpdated')}</span>
          <time dateTime={lastUpdated.toISOString()}>{formattedLastUpdated}</time>
        </p>
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
  const t = useTranslation();
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
  const t = useTranslation();
  const location = useLocation();
  if (checking) return <main className="content-page"><p className="loading-label">{t('login.checkingSession')}</p></main>;
  if (!auth || auth.role !== 'USER') return <Navigate to="/login" replace state={{ from: location }} />;
  return children;
}

function AdminLayout({ onLogout }: { onLogout: () => void }) {
  const t = useTranslation();
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
