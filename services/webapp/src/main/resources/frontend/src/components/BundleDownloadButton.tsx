import { useState } from 'react';
import { Download, FileDown } from 'lucide-react';
import { downloadJobFileUrl } from '../api/catalog';
import { useDownloadJob } from '../hooks/useDownloadJob';
import { t } from '../services/i18n';
import type { OperatingSystem } from '../types/catalog';
import { DownloadJobPanel } from './DownloadJobPanel';
import { OperatingSystemIcon, operatingSystemLabel } from './OperatingSystemIcons';

interface Props {
  bundleId: string;
  appCount: number;
  /** Platforms for which every app in the bundle has a verified installer. */
  operatingSystems: OperatingSystem[];
  compact?: boolean;
}

const ACTIVE_STATUSES = new Set(['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PACKAGING']);

export function BundleDownloadButton({
  bundleId,
  appCount,
  operatingSystems,
  compact = false,
}: Readonly<Props>) {
  const { job, starting, cancelling, error, start, cancel, clear } = useDownloadJob();
  const [selectedOperatingSystem, setSelectedOperatingSystem] = useState<OperatingSystem | null>(null);
  const ready = job?.status === 'READY' || job?.status === 'PARTIAL';
  const active = Boolean(job && ACTIVE_STATUSES.has(job.status));
  const overLimit = appCount > 100;
  const selectedPlatform = selectedOperatingSystem && operatingSystems.includes(selectedOperatingSystem)
    ? selectedOperatingSystem
    : operatingSystems[0] ?? null;
  const hasCompatiblePlatform = selectedPlatform !== null;
  const platformLocked = starting || active || ready;

  function startBundle() {
    if (!selectedPlatform) return;
    void start({ bundleId, operatingSystems: [selectedPlatform] }).catch(() => undefined);
  }

  return (
    <div className={`bundle-download-action ${compact ? 'bundle-download-action-compact' : ''}`}>
      {operatingSystems.length ? (
        <div className="bundle-platform-picker" role="group" aria-label={t('bundle.platforms')}>
          <span>{t('bundle.platforms')}</span>
          <div className="bundle-platform-options">
            {operatingSystems.map((operatingSystem) => {
              const selected = operatingSystem === selectedPlatform;
              return (
                <button
                  key={operatingSystem}
                  className={`bundle-platform-option ${selected ? 'bundle-platform-option-active' : ''}`}
                  type="button"
                  aria-label={operatingSystemLabel(operatingSystem)}
                  aria-pressed={selected}
                  disabled={platformLocked}
                  title={operatingSystemLabel(operatingSystem)}
                  onClick={() => setSelectedOperatingSystem(operatingSystem)}
                >
                  <OperatingSystemIcon operatingSystem={operatingSystem} decorative />
                </button>
              );
            })}
          </div>
        </div>
      ) : (
        <p className="bundle-platform-empty">{t('bundle.noCommonPlatform')}</p>
      )}
      <button
        className="primary-button compact-button"
        type="button"
        disabled={starting || active || overLimit || !hasCompatiblePlatform}
        onClick={ready && job ? () => window.location.assign(downloadJobFileUrl(job.id)) : startBundle}
        title={overLimit
          ? t('bundle.tooLarge')
          : !hasCompatiblePlatform
            ? t('bundle.noCommonPlatform')
            : error
              ? t('download.job.error')
              : undefined}
      >
        {ready ? <FileDown size={17} /> : <Download size={17} />}
        {starting ? t('download.job.creating') : active ? `${job?.progress ?? 0}%` : ready ? t('download.job.getZip') : t('bundle.downloadAll')}
      </button>
      {job ? (
        <DownloadJobPanel job={job} cancelling={cancelling} onCancel={() => void cancel()} onClose={clear} />
      ) : null}
    </div>
  );
}
