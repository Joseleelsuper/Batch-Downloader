import {
  Boxes,
  Clock3,
  Download,
  ExternalLink,
  FolderPlus,
  LockKeyhole,
  Save,
  Trash2,
  UserRound,
} from 'lucide-react';
import {
  type FormEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  Link,
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import {
  confirmEmail,
  createOwnBundle,
  deleteOwnBundle,
  fetchDashboard,
  fetchOwnBundle,
  fetchOwnBundles,
  registerAccount,
  requestPasswordReset,
  resendVerification,
  resetPassword,
  updateOwnBundle,
  updateProfile,
} from '../../api/account';
import { adminLogin, login } from '../../api/account';
import { fetchApps } from '../../api/catalogApps';
import { ApiRequestError } from '../../api/http';
import { useAuth } from '../../auth/AuthContext';
import { useTranslation, type Translator } from '../../services/i18n';
import type { AccountDashboard, OwnBundleDetails, OwnBundleInput, OwnBundleSummary } from '../../types/account';
import type { CatalogApp } from '../../types/catalog';

function apiMessage(t: Translator, error: unknown, fallbackKey: string): string {
  if (error instanceof ApiRequestError) {
    const key = `account.error.${error.code}`;
    const translated = t(key);
    if (translated !== key) return translated;
  }
  return t(fallbackKey);
}

function fieldMessage(error: unknown, field: string): string | null {
  if (!(error instanceof ApiRequestError)) return null;
  const fieldErrors = error.details.fieldErrors;
  if (!fieldErrors || typeof fieldErrors !== 'object') return null;
  const messages = (fieldErrors as Record<string, unknown>)[field];
  if (Array.isArray(messages) && typeof messages[0] === 'string') return messages[0];
  if (typeof messages === 'string') return messages;
  return null;
}

function AuthCard({ children }: Readonly<{ children: React.ReactNode }>) {
  return <main className="login-page"><section className="login-card auth-card">{children}</section></main>;
}

export function UserLoginPage() {
  const t = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [search] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const destination = destinationFrom(location.state, '/dashboard');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const account = await login(email, password);
      auth.setAuthenticated(account);
      navigate(destination, { replace: true });
    } catch (cause) {
      setError(apiMessage(t, cause, 'account.login.invalid'));
    } finally {
      setSubmitting(false);
    }
  }

  function google() {
    window.location.assign(`/api/v1/auth/oauth2/google?returnTo=${encodeURIComponent(destination)}`);
  }

  return (
    <AuthCard>
      <h2>{t('account.login.title')}</h2>
      {search.get('oauthError') ? <p className="error-banner">{t('account.login.oauthError')}</p> : null}
      <form className="auth-form" onSubmit={submit} noValidate>
        <label>{t('account.email')}
          <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="email" required />
        </label>
        <label>{t('login.password')}
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required />
        </label>
        {error ? <p className="error-banner">{error}</p> : null}
        <button className="primary-button" type="submit" disabled={submitting}>{submitting ? t('account.sending') : t('login.submit')}</button>
        <button type="button" className="secondary-button google-auth-button" onClick={google} disabled={submitting}>
          <img className="google-auth-icon" src="/assets/google-g.png" alt="" aria-hidden="true" />
          <span>{t('account.login.google')}</span>
        </button>
      </form>
      <div className="auth-links">
        <Link to="/register">{t('account.register.link')}</Link>
        <Link to="/forgot-password">{t('account.forgot.link')}</Link>
      </div>
      <Link className="auth-admin-link" to="/admin/login">{t('account.adminLogin.link')}</Link>
    </AuthCard>
  );
}

