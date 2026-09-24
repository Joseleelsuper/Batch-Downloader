import { Download } from 'lucide-react';
import { useDownloadJob } from '../hooks/useDownloadJob';
import { useTranslation } from '../services/i18n';
import type { OperatingSystem } from '../types/catalog';

interface Props {
  appId: string;
  appName?: string;
  sourceRef?: string;
  operatingSystem?: OperatingSystem;
  disabled?: boolean;
}

export function DownloadButton({ appId, appName, sourceRef, operatingSystem, disabled }: Readonly<Props>) {
  const t = useTranslation();
  const { job, starting, error, start, download } = useDownloadJob();
  const ready = Boolean(job && ['READY', 'PARTIAL', 'MANUAL_ONLY'].includes(job.status));
  const active = Boolean(job && ![
    'READY',
    'PARTIAL',
    'MANUAL_ONLY',
    'FAILED',
    'CANCELLED',
    'EXPIRED',
  ].includes(job.status));

  function handleDownload() {
    if (ready && job) {
      void download();
      return;
    }
    void start(
      { appIds: [appId], sourceRef, ...(operatingSystem ? { operatingSystems: [operatingSystem] } : {}) },
      t('download.job.appLabel', { name: appName || appId }),
    ).catch(() => undefined);
  }

  return (
    <button
      className={`download-button ${disabled ? 'download-button-disabled' : ''}`}
      disabled={disabled || starting || active}
      onClick={handleDownload}
      title={error ? t('download.job.error') : undefined}
      type="button"
    >
      <Download size={17} strokeWidth={2.4} />
      <span>
        {starting ? t('download.job.creating') : active ? `${job?.progress ?? 0}%` : ready ? t('download.job.getZip') : t('app.download')}
      </span>
    </button>
  );
}
