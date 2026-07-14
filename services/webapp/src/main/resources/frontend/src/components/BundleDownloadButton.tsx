import { Download, FileDown } from 'lucide-react';
import { downloadJobFileUrl } from '../api/catalog';
import { useDownloadJob } from '../hooks/useDownloadJob';
import { t } from '../services/i18n';
import { DownloadJobPanel } from './DownloadJobPanel';

interface Props {
  bundleId: string;
  appCount: number;
  compact?: boolean;
}

const ACTIVE_STATUSES = new Set(['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PACKAGING']);

export function BundleDownloadButton({ bundleId, appCount, compact = false }: Readonly<Props>) {
  const { job, starting, cancelling, error, start, cancel, clear } = useDownloadJob();
  const ready = job?.status === 'READY' || job?.status === 'PARTIAL';
  const active = Boolean(job && ACTIVE_STATUSES.has(job.status));
  const overLimit = appCount > 100;

  function startBundle() {
    void start({ bundleId }).catch(() => undefined);
  }

  return (
    <div className={`bundle-download-action ${compact ? 'bundle-download-action-compact' : ''}`}>
      <button
        className="primary-button compact-button"
        type="button"
        disabled={starting || active || overLimit}
        onClick={ready && job ? () => window.location.assign(downloadJobFileUrl(job.id)) : startBundle}
        title={overLimit ? t('bundle.tooLarge') : error ? t('download.job.error') : undefined}
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