export function AdminLoginPage() {
  const t = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const destination = destinationFrom(location.state, '/admin');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const account = await adminLogin(username, password);
      auth.setAuthenticated(account);
      navigate(destination, { replace: true });
    } catch (cause) {
      setError(apiMessage(t, cause, 'login.invalid'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard>
      <h2>{t('account.adminLogin.title')}</h2>
      <form className="auth-form" onSubmit={submit}>
        <label>{t('login.username')}
          <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required />
        </label>
        <label>{t('login.password')}
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required />
        </label>
        {error ? <p className="error-banner">{error}</p> : null}
        <button className="primary-button" type="submit" disabled={submitting}>{submitting ? t('account.sending') : t('login.submit')}</button>
      </form>
      <Link to="/login">{t('account.userLogin.link')}</Link>
    </AuthCard>
  );
}

export function RegisterPage() {
  const t = useTranslation();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    if (password !== confirmation) {
      setError(t('account.password.mismatch'));
      return;
    }
    if (new TextEncoder().encode(password).length > 72) {
      setError(t('account.password.tooLong'));
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await registerAccount(email, password);
      navigate('/verify-email', { replace: true, state: { email } });
    } catch (cause) {
      setError(fieldMessage(cause, 'email') ?? fieldMessage(cause, 'password')
        ?? apiMessage(t, cause, 'account.register.failed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard>
      <h2>{t('account.register.title')}</h2>
      <form className="auth-form" onSubmit={submit}>
        <label>{t('account.email')}<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="email" required /></label>
        <label>{t('login.password')}<input type="password" minLength={12} value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" required /></label>
        <label>{t('account.password.confirm')}<input type="password" minLength={12} value={confirmation} onChange={(event) => setConfirmation(event.target.value)} autoComplete="new-password" required /></label>
        <small>{t('account.password.help')}</small>
        {error ? <p className="error-banner">{error}</p> : null}
        <button className="primary-button" type="submit" disabled={submitting}>{submitting ? t('account.sending') : t('account.register.submit')}</button>
      </form>
      <Link to="/login">{t('account.login.link')}</Link>
    </AuthCard>
  );
}

export function VerifyEmailPage() {
  const t = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const [token] = useState(() => new URLSearchParams(location.search).get('token'));
  const processedToken = useRef<string | null>(null);
  const [email, setEmail] = useState((location.state as { email?: string } | null)?.email ?? '');
  const [state, setState] = useState<'waiting' | 'checking' | 'success' | 'error'>(token ? 'checking' : 'waiting');
  const [message, setMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!location.search) return;
    navigate('/verify-email', { replace: true, state: location.state });
  }, [location.search, location.state, navigate]);

  useEffect(() => {
    if (!token || processedToken.current === token) return;
    processedToken.current = token;
    void confirmEmail(token)
      .then(() => setState('success'))
      .catch((cause) => {
        setMessage(apiMessage(t, cause, 'account.verify.failed'));
        setState('error');
      });
  }, [t, token]);

  async function resend(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setMessage(null);
    try {
      await resendVerification(email);
      setMessage(t('account.verify.resent'));
    } catch (cause) {
      setMessage(apiMessage(t, cause, 'account.verify.resendFailed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard>
      <h2>{t('account.verify.title')}</h2>
      {state === 'checking' ? <p>{t('account.verify.checking')}</p> : null}
      {state === 'success' ? <><p className="form-message">{t('account.verify.success')}</p><Link to="/login">{t('account.login.link')}</Link></> : null}
      {state === 'error' ? <p className="error-banner">{message}</p> : null}
      {state === 'waiting' || state === 'error' ? (
        <form className="auth-form" onSubmit={resend}>
          <p>{t('account.verify.instructions')}</p>
          <label>{t('account.email')}<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="email" required /></label>
          <button type="submit" className="secondary-button" disabled={submitting}>{t('account.verify.resend')}</button>
          {state === 'waiting' && message ? <p className="form-message">{message}</p> : null}
        </form>
      ) : null}
    </AuthCard>
  );
}

export function ForgotPasswordPage() {
  const t = useTranslation();
  const [email, setEmail] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setMessage(null);
    try {
      await requestPasswordReset(email);
      setMessage(t('account.forgot.sent'));
    } catch (cause) {
      setMessage(apiMessage(t, cause, 'account.forgot.failed'));
    } finally {
      setSubmitting(false);
    }
  }
  return <AuthCard><h2>{t('account.forgot.title')}</h2><form className="auth-form" onSubmit={submit}>
    <label>{t('account.email')}<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="email" required /></label>
    <button className="primary-button" type="submit" disabled={submitting}>{t('account.forgot.submit')}</button>
    {message ? <p className="form-message">{message}</p> : null}
  </form><Link to="/login">{t('account.login.link')}</Link></AuthCard>;
}

export function ResetPasswordPage() {
  const t = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const [token] = useState(() => new URLSearchParams(location.search).get('token'));
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [complete, setComplete] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (location.search) navigate('/reset-password', { replace: true });
  }, [location.search, navigate]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting || !token) return;
    if (password !== confirmation) {
      setError(t('account.password.mismatch'));
      return;
    }
    if (new TextEncoder().encode(password).length > 72) {
      setError(t('account.password.tooLong'));
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await resetPassword(token, password);
      setComplete(true);
    } catch (cause) {
      setError(apiMessage(t, cause, 'account.reset.failed'));
    } finally {
      setSubmitting(false);
    }
  }

  return <AuthCard><h2>{t('account.reset.title')}</h2>
    {!token && !complete ? <p className="error-banner">{t('account.reset.missingToken')}</p> : null}
    {complete ? <><p className="form-message">{t('account.reset.success')}</p><Link to="/login">{t('account.login.link')}</Link></> : null}
    {token && !complete ? <form className="auth-form" onSubmit={submit}>
      <label>{t('login.password')}<input type="password" minLength={12} value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" required /></label>
      <label>{t('account.password.confirm')}<input type="password" minLength={12} value={confirmation} onChange={(event) => setConfirmation(event.target.value)} autoComplete="new-password" required /></label>
      {error ? <p className="error-banner">{error}</p> : null}
      <button className="primary-button" type="submit" disabled={submitting}>{t('account.reset.submit')}</button>
    </form> : null}
  </AuthCard>;
}

