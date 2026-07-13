import { Download } from 'lucide-react';
import { downloadJobFileUrl } from '../api/catalog';
import { useDownloadJob } from '../hooks/useDownloadJob';
import { t } from '../services/i18n';

interface Props {
  appId: string;
  disabled?: boolean;
}

export function DownloadButton({ appId, disabled }: Props) {
  const { job, starting, error, start } = useDownloadJob();
  const ready = job?.status === 'READY' || job?.status === 'PARTIAL';
  const active = Boolean(job && !['READY', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(job.status));

  function handleDownload() {
    if (ready && job) {
      window.location.assign(downloadJobFileUrl(job.id));
      return;
    }
    void start([appId]).catch(() => undefined);
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
