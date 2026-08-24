import { RefreshCw, RotateCcw, Square, Trash2, Wand2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import {
  connectScraperEvents,
  createScraperRun,
  enqueueMissingScraperDescriptions,
  fetchAdminCurrentRun,
  fetchAdminLogs,
  fetchAdminMetrics,
  fetchAdminQueues,
  fetchAdminRuns,
  fetchAdminSnapshots,
  pruneTerminalScraperQueueItems,
  recoverStuckScraperQueueItems,
  retryFailedScraperQueueItems,
  sendScraperCommand,
} from '../../api/scraperAdmin';
import { AdminTable } from '../../components/admin/AdminTable';
import { ScraperQueues } from '../../components/admin/ScraperQueues';
import { useTranslation, type Translator } from '../../services/i18n';
import type {
  ResolverLogItem,
  ScraperMetricItem,
  ScraperQueueState,
  ScraperRunSummary,
  ScraperSnapshotItem,
  ScrapeScope,
} from '../../types/catalog';
import { formatDate } from '../../utils/date';

export function AdminScraperPage() {
  const t = useTranslation();
  const [current, setCurrent] = useState<ScraperRunSummary | null>(null);
  const [runs, setRuns] = useState<ScraperRunSummary[]>([]);
  const [logs, setLogs] = useState<ResolverLogItem[]>([]);
  const [queues, setQueues] = useState<ScraperQueueState[]>([]);
  const [metrics, setMetrics] = useState<ScraperMetricItem[]>([]);
  const [snapshots, setSnapshots] = useState<ScraperSnapshotItem[]>([]);
  const [socketState, setSocketState] = useState<'live' | 'reconnecting' | 'offline'>('offline');
  const [message, setMessage] = useState<string | null>(null);
  const [enrichmentAction, setEnrichmentAction] = useState<'descriptions' | null>(null);

  async function load() {
    const [nextCurrent, nextRuns, nextLogs, nextQueues, nextMetrics, nextSnapshots] = await Promise.all([
      fetchAdminCurrentRun(),
      fetchAdminRuns(),
      fetchAdminLogs(),
      fetchAdminQueues(),
      fetchAdminMetrics(),
      fetchAdminSnapshots(),
    ]);
    setCurrent(nextCurrent);
    setRuns(nextRuns);
    setLogs(nextLogs);
    setQueues(nextQueues);
    setMetrics(nextMetrics);
    setSnapshots(nextSnapshots);
  }

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), 10000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => connectScraperEvents((event) => {
    setQueues(event.queues);
    setMetrics(event.metrics);
    setSnapshots(event.snapshots);
  }, setSocketState), []);

  async function command(value: 'pause' | 'resume' | 'stop' | 'force_stop') {
    setMessage(null);
    try {
      await sendScraperCommand(value);
      setMessage(t('admin.message.acceptedCommand'));
      await load();
    } catch {
      setMessage(t('admin.message.sendCommandError'));
    }
  }

  async function requestRun(scope: Exclude<ScrapeScope, 'selected'>) {
    setMessage(null);
    try {
      const request = await createScraperRun(scope);
      setMessage(t('admin.message.acceptedRun', {
        scope: request.scope,
        requestId: request.requestId,
      }));
      await load();
    } catch {
      setMessage(t('admin.message.sendCommandError'));
    }
  }

  async function maintainQueue(action: 'recover_stuck' | 'retry_failed' | 'prune_terminal') {
    setMessage(null);
    try {
      const result = action === 'recover_stuck'
        ? await recoverStuckScraperQueueItems()
        : action === 'retry_failed'
          ? await retryFailedScraperQueueItems()
          : await pruneTerminalScraperQueueItems();
      setMessage(t(`admin.message.queueMaintenance.${action}`, { count: result.affected }));
      await load();
    } catch {
      setMessage(t('admin.message.queueMaintenanceError'));
    }
  }

  async function enqueueMissingDescriptions() {
    setMessage(null);
    setEnrichmentAction('descriptions');
    try {
      const result = await enqueueMissingScraperDescriptions();
      if (result.matched === 0) {
        setMessage(t('admin.message.noMissingDescriptions'));
      } else {
        setMessage(t('admin.message.enrichmentQueued', {
          enqueued: result.enqueued,
          active: result.alreadyActive,
        }));
      }
      await load();
    } catch {
      setMessage(t('admin.message.enrichmentQueueError'));
    } finally {
      setEnrichmentAction(null);
    }
  }

  const controlState = scraperControlState(t, current);

  return (
    <section className="admin-panel">
      <h2>{t('admin.layout.scraper')}</h2>
      <div className="scraper-status admin-card">
        <div>
          <span>{t('admin.scraper.status')}</span>
          <strong>{current?.status ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.scope')}</span>
          <strong>{current?.scope ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.app')}</span>
          <strong>{current?.currentAppName ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.currentPhase')}</span>
          <strong>{current?.currentPhase ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.progress')}</span>
          <strong>{current ? formatScrapeProgress(t, current) : '-'}</strong>
        </div>
      </div>
      <div className="button-row">
        <button className="secondary-button" type="button" disabled={!controlState.pause.enabled} title={controlState.pause.reason} onClick={() => command('pause')}>{t('admin.scraper.pause')}</button>
        <button className="secondary-button" type="button" disabled={!controlState.resume.enabled} title={controlState.resume.reason} onClick={() => command('resume')}>{t('admin.scraper.resume')}</button>
        <button className="secondary-button" type="button" disabled={!controlState.stop.enabled} title={controlState.stop.reason} onClick={() => command('stop')}><Square size={16} />{t('admin.scraper.stop')}</button>
        <button className="secondary-button danger-button" type="button" disabled={!controlState.forceStop.enabled} title={controlState.forceStop.reason} onClick={() => command('force_stop')}><Square size={16} />{t('admin.scraper.forceStop')}</button>
        <button className="primary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('incremental')}>{t('admin.scraper.runIncremental')}</button>
        <button className="secondary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('unresolved')}>{t('admin.scraper.runUnresolved')}</button>
        <button className="secondary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('full')}>{t('admin.scraper.runFull')}</button>
      </div>
      <div className="button-row queue-maintenance-row">
        <button className="secondary-button" type="button" onClick={() => maintainQueue('recover_stuck')}><RotateCcw size={16} />{t('admin.scraper.recoverStuck')}</button>
        <button className="secondary-button" type="button" onClick={() => maintainQueue('retry_failed')}><RefreshCw size={16} />{t('admin.scraper.retryFailed')}</button>
        <button className="secondary-button" type="button" onClick={() => maintainQueue('prune_terminal')}><Trash2 size={16} />{t('admin.scraper.pruneTerminal')}</button>
      </div>
      <div className="button-row queue-maintenance-row">
        <button
          className="secondary-button"
          type="button"
          disabled={enrichmentAction !== null}
          onClick={() => enqueueMissingDescriptions()}
        >
          <Wand2 size={16} />
          {t('admin.scraper.enqueueMissingDescriptions')}
        </button>
      </div>
      {message ? <p className="form-message">{message}</p> : null}
      <div className="scraper-live-line">
        <span>{t('admin.scraper.liveState')}</span>
        <strong>{socketState}</strong>
      </div>
      <ScraperQueues queues={queues} />
      <ScraperMetricsChart metrics={metrics} />
      <ScraperSnapshots snapshots={snapshots} />
      <div className="admin-grid-two">
        <AdminTable
          title={t('admin.scraper.runs')}
          rows={runs.map((run) => [
            run.status,
            `${run.scope} · ${run.targetCount}`,
            formatScrapeProgress(t, run),
            run.currentAppName || run.currentPackageId || t('admin.scraper.noApp'),
            run.currentPhase || run.errorSummary || t('admin.scraper.noPhase'),
            formatDate(run.startedAt),
          ])}
        />
        <AdminTable
          title={t('admin.scraper.logs')}
          rows={logs.map((log) => [
            log.phase,
            log.status,
            formatLogDetails(t, log),
            formatDate(log.createdAt),
          ])}
        />
      </div>
    </section>
  );
}

function ScraperMetricsChart({ metrics }: Readonly<{ metrics: ScraperMetricItem[] }>) {
  const t = useTranslation();
  const width = 720;
  const height = 190;
  const maxValue = Math.max(1, ...metrics.flatMap((item) => [item.available, item.review, item.unavailable]));
  return (
    <div className="admin-card scraper-chart-card">
      <div className="scraper-section-heading">
        <h3>{t('admin.scraper.metrics')}</h3>
      </div>
      {metrics.length ? (
        <svg className="scraper-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={t('admin.scraper.metrics')}>
          <polyline points={metricPoints(metrics, 'available', width, height, maxValue)} className="metric-line metric-line-available" />
          <polyline points={metricPoints(metrics, 'review', width, height, maxValue)} className="metric-line metric-line-review" />
          <polyline points={metricPoints(metrics, 'unavailable', width, height, maxValue)} className="metric-line metric-line-unavailable" />
        </svg>
      ) : <p className="empty-state">{t('admin.table.empty')}</p>}
      <div className="metric-legend">
        <span className="legend-available">{t('catalog.filter.available')}</span>
        <span className="legend-review">{t('catalog.filter.review')}</span>
        <span className="legend-unavailable">{t('catalog.filter.missing')}</span>
      </div>
    </div>
  );
}

function ScraperSnapshots({ snapshots }: Readonly<{ snapshots: ScraperSnapshotItem[] }>) {
  const t = useTranslation();
  const [selectedStage, setSelectedStage] = useState<string | null>(null);
  const selected = snapshots.find((snapshot) => snapshot.stage === selectedStage) ?? snapshots[0];
  useEffect(() => {
    if (!selectedStage && snapshots[0]) setSelectedStage(snapshots[0].stage);
  }, [selectedStage, snapshots]);
  return (
    <div className="admin-card scraper-snapshot-card">
      <div className="scraper-section-heading">
        <h3>{t('admin.scraper.snapshot')}</h3>
        <div className="snapshot-tabs">
          {snapshots.map((snapshot) => (
            <button
              className={snapshot.stage === selected?.stage ? 'snapshot-tab-active' : ''}
              key={snapshot.stage}
              onClick={() => setSelectedStage(snapshot.stage)}
              type="button"
            >
              {snapshot.stage}
            </button>
          ))}
        </div>
      </div>
      {selected ? (
        <>
          <div className="snapshot-meta">
            <span>{selected.appName || selected.packageId || '-'}</span>
            <span>{selected.url || '-'}</span>
          </div>
          <iframe
            className="snapshot-frame"
            sandbox=""
            srcDoc={selected.html || `<p>${t('admin.scraper.snapshotEmpty')}</p>`}
            title={t('admin.scraper.snapshot')}
          />
        </>
      ) : <p className="empty-state">{t('admin.table.empty')}</p>}
    </div>
  );
}

function formatScrapeProgress(t: Translator, run: ScraperRunSummary): string {
  return [
    t('admin.scraper.progress.resolved', { count: run.appsResolved }),
    t('admin.scraper.progress.discovered', { count: run.appsDiscovered }),
    t('admin.scraper.progress.failed', { count: run.appsFailed }),
    t('admin.scraper.progress.skipped', { count: run.appsSkipped }),
    t('admin.scraper.progress.review', { count: run.appsNeedsReview }),
    t('admin.scraper.progress.confirmedMissing', { count: run.appsConfirmedMissing }),
    t('admin.scraper.progress.transient', { count: run.appsTransientFailed }),
  ].join(' · ');
}

function metricPoints(
  metrics: ScraperMetricItem[],
  key: 'available' | 'review' | 'unavailable',
  width: number,
  height: number,
  maxValue: number,
): string {
  if (!metrics.length) return '';
  const xStep = metrics.length === 1 ? 0 : width / (metrics.length - 1);
  return metrics.map((item, index) => {
    const x = Math.round(index * xStep);
    const y = Math.round(height - (item[key] / maxValue) * (height - 24) - 12);
    return `${x},${y}`;
  }).join(' ');
}

function scraperControlState(t: Translator, current: ScraperRunSummary | null) {
  const running = current?.status === 'running';
  const paused = running && Boolean(current?.pausedAt || current?.currentPhase === 'paused');
  const stopping = Boolean(current?.stopRequested || current?.currentPhase === 'stopping');
  return {
    pause: {
      enabled: running && !paused && !stopping,
      reason: running && !paused && !stopping ? t('admin.scraper.reason.pause') : t('admin.scraper.reason.pauseUnavailable'),
    },
    resume: {
      enabled: paused && !stopping,
      reason: paused && !stopping ? t('admin.scraper.reason.resume') : t('admin.scraper.reason.resumeUnavailable'),
    },
    stop: {
      enabled: running && !stopping,
      reason: running && !stopping ? t('admin.scraper.reason.stop') : t('admin.scraper.reason.stopUnavailable'),
    },
    forceStop: {
      enabled: Boolean(current),
      reason: current ? t('admin.scraper.reason.forceStop') : t('admin.scraper.reason.forceStopUnavailable'),
    },
    runOnce: {
      enabled: !running,
      reason: !running ? t('admin.scraper.reason.runOnce') : t('admin.scraper.reason.runOnceUnavailable'),
    },
  };
}

function formatLogDetails(t: Translator, log: ResolverLogItem): string {
  const metadata = parseSafeMetadata(log.safeMetadata);
  const domain = metadataString(metadata, 'domain');
  const reason = metadataString(metadata, 'reason');
  const extension = metadataString(metadata, 'extension');
  const assetKind = metadataString(metadata, 'asset_kind');
  const source = metadataString(metadata, 'source');
  const error = metadataString(metadata, 'error');
  const detail = metadataString(metadata, 'detail');
  const statement = metadataString(metadata, 'statement');
  const winstallId = metadataString(metadata, 'winstall_id');
  const appName = metadataString(metadata, 'app_name');
  const currentPhase = metadataString(metadata, 'current_phase');
  const lastKnownStep = metadataString(metadata, 'last_known_step');
  const officialDomain = metadataString(metadata, 'official_domain');
  const score = metadataNumber(metadata, 'score');
  const elapsedSeconds = metadataNumber(metadata, 'elapsed_seconds');
  const timeoutSeconds = metadataNumber(metadata, 'timeout_seconds');
  const isPrimary = metadataBoolean(metadata, 'is_primary');
  const details = [
    error ? t('admin.log.error', { value: error }) : null,
    detail ? t('admin.log.detail', { value: detail }) : null,
    appName ? t('admin.log.app', { value: appName }) : null,
    winstallId ? t('admin.log.winstallId', { value: winstallId }) : null,
    currentPhase ? t('admin.log.phase', { value: currentPhase }) : null,
    lastKnownStep && lastKnownStep !== currentPhase ? t('admin.log.lastStep', { value: lastKnownStep }) : null,
    domain ? t('admin.log.domain', { value: domain }) : null,
    officialDomain ? t('admin.log.officialDomain', { value: officialDomain }) : null,
    reason ? t('admin.log.reason', { value: reason }) : null,
    elapsedSeconds !== undefined ? t('admin.log.elapsedSeconds', { value: elapsedSeconds }) : null,
    timeoutSeconds !== undefined ? t('admin.log.timeoutSeconds', { value: timeoutSeconds }) : null,
    score !== undefined ? t('admin.log.score', { value: score }) : null,
    extension ? t('admin.log.extension', { value: extension }) : null,
    assetKind ? t('admin.log.type', { value: assetKind }) : null,
    source ? t('admin.log.source', { value: source }) : null,
    statement ? t('admin.log.sqlStatement') : null,
    isPrimary !== undefined ? (isPrimary ? t('admin.log.primary') : t('admin.log.alternate')) : null,
  ].filter(Boolean);
  if (log.message && details.length) return `${log.message} - ${details.join('; ')}`;
  if (details.length) return details.join('; ');
  return log.message || t('admin.log.noDetails');
}

function parseSafeMetadata(value?: string | null): Record<string, unknown> {
  if (!value) return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {};
  } catch {
    return {};
  }
}

function metadataString(metadata: Record<string, unknown>, key: string): string | undefined {
  const value = metadata[key];
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function metadataNumber(metadata: Record<string, unknown>, key: string): number | undefined {
  const value = metadata[key];
  return typeof value === 'number' ? value : undefined;
}

function metadataBoolean(metadata: Record<string, unknown>, key: string): boolean | undefined {
  const value = metadata[key];
  return typeof value === 'boolean' ? value : undefined;
}