export function AccountLayout() {
  const t = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  async function signOut() {
    await auth.signOut();
    navigate('/login', { replace: true });
  }
  return <div className="account-shell">
    <aside className="account-nav">
      <Link className="brand" to="/"><img className="brand-icon" src="/assets/icon.ico" alt="" /><span>Batch Downloader</span></Link>
      <nav>
        <NavLink to="/dashboard" end><Clock3 size={18} />{t('account.nav.dashboard')}</NavLink>
        <NavLink to="/dashboard/bundles"><Boxes size={18} />{t('account.nav.bundles')}</NavLink>
        <NavLink to="/profile"><UserRound size={18} />{t('account.nav.profile')}</NavLink>
      </nav>
      <button type="button" onClick={signOut}>{t('nav.logout')}</button>
    </aside>
    <main className="account-content"><Outlet /></main>
  </div>;
}

export function DashboardPage() {
  const t = useTranslation();
  const [dashboard, setDashboard] = useState<AccountDashboard | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    fetchDashboard().then(setDashboard).catch((cause) => setError(apiMessage(t, cause, 'account.dashboard.failed')));
  }, [t]);
  if (error) return <section><p className="error-banner">{error}</p></section>;
  if (!dashboard) return <p className="loading-label">{t('account.loading')}</p>;
  return <section className="account-page">
    <header><div><h2>{t('account.dashboard.title', { username: dashboard.account.username })}</h2><p>{t('account.dashboard.subtitle')}</p></div></header>
    <div className="account-stats">
      <Stat label={t('account.dashboard.bundles')} value={dashboard.counts.bundles} icon={<Boxes />} />
      <Stat label={t('account.dashboard.public')} value={dashboard.counts.publicBundles} icon={<ExternalLink />} />
      <Stat label={t('account.dashboard.private')} value={dashboard.counts.privateBundles} icon={<LockKeyhole />} />
      <Stat label={t('account.dashboard.downloads')} value={dashboard.counts.downloads} icon={<Download />} />
    </div>
    <div className="account-grid">
      <section className="account-card"><h3>{t('account.dashboard.recentDownloads')}</h3><HistoryList items={dashboard.recentDownloads} /></section>
      <section className="account-card"><h3>{t('account.dashboard.recentBundles')}</h3><BundleList items={dashboard.recentBundles} /></section>
    </div>
  </section>;
}

