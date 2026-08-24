import { Download } from 'lucide-react';
import { downloadJobFileUrl } from '../api/downloads';
import { useDownloadJob } from '../hooks/useDownloadJob';
import { useTranslation } from '../services/i18n';

interface Props {
  appId: string;
  appName?: string;
  sourceRef?: string;
  disabled?: boolean;
}

export function DownloadButton({ appId, appName, sourceRef, disabled }: Props) {
  const t = useTranslation();
  const { job, starting, error, start } = useDownloadJob();
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
      window.location.assign(downloadJobFileUrl(job.id));
      return;
    }
    void start(
      { appIds: [appId], sourceRef },
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
