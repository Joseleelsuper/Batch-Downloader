import { Boxes } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useTranslation } from '../services/i18n';
import type { BundleSummary } from '../types/catalog';
import { AppMiniIcon } from './AppMiniIcon';
import { BundleDownloadButton } from './BundleDownloadButton';
import { BundleActivity } from './BundleActivity';

export function BundleCard({ bundle, onStarChange }: { bundle: BundleSummary; onStarChange?: () => void }) {
  const t = useTranslation();
  const { account } = useAuth();
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
        {bundle.creatorUsername ? (
          <small className="bundle-card-creator">
            <span className="material-symbols-outlined" aria-hidden="true">person</span>
            {t('bundle.createdBy', { username: ['tory', 'Joseleelsuper'].includes(bundle.creatorUsername) ? 'José Gallardo' : bundle.creatorUsername })}
          </small>
        ) : null}
        <div className="mini-apps bundle-card-preview">
          {visibleApps.map((app) => (
            <AppMiniIcon app={app} key={app.id} />
          ))}
          {hiddenAppCount > 0 ? <span className="mini-more">+{hiddenAppCount}</span> : null}
        </div>
      </Link>
      <BundleActivity key={`${bundle.id}:${account?.id ?? "anonymous"}`} bundle={bundle} onChange={onStarChange} />
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
