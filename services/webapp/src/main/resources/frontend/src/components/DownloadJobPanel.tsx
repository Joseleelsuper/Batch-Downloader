import { Ban, FileDown, X } from 'lucide-react';
import { downloadJobFileUrl } from '../api/catalog';
import { t } from '../services/i18n';
import type { DownloadJob } from '../types/catalog';

interface Props {
  job: DownloadJob;
  cancelling?: boolean;
  onCancel: () => void;
  onClose: () => void;
}

const DOWNLOADABLE_STATUSES = new Set(['READY', 'PARTIAL']);
const CANCELLABLE_STATUSES = new Set(['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PACKAGING']);

export function DownloadJobPanel({ job, cancelling = false, onCancel, onClose }: Readonly<Props>) {
  const completed = job.items.filter((item) => item.status === 'COMPLETED').length;
  const failed = job.items.filter((item) => item.status === 'FAILED').length;
  const statusKey = `download.job.status.${job.status.toLowerCase()}`;

  return (
    <section className="download-job-panel" aria-live="polite" aria-label={t('download.job.title')}>
      <div className="download-job-heading">
        <div>
          <strong>{t('download.job.title')}</strong>
          <span>{t(statusKey)}</span>
        </div>
        <button type="button" className="icon-action" onClick={onClose} aria-label={t('common.close')}>
          <X size={17} />
        </button>
      </div>
      <progress max={100} value={job.progress}>{job.progress}%</progress>
      <p>
        {t('download.job.summary', { completed, total: job.acceptedCount || job.items.length, failed })}
        {job.omittedCount > 0 ? ` · ${t('download.job.omitted', {
          accepted: job.acceptedCount,
          requested: job.requestedCount,
          omitted: job.omittedCount,
        })}` : null}
      </p>
      <div className="download-job-actions">
        {DOWNLOADABLE_STATUSES.has(job.status) ? (
          <a className="primary-button compact-button" href={downloadJobFileUrl(job.id)}>
            <FileDown size={17} />
            {t('download.job.getZip')}
          </a>
        ) : null}
        {CANCELLABLE_STATUSES.has(job.status) ? (
          <button
            className="danger-button compact-button"
            disabled={cancelling}
            onClick={onCancel}
            type="button"
          >
            <Ban size={17} />
            {cancelling ? t('download.job.cancelling') : t('download.job.cancel')}
          </button>
        ) : null}
      </div>
    </section>
  );
}
