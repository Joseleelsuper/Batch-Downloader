import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { fetchBundle } from '../../api/bundles';
import { isCatalogAppSelectable } from '../../catalogSelection';
import { AppMiniIcon } from '../../components/AppMiniIcon';
import { AppStatusBadge } from '../../components/AppStatusBadge';
import { BundleDownloadButton } from '../../components/BundleDownloadButton';
import { DownloadButton } from '../../components/DownloadButton';
import { OperatingSystemList } from '../../components/OperatingSystemIcons';
import { useTranslation } from '../../services/i18n';
import type { BundleDetails } from '../../types/catalog';

export function BundleDetailPage() {
  const t = useTranslation();
  const { slug } = useParams();
  const [bundle, setBundle] = useState<BundleDetails | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!slug) return;
    fetchBundle(slug)
      .then(setBundle)
      .catch(() => setError(t('bundle.loadError')));
  }, [slug, t]);

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