function Stat({ label, value, icon }: { label: string; value: number; icon: React.ReactNode }) {
  return <article className="account-stat"><span>{icon}</span><strong>{value}</strong><small>{label}</small></article>;
}

function HistoryList({ items }: { items: AccountDashboard['recentDownloads'] }) {
  const t = useTranslation();
  if (!items.length) return <p className="empty-state">{t('account.downloads.empty')}</p>;
  return <ul className="account-list">{items.map((item) => <li key={`${item.jobId}-${item.appId}`}>
    {item.iconUrl ? <img src={item.iconUrl} alt="" /> : <Download size={20} />}
    <span><strong>{item.appName}</strong><small>{new Date(item.downloadedAt).toLocaleString('es-ES')}</small></span>
  </li>)}</ul>;
}

function BundleList({ items }: { items: OwnBundleSummary[] }) {
  const t = useTranslation();
  if (!items.length) return <p className="empty-state">{t('account.bundles.empty')}</p>;
  return <ul className="account-list">{items.map((bundle) => <li key={bundle.id}>
    <Boxes size={20} /><span><Link to={`/dashboard/bundles/${bundle.id}/edit`}>{bundle.name}</Link><small>{bundle.appCount} · {bundle.visibility}</small></span>
  </li>)}</ul>;
}

export function AccountBundlesPage() {
  const t = useTranslation();
  const [bundles, setBundles] = useState<OwnBundleSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    fetchOwnBundles().then((response) => setBundles(response.data)).catch((cause) => setError(apiMessage(t, cause, 'account.bundles.failed')));
  }, [t]);
  return <section className="account-page"><header><div><h2>{t('account.bundles.title')}</h2><p>{t('account.bundles.subtitle')}</p></div>
    <Link className="primary-button" to="/dashboard/bundles/new"><FolderPlus size={18} />{t('account.bundles.new')}</Link></header>
    {error ? <p className="error-banner">{error}</p> : <BundleList items={bundles} />}
  </section>;
}

