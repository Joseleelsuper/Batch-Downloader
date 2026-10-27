import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchBundles } from '../../api/bundles';
import { fetchApps } from '../../api/catalogApps';
import { AppMiniIcon } from '../../components/AppMiniIcon';
import { BundleCard } from '../../components/BundleCard';
import { useAuth } from '../../auth/AuthContext';
import { useTranslation } from '../../services/i18n';
import type { BundleSummary, CatalogApp } from '../../types/catalog';

export function HomePage() {
  const t = useTranslation();
  const [apps, setApps] = useState<CatalogApp[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loadingApps, setLoadingApps] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    const handleError = (requestError: unknown) => {
      if (!cancelled && !isAbortError(requestError)) setError(t('home.loadError'));
    };

    setError(null);
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
  }, [t]);

  return (
    <main className="home-page">
      <section className="home-hero">
        <div>
          <h1>{t('home.hero.title')}</h1>
          <p>{t('home.hero.body')}</p>
        </div>
        <Link className="primary-link" to="/catalog">
          {t('home.hero.cta')}
        </Link>
      </section>
      {error ? <p className="error-banner">{error}</p> : null}
      <BundleSection
        title={t('home.bundleOfficial')}
        type="official"
      />
      <BundleSection
        title={t('home.bundleCommunity')}
        type="community"
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

function BundleSection({ title, type }: { title: string; type: 'official' | 'community' }) {
  const t = useTranslation();
  const { account, status } = useAuth();
  const scope = status === 'checking' ? 'checking' : account?.id ?? 'anonymous';
  const [loadedScope, setLoadedScope] = useState<string | null>(null);
  const [bundles, setBundles] = useState<BundleSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [sort, setSort] = useState('updated');
  const [refresh, setRefresh] = useState(0);
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(true);
  const visibleBundles = loadedScope === scope ? bundles : [];
  useEffect(() => {
    if (scope === 'checking') return;
    const controller = new AbortController();
    setLoading(true);
    setError(false);
    void fetchBundles({ type, pageSize: 3, sort }, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setBundles(result.data);
      setLoadedScope(scope);
      setTotal(result.total);
    }).catch(() => { if (!controller.signal.aborted) setError(true); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [type, sort, refresh, scope]);
  return <section className="home-section">
    <div className="section-heading">
      <h2>{title}</h2>
      <div className="bundle-section-tools">
        <label><span className="sr-only">{t('bundle.catalog.orderSection', { title })}</span>
          <select value={sort} onChange={(event) => setSort(event.target.value)}>
            <option value="updated">{t('bundle.sort.updated')}</option>
            <option value="stars">{t('bundle.sort.stars')}</option>
          </select>
        </label>
        {total > visibleBundles.length ? <Link to={`/catalog/bundles?bundleType=${type === 'official' ? 'official' : 'public'}&sort=${sort}`}>
          {t('common.viewAll')}</Link> : null}
      </div>
    </div>
    {error ? <p className="error-banner" role="alert">{t('home.loadError')}</p> : null}
    <div className="bundle-grid">
      {visibleBundles.map((bundle) => <BundleCard bundle={bundle} key={`${bundle.id}:${scope}`}
        onStarChange={sort === 'stars' ? () => setRefresh((value) => value + 1) : undefined} />)}
      {!visibleBundles.length && !error && (loading || loadedScope !== scope) ? <p className="loading-label">{t('common.loading')}</p>
        : !visibleBundles.length && !error ? <p className="empty-state">{t('home.emptyBundles')}</p> : null}
    </div>
  </section>;
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

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
