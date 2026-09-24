import {
  Ban,
  ChevronDown,
  ChevronUp,
  ExternalLink,
  FileDown,
  WifiOff,
  X,
} from 'lucide-react';
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
  saving?: boolean;
  onDownload?: () => void;
  onCancel: () => void;
  onClose: () => void;
  onToggleMinimized?: () => void;
}

const CANCELLABLE_STATUSES = new Set(['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PACKAGING']);
const TERMINAL_ITEM_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);
const MANUAL_DOWNLOAD_ERROR = 'manual_download_required';

function publicFailureMessage(t: Translator, code?: string | null): string {
  if (!code) return t('download.job.failure.generic');
  const key = `download.job.apiError.${code}`;
  const specific = t(key);
  if (specific !== key) return specific;
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
  saving = false,
  onDownload,
  onCancel,
  onClose,
  onToggleMinimized,
}: Readonly<Props>) {
  const t = useTranslation();
  const completed = job.items.filter((item) => item.status === 'COMPLETED').length;
  const manualItems = job.items.filter(
    (item) => item.status === 'FAILED' && item.errorCode === MANUAL_DOWNLOAD_ERROR,
  );
  const failedItems = job.items.filter(
    (item) => item.status === 'FAILED' && item.errorCode !== MANUAL_DOWNLOAD_ERROR,
  );
  const failed = failedItems.length;
  const pending = job.items.filter((item) => !TERMINAL_ITEM_STATUSES.has(item.status)).length;
  const metrics = [
    { key: 'downloaded', value: completed, tone: 'success' },
    { key: 'manual', value: manualItems.length, tone: 'manual' },
    { key: 'failed', value: failed, tone: 'danger' },
    { key: 'pending', value: pending, tone: 'neutral' },
    { key: 'omitted', value: job.omittedCount, tone: 'neutral' },
  ].filter((metric) => metric.key === 'downloaded' || metric.value > 0);
  const terminal = TERMINAL_DOWNLOAD_STATUSES.has(job.status);
  const downloadable = DOWNLOADABLE_DOWNLOAD_STATUSES.has(job.status);
  const waitKey = `download.job.wait.${job.waitReason}`;
  const waitMessage = job.waitReason && !terminal && t(waitKey) !== waitKey ? t(waitKey) : null;
  const deliveryFinished = job.deliveryStatus === 'SAVED' || job.deliveryStatus === 'CLEANING';
  const statusKey = downloadable && job.deliveryStatus && job.deliveryStatus !== 'WAITING'
    ? `download.job.delivery.${job.deliveryStatus.toLowerCase()}`
    : `download.job.status.${job.status.toLowerCase()}`;
  const progress = downloadable
    ? deliveryFinished ? 100 : job.artifactSizeBytes
      ? Math.min(100, Math.floor((job.deliveryBytes ?? 0) * 100 / job.artifactSizeBytes)) : 0
    : job.progress;

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
          {(!terminal || saving) && onToggleMinimized ? (
            <button
              type="button"
              className="icon-action"
              onClick={onToggleMinimized}
              aria-label={minimized ? t('download.job.expand') : t('download.job.minimize')}
            >
              {minimized ? <ChevronUp size={17} /> : <ChevronDown size={17} />}
            </button>
          ) : null}
          {terminal && !saving ? (
            <button type="button" className="icon-action" onClick={onClose} aria-label={t('common.close')}>
              <X size={17} />
            </button>
          ) : null}
        </div>
      </div>
      <progress max={100} value={progress}
        aria-label={t(downloadable ? 'download.job.deliveryProgress' : 'download.job.preparationProgress')}>
        {progress}%
      </progress>
      {minimized ? null : (
        <>
          {job.queuePosition ? <p>{t('download.job.queuePosition', { position: job.queuePosition })}</p> : null}
          {waitMessage ? <p>{waitMessage}</p> : null}
          {job.estimatedBytes != null ? <p>{t('download.job.estimatedSize', {
            size: `${(job.estimatedBytes / 1024 ** 2).toFixed(1)} MiB`,
          })}</p> : null}
          <dl className="download-job-metrics" aria-label={t('download.job.metrics')}>
            {metrics.map((metric) => (
              <div data-tone={metric.tone} key={metric.key}>
                <dd>{metric.value}</dd>
                <dt>{t(`download.job.metric.${metric.key}`)}</dt>
              </div>
            ))}
          </dl>
          {job.linux && downloadable && completed > 0 ? (
            <details>
              <summary>{t('linux.download.usage')}</summary>
              <p>{t('linux.download.extract')}</p>
              <p><code>bash install.sh</code></p>
              <p><code>bash uninstall.sh</code></p>
              <p>{t('linux.download.persisted')}</p>
            </details>
          ) : null}
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
          {manualItems.length ? (
            <section className="download-job-manual" aria-label={t('download.job.manualApps')}>
              <div>
                <strong>{t('download.job.manual.title')}</strong>
                <p>{t('download.job.manual.body')}</p>
              </div>
              <ul>
                {manualItems.map((item) => {
                  const officialPageUrl = safeOfficialPageUrl(item.officialPageUrl);
                  return (
                    <li key={item.id}>
                      <span>{item.appName || item.appId}</span>
                      {officialPageUrl ? (
                        <a href={officialPageUrl} target="_blank" rel="noopener noreferrer">
                          <ExternalLink size={14} aria-hidden="true" />
                          {t('download.job.openOfficialPage')}
                        </a>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            </section>
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
                        <ExternalLink size={14} aria-hidden="true" />
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
          {downloadable && autoDownloadAttempted && !saving && !deliveryFinished ? (
            <p className="download-job-auto-notice">{t('download.job.autoAttempted')}</p>
          ) : null}
          <div className="download-job-actions">
            {downloadable && !deliveryFinished ? (
              <button type="button" className="primary-button compact-button" onClick={onDownload} disabled={saving}>
                <FileDown size={17} />
                {t('download.job.getZip')}
              </button>
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