export function BundleEditorPage() {
  const t = useTranslation();
  const { id } = useParams();
  const editing = Boolean(id);
  const navigate = useNavigate();
  const [bundle, setBundle] = useState<OwnBundleDetails | null>(null);
  const [catalog, setCatalog] = useState<CatalogApp[]>([]);
  const [query, setQuery] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [slug, setSlug] = useState('');
  const [tags, setTags] = useState('');
  const [visibility, setVisibility] = useState<'private' | 'public'>('private');
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!id) return;
    fetchOwnBundle(id).then((value) => {
      setBundle(value); setName(value.name); setDescription(value.description ?? '');
      setSlug(value.slug); setTags(value.tags.join(', ')); setVisibility(value.visibility);
      setSelected(value.apps.map((app) => app.id));
    }).catch((cause) => setError(apiMessage(t, cause, 'account.bundles.failed')));
  }, [id, t]);

  useEffect(() => {
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      void fetchApps({ query, filter: 'available', sort: 'name', page: 1, pageSize: 60 }, controller.signal)
        .then((response) => setCatalog(response.data)).catch(() => setCatalog([]));
    }, 180);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [query]);

  const selectedSet = useMemo(() => new Set(selected), [selected]);
  function toggle(appId: string) {
    setSelected((current) => current.includes(appId)
      ? current.filter((value) => value !== appId)
      : current.length < 100 ? [...current, appId] : current);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true); setError(null);
    const input: OwnBundleInput = {
      name, description, slug,
      tags: tags.split(',').map((value) => value.trim()).filter(Boolean),
      appIds: selected,
    };
    try {
      const saved = editing && id && bundle
        ? await updateOwnBundle(id, { ...input, visibility, expectedVersion: bundle.version })
        : await createOwnBundle(input);
      navigate(`/dashboard/bundles/${saved.id}/edit`, { replace: true });
      setBundle(saved);
    } catch (cause) {
      setError(apiMessage(t, cause, 'account.bundles.saveFailed'));
    } finally {
      setSubmitting(false);
    }
  }

  async function remove() {
    if (!id || !window.confirm(t('account.bundles.deleteConfirm'))) return;
    setSubmitting(true);
    try { await deleteOwnBundle(id); navigate('/dashboard/bundles', { replace: true }); }
    catch (cause) { setError(apiMessage(t, cause, 'account.bundles.deleteFailed')); setSubmitting(false); }
  }

  return <section className="account-page"><header><div><h2>{editing ? t('account.bundles.edit') : t('account.bundles.create')}</h2><p>{t('account.bundles.privateDefault')}</p></div></header>
    <form className="bundle-account-editor" onSubmit={submit}>
      <div className="account-card bundle-fields">
        <label>{t('account.bundles.name')}<input value={name} onChange={(event) => setName(event.target.value)} required maxLength={160} /></label>
        <label>{t('account.bundles.slug')}<input value={slug} onChange={(event) => setSlug(event.target.value)} maxLength={180} /></label>
        <label>{t('account.bundles.description')}<textarea value={description} onChange={(event) => setDescription(event.target.value)} maxLength={4000} /></label>
        <label>{t('account.bundles.tags')}<input value={tags} onChange={(event) => setTags(event.target.value)} /></label>
        {editing ? <label>{t('account.bundles.visibility')}<select value={visibility} onChange={(event) => setVisibility(event.target.value as 'private' | 'public')}><option value="private">{t('account.private')}</option><option value="public">{t('account.public')}</option></select></label> : null}
      </div>
      <div className="account-card bundle-catalog-picker"><h3>{t('account.bundles.apps')}</h3><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t('account.bundles.searchApps')} />
        <small>{t('account.bundles.selected', { count: selected.length })}</small>
        <div>{catalog.map((app) => <label key={app.id}><input type="checkbox" checked={selectedSet.has(app.id)} onChange={() => toggle(app.id)} />{app.iconUrl ? <img src={app.iconUrl} alt="" /> : null}<span>{app.name}</span></label>)}</div>
      </div>
      {error ? <p className="error-banner">{error}</p> : null}
      <div className="editor-actions"><button className="primary-button" type="submit" disabled={submitting}><Save size={18} />{t('account.save')}</button>
        {editing ? <button className="danger-button" type="button" onClick={remove} disabled={submitting}><Trash2 size={18} />{t('account.delete')}</button> : null}</div>
    </form>
  </section>;
}

export function ProfilePage() {
  const t = useTranslation();
  const auth = useAuth();
  const [username, setUsername] = useState(auth.account?.username ?? '');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true); setMessage(null); setError(null);
    try {
      const changed = await updateProfile(username);
      auth.setAuthenticated(changed);
      setMessage(t('account.profile.saved'));
    } catch (cause) { setError(apiMessage(t, cause, 'account.profile.failed')); }
    finally { setSubmitting(false); }
  }
  return <section className="account-page"><header><div><h2>{t('account.profile.title')}</h2><p>{auth.account?.email}</p></div></header>
    <form className="account-card profile-form" onSubmit={submit}><label>{t('login.username')}<input value={username} onChange={(event) => setUsername(event.target.value)} minLength={3} maxLength={40} autoComplete="username" required /></label>
      <small>{t('account.profile.providers', { providers: auth.account?.authenticationMethods.join(', ') ?? '' })}</small>
      {error ? <p className="error-banner">{error}</p> : null}{message ? <p className="form-message">{message}</p> : null}
      <button className="primary-button" type="submit" disabled={submitting}><Save size={18} />{t('account.save')}</button></form>
  </section>;
}

function destinationFrom(state: unknown, fallback: string): string {
  if (!state || typeof state !== 'object') return fallback;
  const from = (state as { from?: unknown }).from;
  if (!from || typeof from !== 'object') return fallback;
  const pathname = (from as { pathname?: unknown }).pathname;
  const search = (from as { search?: unknown }).search;
  if (typeof pathname !== 'string' || !pathname.startsWith('/') || pathname.startsWith('//')) return fallback;
  return pathname + (typeof search === 'string' ? search : '');
}
