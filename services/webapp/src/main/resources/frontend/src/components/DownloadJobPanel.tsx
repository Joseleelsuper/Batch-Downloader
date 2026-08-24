import {
  Ban,
  ChevronDown,
  ChevronUp,
  ExternalLink,
  FileDown,
  WifiOff,
  X,
} from 'lucide-react';
import { downloadJobFileUrl } from '../api/downloads';
import {
  DOWNLOADABLE_DOWNLOAD_STATUSES,
  TERMINAL_DOWNLOAD_STATUSES,
} from '../downloads/DownloadJobsContext';
import { useTranslation, type Translator } from '../services/i18n';
import type { DownloadJob } from '../types/catalog';

interface Props {
  job: DownloadJob;
  label?: string;
  minimized?: boolean;
  cancelling?: boolean;
  connectionError?: boolean;
  actionError?: string | null;
  autoDownloadAttempted?: boolean;
  onCancel: () => void;
  onClose: () => void;
  onToggleMinimized?: () => void;
}

const CANCELLABLE_STATUSES = new Set(['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PACKAGING']);

function publicFailureMessage(t: Translator, code?: string | null): string {
  if (!code) return t('download.job.failure.generic');
  if (code.startsWith('source_')) return t('download.job.failure.source');
  if (/(remote|network|dns|redirect|http|io|unavailable)/i.test(code)) {
    return t('download.job.failure.network');
  }
  if (/(checksum|sha|mime|content|signature|mismatch|invalid)/i.test(code)) {
    return t('download.job.failure.validation');
  }
  if (/(size|limit|too_many|budget)/i.test(code)) {
    return t('download.job.failure.limit');
  }
  return t('download.job.failure.generic');
}

function safeOfficialPageUrl(value?: string | null): string | null {
  if (!value || [...value].some((character) => {
    const code = character.charCodeAt(0);
    return code < 32 || code === 127;
  })) return null;
  try {
    const url = new URL(value.trim());
    return url.protocol === 'https:'
      && Boolean(url.hostname)
      && !url.username
      && !url.password
      ? url.toString()
      : null;
  } catch {
    return null;
  }
}

export function DownloadJobPanel({
  job,
  label,
  minimized = false,
  cancelling = false,
  connectionError = false,
  actionError = null,
  autoDownloadAttempted = false,
  onCancel,
  onClose,
  onToggleMinimized,
}: Readonly<Props>) {
  const t = useTranslation();
  const completed = job.items.filter((item) => item.status === 'COMPLETED').length;
  const failedItems = job.items.filter((item) => item.status === 'FAILED');
  const failed = failedItems.length;
  const statusKey = `download.job.status.${job.status.toLowerCase()}`;
  const terminal = TERMINAL_DOWNLOAD_STATUSES.has(job.status);
  const downloadable = DOWNLOADABLE_DOWNLOAD_STATUSES.has(job.status);

  return (
    <section
      className={`download-job-panel ${minimized ? 'download-job-panel-minimized' : ''}`}
      aria-live="polite"
      aria-label={`${t('download.job.title')}: ${label || t('download.job.title')}`}
    >
      <div className="download-job-heading">
        <div>
          <strong title={label}>{label || t('download.job.title')}</strong>
          <span>{t(statusKey)}</span>
        </div>
        <div className="download-job-heading-actions">
          {!terminal && onToggleMinimized ? (
            <button
              type="button"
              className="icon-action"
              onClick={onToggleMinimized}
              aria-label={minimized ? t('download.job.expand') : t('download.job.minimize')}
            >
              {minimized ? <ChevronUp size={17} /> : <ChevronDown size={17} />}
            </button>
          ) : null}
          {terminal ? (
            <button type="button" className="icon-action" onClick={onClose} aria-label={t('common.close')}>
              <X size={17} />
            </button>
          ) : null}
        </div>
      </div>
      <progress max={100} value={job.progress}>{job.progress}%</progress>
      {minimized ? null : (
        <>
          <p className="download-job-summary">
            {t('download.job.summary', {
              completed,
              total: job.acceptedCount || job.items.length,
              failed,
            })}
            {job.omittedCount > 0 ? ` · ${t('download.job.omitted', {
              accepted: job.acceptedCount,
              requested: job.requestedCount,
              omitted: job.omittedCount,
            })}` : null}
          </p>
          {connectionError ? (
            <p className="download-job-connection-warning">
              <WifiOff size={15} />
              {t('download.job.connectionLost')}
            </p>
          ) : null}
          {actionError ? <p className="download-job-action-error">{actionError}</p> : null}
          {job.failureCode ? (
            <div className="download-job-global-failure">
              <strong>{t('download.job.globalFailure')}</strong>
              <p>{publicFailureMessage(t, job.failureCode)}</p>
              <details>
                <summary>{t('download.job.technicalDetails')}</summary>
                <code>{job.failureCode}</code>
              </details>
            </div>
          ) : null}
          {failedItems.length ? (
            <div className="download-job-failures" aria-label={t('download.job.failedApps')}>
              {failedItems.map((item) => {
                const officialPageUrl = safeOfficialPageUrl(item.officialPageUrl);
                return (
                  <article className="download-job-failure" key={item.id}>
                    <div>
                      <strong>{item.appName || item.appId}</strong>
                      <p>{publicFailureMessage(t, item.errorCode)}</p>
                    </div>
                    {officialPageUrl ? (
                      <a href={officialPageUrl} target="_blank" rel="noopener noreferrer">
                        <ExternalLink size={14} />
                        {t('download.job.openOfficialPage')}
                      </a>
                    ) : (
                      <small>{t('download.job.noOfficialPage')}</small>
                    )}
                    {item.errorCode ? (
                      <details>
                        <summary>{t('download.job.technicalDetails')}</summary>
                        <code>{item.errorCode}</code>
                      </details>
                    ) : null}
                  </article>
                );
              })}
            </div>
          ) : null}
          {downloadable && autoDownloadAttempted ? (
            <p className="download-job-auto-notice">{t('download.job.autoAttempted')}</p>
          ) : null}
          <div className="download-job-actions">
            {downloadable ? (
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
        </>
      )}
    </section>
  );
}
