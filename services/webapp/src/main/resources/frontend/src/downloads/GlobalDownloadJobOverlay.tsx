import { AlertTriangle, Download, X } from 'lucide-react';
import { DownloadJobPanel } from '../components/DownloadJobPanel';
import { t } from '../services/i18n';
import { useDownloadJobs } from './DownloadJobsContext';

export function GlobalDownloadJobOverlay() {
  const {
    jobs,
    startError,
    cancel,
    dismiss,
    toggleMinimized,
    clearStartError,
  } = useDownloadJobs();

  if (!jobs.length && !startError) return null;

  return (
    <aside className="download-job-overlay" aria-label={t('download.overlay.title')}>
      <header className="download-job-overlay-header">
        <div>
          <Download size={18} />
          <strong>{t('download.overlay.title')}</strong>
        </div>
        {jobs.length ? <span>{jobs.length}</span> : null}
      </header>
      {startError ? (
        <div className="download-job-start-error" role="alert">
          <AlertTriangle size={17} />
          <p>{startError}</p>
          <button className="icon-action" type="button" onClick={clearStartError} aria-label={t('common.close')}>
            <X size={16} />
          </button>
        </div>
      ) : null}
      <div className="download-job-overlay-list">
        {jobs.map((entry) => entry.job ? (
          <DownloadJobPanel
            key={entry.id}
            job={entry.job}
            label={entry.label}
            minimized={entry.minimized}
            cancelling={entry.cancelling}
            connectionError={entry.connectionError}
            actionError={entry.actionError}
            autoDownloadAttempted={entry.autoDownloadAttempted}
            onCancel={() => void cancel(entry.id).catch(() => undefined)}
            onClose={() => dismiss(entry.id)}
            onToggleMinimized={() => toggleMinimized(entry.id)}
          />
        ) : (
          <section className="download-job-panel download-job-panel-restoring" key={entry.id} aria-live="polite">
            <div className="download-job-heading">
              <div>
                <strong>{entry.label}</strong>
                <span>{entry.connectionError
                  ? t('download.job.connectionLost')
                  : t('download.job.restoring')}</span>
              </div>
            </div>
            <progress aria-label={t('download.job.restoring')} />
          </section>
        ))}
      </div>
    </aside>
  );
}
