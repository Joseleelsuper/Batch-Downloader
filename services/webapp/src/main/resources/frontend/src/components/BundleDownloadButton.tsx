import { useState } from 'react';
import { Download, FileDown } from 'lucide-react';
import { downloadJobFileUrl } from '../api/catalog';
import { useDownloadJob } from '../hooks/useDownloadJob';
import { t } from '../services/i18n';
import type {
  BundlePlatformAvailability,
  OperatingSystem,
} from '../types/catalog';
import { OperatingSystemIcon, operatingSystemLabel } from './OperatingSystemIcons';

interface Props {
  bundleId: string;
  bundleName?: string;
  appCount: number;
  /** Platforms with at least one installer selected by the same rule as job creation. */
  operatingSystems: OperatingSystem[];
  platformAvailability?: BundlePlatformAvailability[];
  selectedOperatingSystem?: OperatingSystem | null;
  onOperatingSystemChange?: (operatingSystem: OperatingSystem) => void;
  compact?: boolean;
}

const ACTIVE_STATUSES = new Set(['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PACKAGING']);

export function BundleDownloadButton({
  bundleId,
  bundleName,
  appCount,
  operatingSystems,
  platformAvailability = [],
  selectedOperatingSystem,
  onOperatingSystemChange,
  compact = false,
}: Readonly<Props>) {
  const { job, starting, error, start } = useDownloadJob();
  const [internalOperatingSystem, setInternalOperatingSystem] = useState<OperatingSystem | null>(null);
  const availability = platformAvailability.length
    ? platformAvailability
    : operatingSystems.map((operatingSystem) => ({
      operatingSystem,
      downloadableAppCount: appCount,
      previewApps: [],
    }));
  const selectableSystems = availability.map((item) => item.operatingSystem);
  const requestedSelection = selectedOperatingSystem ?? internalOperatingSystem;
  const selectedPlatform = requestedSelection && selectableSystems.includes(requestedSelection)
    ? requestedSelection
    : selectableSystems[0] ?? null;
  const selectedAvailability = availability.find(
    (item) => item.operatingSystem === selectedPlatform,
  );
  const downloadableCount = selectedAvailability?.downloadableAppCount ?? 0;
  const ready = Boolean(job && ['READY', 'PARTIAL', 'MANUAL_ONLY'].includes(job.status));
  const active = Boolean(job && ACTIVE_STATUSES.has(job.status));
  const overLimit = appCount > 100;
  const hasCompatiblePlatform = selectedPlatform !== null && downloadableCount > 0;
  const platformLocked = starting || active || ready;

  function selectPlatform(operatingSystem: OperatingSystem) {
    setInternalOperatingSystem(operatingSystem);
    onOperatingSystemChange?.(operatingSystem);
  }

  function startBundle() {
    if (!selectedPlatform) return;
    void start(
      { bundleId, operatingSystems: [selectedPlatform] },
      t('download.job.bundleLabel', { name: bundleName || bundleId }),
    ).catch(() => undefined);
  }

  return (
    <div className={`bundle-download-action ${compact ? 'bundle-download-action-compact' : ''}`}>
      {availability.length ? (
        <div className="bundle-platform-picker" role="group" aria-label={t('bundle.platforms')}>
          <span>{t('bundle.platforms')}</span>
          <div className="bundle-platform-options">
            {availability.map((item) => {
              const selected = item.operatingSystem === selectedPlatform;
              const label = t('bundle.platformAvailability', {
                platform: operatingSystemLabel(item.operatingSystem),
                count: item.downloadableAppCount,
              });
              return (
                <button
                  key={item.operatingSystem}
                  className={`bundle-platform-option ${selected ? 'bundle-platform-option-active' : ''}`}
                  type="button"
                  aria-label={label}
                  aria-pressed={selected}
                  disabled={platformLocked}
                  title={label}
                  onClick={() => selectPlatform(item.operatingSystem)}
                >
                  <OperatingSystemIcon operatingSystem={item.operatingSystem} decorative />
                  <small>{item.downloadableAppCount}</small>
                </button>
              );
            })}
          </div>
        </div>
      ) : (
        <p className="bundle-platform-empty">{t('bundle.noAvailablePlatform')}</p>
      )}
      <button
        className="primary-button compact-button"
        type="button"
        disabled={starting || active || overLimit || !hasCompatiblePlatform}
        onClick={ready && job ? () => window.location.assign(downloadJobFileUrl(job.id)) : startBundle}
        title={overLimit
          ? t('bundle.tooLarge')
          : !hasCompatiblePlatform
            ? t('bundle.noAvailablePlatform')
            : error
              ? t('download.job.error')
              : undefined}
      >
        {ready ? <FileDown size={17} /> : <Download size={17} />}
        {starting
          ? t('download.job.creating')
          : active
            ? `${job?.progress ?? 0}%`
            : ready
              ? t('download.job.getZip')
              : t('bundle.downloadCount', { count: downloadableCount })}
      </button>
    </div>
  );
}
