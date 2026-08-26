import { Boxes } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchBundles } from '../../api/bundles';
import { fetchApps } from '../../api/catalogApps';
import { AppMiniIcon } from '../../components/AppMiniIcon';
import { BundleDownloadButton } from '../../components/BundleDownloadButton';
import { useTranslation } from '../../services/i18n';
import type { BundleSummary, CatalogApp } from '../../types/catalog';

export function HomePage() {
  const t = useTranslation();
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
  }, [t]);

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
  const t = useTranslation();
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
  const t = useTranslation();
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

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
