import { ChevronDown } from 'lucide-react';
import { useState, useSyncExternalStore } from 'react';
import { Link } from 'react-router-dom';
import { fetchApps } from '../../api/catalogApps';
import { AsyncContent, LoadingPlaceholder } from '../../components/AsyncContent';
import { DeferredSection } from '../../components/DeferredSection';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { AppMiniIcon } from '../../components/AppMiniIcon';
import { BundleCard } from '../../components/BundleCard';
import { useBundleResults } from '../../hooks/useBundleResults';
import { useTranslation } from '../../services/i18n';
import type { CatalogApp } from '../../types/catalog';

// Los mismos umbrales de columnas que .bundle-grid en responsive.css.
const BUNDLE_BREAKPOINTS = ['(max-width: 640px)', '(max-width: 1180px)'];

function subscribeBundleLayout(onChange: () => void) {
  const queries = BUNDLE_BREAKPOINTS.map((query) => window.matchMedia?.(query));
  queries.forEach((query) => query?.addEventListener('change', onChange));
  return () => queries.forEach((query) => query?.removeEventListener('change', onChange));
}

function getBundleColumns() {
  const [mobile, compact] = BUNDLE_BREAKPOINTS.map((query) => window.matchMedia?.(query).matches);
  return mobile ? 1 : compact ? 2 : 3;
}

export function HomePage() {
  const t = useTranslation();
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
      <BundleSection
        title={t('home.bundleOfficial')}
        type="official"
      />
      <DeferredSection fallback={<LoadingPlaceholder className="home-bundles-placeholder" />}>
        <BundleSection title={t('home.bundleCommunity')} type="community" />
      </DeferredSection>
      <DeferredSection fallback={<LoadingPlaceholder className="home-apps-placeholder" />}><RecentAppsSection /></DeferredSection>
    </main>
  );
}

function BundleSection({ title, type }: { title: string; type: 'official' | 'community' }) {
  const t = useTranslation();
  const [sort, setSort] = useState('updated');
  const columns = useSyncExternalStore(subscribeBundleLayout, getBundleColumns, () => 3);
  const { response, error, loading, refresh, scope } = useBundleResults({ type, pageSize: 3, sort });
  const visibleBundles = response?.data.slice(0, columns) ?? [];
  const total = response?.total ?? 0;
  return <section className="home-section">
    <div className="section-heading">
      <h2>{title}</h2>
      <div className="bundle-section-tools">
        <label className="bundle-sort-selector"><span className="sr-only">{t('bundle.catalog.orderSection', { title })}</span>
          <select value={sort} onChange={(event) => setSort(event.target.value)}>
            <option value="downloads">{t('bundle.sort.downloads')}</option>
            <option value="updated">{t('bundle.sort.updated')}</option>
            <option value="stars">{t('bundle.sort.stars')}</option>
            <option value="name">{t('bundle.sort.name')}</option>
          </select>
          <ChevronDown className="sort-menu-chevron" size={16} aria-hidden="true" />
        </label>
        {total > visibleBundles.length ? <Link to={`/catalog/bundles?bundleType=${type === 'official' ? 'official' : 'public'}&sort=${sort}`}>
          {t('common.viewAll')}</Link> : null}
      </div>
    </div>
    <AsyncContent loading={loading} error={error} hasData={Boolean(response)} onRetry={refresh} errorMessage={t('home.loadError')}
      placeholder={<LoadingPlaceholder className="bundle-card-placeholder" />}>
      <div className="bundle-grid">
        {visibleBundles.map((bundle) => <BundleCard bundle={bundle} key={`${bundle.id}:${scope}`}
          onStarChange={sort === 'stars' ? refresh : undefined} />)}
        {!visibleBundles.length ? <p className="empty-state">{t('home.emptyBundles')}</p> : null}
      </div>
    </AsyncContent>
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

function RecentAppsSection() {
  const t = useTranslation();
  const resource = useAsyncResource((signal) => fetchApps(
    { query: '', filter: 'available', sort: 'updated', page: 1, pageSize: 6 }, signal,
  ));
  const apps = resource.data?.data ?? [];
  return <section className="home-section">
    <div className="section-heading">
      <h2>{t('home.appsRecent')}</h2>
      {apps.length > 5 ? <Link to="/catalog?sort=updated">{t('common.viewAll')}</Link> : null}
    </div>
    <AsyncContent {...resource} hasData={resource.data !== undefined} onRetry={resource.refresh} errorMessage={t('home.loadError')}>
      <div className="app-compact-grid">{apps.map((app) => <AppCompactCard app={app} key={app.id} />)}</div>
      {!apps.length ? <p className="empty-state">{t('catalog.empty')}</p> : null}
    </AsyncContent>
  </section>;
}
