import {
  Ban,
  ChevronDown,
  ChevronUp,
  ExternalLink,
  FileDown,
  X,
} from 'lucide-react';
import { useState } from 'react';
import {
  DOWNLOADABLE_DOWNLOAD_STATUSES,
  TERMINAL_DOWNLOAD_STATUSES,
} from '../downloads/DownloadJobsContext';
import { useTranslation } from '../services/i18n';
import type { DownloadJob } from '../types/catalog';
import { explainFailure, type DownloadActionError } from '../downloads/failures';

interface Props {
  job: DownloadJob;
  label?: string;
  minimized?: boolean;
  cancelling?: boolean;
  connectionError?: boolean;
  actionError?: DownloadActionError | null;
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

const VOLUNTARY_CODES = new Set(['download_job_cancelled', 'linux_selection_cancelled']);
const AGGREGATE_CODES = new Set(['all_items_failed', 'all_downloads_failed']);

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
  const [issuesOpen, setIssuesOpen] = useState(false);
  const completed = job.items.filter((item) => item.status === 'COMPLETED').length;
  const manualItems = job.items.filter(
    (item) => item.status === 'FAILED' && item.errorCode === MANUAL_DOWNLOAD_ERROR,
  );
  const failedItems = job.items.filter(
    (item) => item.status === 'FAILED' && item.errorCode !== MANUAL_DOWNLOAD_ERROR
      && !VOLUNTARY_CODES.has(item.errorCode ?? '')
      && !(job.status === 'CANCELLED' && item.errorCode === 'download_interrupted'),
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
  const issues = failedItems.map((item) => ({
    id: item.id,
    title: item.appName || item.appId,
    explanation: explainFailure(t, item.errorCode),
    officialPageUrl: safeOfficialPageUrl(item.officialPageUrl),
    application: true,
  }));
  if (job.failureCode && job.failureCode !== MANUAL_DOWNLOAD_ERROR
      && !VOLUNTARY_CODES.has(job.failureCode)
      && !(failed > 0 && AGGREGATE_CODES.has(job.failureCode))) {
    issues.push({ id: 'job', title: t('download.job.globalFailure'),
      explanation: explainFailure(t, job.failureCode), officialPageUrl: null, application: false });
  }
  if (actionError) {
    issues.push({ id: 'action', title: t(`download.job.issue.operation.${actionError.operation}`),
      explanation: explainFailure(t, actionError.code, actionError.httpStatus, actionError.operation),
      officialPageUrl: null, application: false });
  }
  if (connectionError && !terminal) {
    issues.push({ id: 'connection', title: t('download.job.issue.operation.connection'),
      explanation: explainFailure(t, 'connection_lost'), officialPageUrl: null, application: false });
  }

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
          {issues.length ? (
            <details className="download-job-issues" open={issuesOpen}
              onToggle={(event) => setIssuesOpen(event.currentTarget.open)}>
              <summary>{t('download.job.issues', { count: issues.length })}</summary>
              <ul className="download-job-failures">
                {issues.map(({ id, title, explanation, officialPageUrl, application }) => (
                  <li className="download-job-failure" key={id}>
                    <strong>{title}</strong>
                    <dl className="download-job-issue-details">
                      <div><dt>{t('download.job.issue.what')}</dt><dd>{explanation.what}</dd></div>
                      <div><dt>{t('download.job.issue.cause')}</dt><dd>{explanation.cause}</dd></div>
                      <div><dt>{t('download.job.issue.recovery')}</dt><dd>{explanation.recovery}</dd></div>
                      <div><dt>{t('download.job.issue.code')}</dt><dd><code>{explanation.code}</code></dd></div>
                      {explanation.httpStatus != null ? (
                        <div><dt>{t('download.job.issue.http')}</dt><dd>{explanation.httpStatus}</dd></div>
                      ) : null}
                    </dl>
                    {officialPageUrl ? (
                      <a href={officialPageUrl} target="_blank" rel="noopener noreferrer">
                        <ExternalLink size={14} aria-hidden="true" />
                        {t('download.job.openOfficialPage')}
                      </a>
                    ) : application ? <small>{t('download.job.noOfficialPage')}</small> : null}
                  </li>
                ))}
              </ul>
            </details>
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
