import { RefreshCw, RotateCcw, Square, Trash2, Wand2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import {
  connectScraperEvents,
  createScraperRun,
  enqueueMissingScraperDescriptions,
  fetchAdminCurrentRun,
  fetchAdminFailedItems,
  fetchAdminLogs,
  fetchAdminQueues,
  fetchAdminRuns,
  pruneTerminalScraperQueueItems,
  recoverStuckScraperQueueItems,
  retryFailedScraperQueueItems,
  sendScraperCommand,
} from '../../api/scraperAdmin';
import { ScraperQueues } from '../../components/admin/ScraperQueues';
import { useTranslation, type Translator } from '../../services/i18n';
import type {
  ResolverLogItem,
  ScraperFailedItem,
  ScraperQueueState,
  ScraperRunSummary,
  ScrapeScope,
} from '../../types/catalog';
import { formatDate } from '../../utils/date';

const STALE_HEARTBEAT_MS = 90 * 60 * 1000;

export function AdminScraperPage() {
  const t = useTranslation();
  const [current, setCurrent] = useState<ScraperRunSummary | null>(null);
  const [runs, setRuns] = useState<ScraperRunSummary[]>([]);
  const [logs, setLogs] = useState<ResolverLogItem[]>([]);
  const [failedItems, setFailedItems] = useState<ScraperFailedItem[]>([]);
  const [logView, setLogView] = useState<'issues' | 'all'>('issues');
  const [queues, setQueues] = useState<ScraperQueueState[]>([]);
  const [socketState, setSocketState] = useState<'live' | 'reconnecting' | 'offline'>('offline');
  const [message, setMessage] = useState<string | null>(null);
  const [enrichmentAction, setEnrichmentAction] = useState<'descriptions' | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const load = useCallback(async () => {
    const [nextCurrent, nextRuns, nextLogs, nextFailedItems, nextQueues] = await Promise.all([
      fetchAdminCurrentRun(),
      fetchAdminRuns(),
      fetchAdminLogs(logView === 'issues'),
      fetchAdminFailedItems(),
      fetchAdminQueues(),
    ]);
    setCurrent(nextCurrent);
    setRuns(nextRuns);
    setLogs(nextLogs);
    setFailedItems(nextFailedItems);
    setQueues(nextQueues);
  }, [logView]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => {
      setNow(Date.now());
      void load();
    }, 10000);
    return () => window.clearInterval(timer);
  }, [load]);

  useEffect(() => connectScraperEvents((event) => {
    setQueues(event.queues);
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
      let matched = 0;
      let enqueued = 0;
      let active = 0;
      let after: string | undefined;
      do {
        const result = await enqueueMissingScraperDescriptions(after);
        matched += result.matched;
        enqueued += result.enqueued;
        active += result.alreadyActive;
        after = result.nextAfter ?? undefined;
        setMessage(t('admin.message.enrichmentQueued', { enqueued, active }));
      } while (after);
      if (matched === 0) {
        setMessage(t('admin.message.noMissingDescriptions'));
      }
      await load();
    } catch {
      setMessage(t('admin.message.enrichmentQueueError'));
    } finally {
      setEnrichmentAction(null);
    }
  }

  const controlState = scraperControlState(t, current);
  const heartbeatTime = current ? parseUtcTimestamp(current.heartbeatAt) : Number.NaN;
  const heartbeatIsStale = current?.status === 'running'
    && Number.isFinite(heartbeatTime)
    && now - heartbeatTime > STALE_HEARTBEAT_MS;

  return (
    <section className="admin-panel">
      <h2>{t('admin.layout.scraper')}</h2>
      <div className="scraper-status admin-card">
        <div>
          <span>{t('admin.scraper.status')}</span>
          <strong>{current ? runStatusLabel(t, current.status) : '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.scope')}</span>
          <strong>{current ? scopeLabel(t, current.scope) : '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.app')}</span>
          <strong>{current?.currentAppName ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.currentPhase')}</span>
          <strong>{current && current.currentPhase === current.status
            ? runStatusLabel(t, current.status)
            : current?.currentPhase ?? '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.startedAt')}</span>
          <strong>{current ? formatScraperDate(current.startedAt) : '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.finishedAt')}</span>
          <strong>{current?.finishedAt ? formatScraperDate(current.finishedAt) : '-'}</strong>
        </div>
        <div>
          <span>{t('admin.scraper.heartbeatAt')}</span>
          <strong>{current ? formatScraperDate(current.heartbeatAt) : '-'}</strong>
        </div>
        <div className="scraper-status-progress">
          <span>{t('admin.scraper.progress')}</span>
          {current ? (
            <strong className="scraper-progress-summary">
              {scrapeProgressItems(t, current).map((item) => <span key={item}>{item}</span>)}
            </strong>
          ) : <strong>-</strong>}
        </div>
      </div>
      {heartbeatIsStale ? (
        <p className="form-message" role="alert">{t('admin.scraper.staleHeartbeat')}</p>
      ) : null}
      {current?.status === 'failed' || current?.status === 'partial' ? (
        <div className="scraper-failure admin-card" role="status">
          <strong>{t(current.status === 'failed'
            ? 'admin.scraper.failure.title'
            : 'admin.scraper.failure.partialTitle')}</strong>
          <p>{runOutcomeExplanation(t, current)}</p>
        </div>
      ) : null}
      <section className="scraper-action-section admin-card" aria-labelledby="scraper-basic-actions-heading">
        <h3 id="scraper-basic-actions-heading">{t('admin.scraper.actions.basic')}</h3>
        <div className="button-row">
          <button className="secondary-button" type="button" disabled={!controlState.pause.enabled} title={controlState.pause.reason} onClick={() => command('pause')}>{t('admin.scraper.pause')}</button>
          <button className="secondary-button" type="button" disabled={!controlState.resume.enabled} title={controlState.resume.reason} onClick={() => command('resume')}>{t('admin.scraper.resume')}</button>
          <button className="secondary-button" type="button" disabled={!controlState.stop.enabled} title={controlState.stop.reason} onClick={() => command('stop')}><Square size={16} />{t('admin.scraper.stop')}</button>
          <button className="secondary-button danger-button" type="button" disabled={!controlState.forceStop.enabled} title={controlState.forceStop.reason} onClick={() => command('force_stop')}><Square size={16} />{t('admin.scraper.forceStop')}</button>
        </div>
      </section>
      <section className="scraper-action-section admin-card" aria-labelledby="scraper-execution-heading">
        <h3 id="scraper-execution-heading">{t('admin.scraper.actions.execution')}</h3>
        <div className="button-row">
          <button className="primary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('incremental')}>{t('admin.scraper.runIncremental')}</button>
          <button className="secondary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('unresolved')}>{t('admin.scraper.runUnresolved')}</button>
          <button className="secondary-button" type="button" disabled={!controlState.runOnce.enabled} title={controlState.runOnce.reason} onClick={() => requestRun('full')}>{t('admin.scraper.runFull')}</button>
        </div>
      </section>
      <section className="scraper-action-section admin-card" aria-labelledby="scraper-advanced-actions-heading">
        <h3 id="scraper-advanced-actions-heading">{t('admin.scraper.actions.advanced')}</h3>
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
      </section>
      {message ? <p className="form-message">{message}</p> : null}
      <div className="scraper-live-line">
        <span>{t('admin.scraper.liveState')}</span>
        <strong>{socketState}</strong>
      </div>
      <ScraperQueues queues={queues} />
      <section className="admin-card scraper-activity" aria-labelledby="scraper-failed-items-heading">
        <h3 id="scraper-failed-items-heading">{t('admin.scraper.failedItems')}</h3>
        {failedItems.length ? failedItems.map((item) => (
          <article className="scraper-activity-item" key={item.id}>
            <div className="scraper-activity-heading">
              <strong>{item.appName || item.packageId}</strong>
              <span>{queueLabel(t, item.queue)} · {t('admin.scraper.attempts', { count: item.attempts })}</span>
              <time dateTime={item.updatedAt}>{formatDate(item.updatedAt)}</time>
            </div>
            <p>{item.lastError
              ? explainRunFailure(t, item.lastError)
              : t('admin.scraper.failure.jobUnavailable')}</p>
          </article>
        )) : <p className="empty-state">{t('admin.scraper.noFailedItems')}</p>}
      </section>
      <section className="admin-card scraper-activity" aria-labelledby="scraper-runs-heading">
        <h3 id="scraper-runs-heading">{t('admin.scraper.runs')}</h3>
        {runs.length ? runs.map((run) => (
          <details className="scraper-activity-item" key={run.id}>
            <summary>
              <span className="scraper-activity-heading">
                <strong>{runStatusLabel(t, run.status)}</strong>
                <span>{scopeLabel(t, run.scope)} · {t('admin.scraper.targets', { count: run.targetCount })}</span>
                <time dateTime={run.startedAt}>{formatDate(run.startedAt)}</time>
              </span>
              {run.errorSummary || run.status === 'failed' || run.status === 'partial' ? (
                <span className="scraper-activity-message">{runOutcomeExplanation(t, run)}</span>
              ) : null}
            </summary>
            <div className="scraper-activity-detail">
              <p>{formatScrapeProgress(t, run)}</p>
              <p>{t('admin.scraper.currentPhase')}: {run.currentPhase === run.status
                ? runStatusLabel(t, run.status)
                : run.currentPhase || t('admin.scraper.noPhase')}</p>
              <p>{t('admin.scraper.app')}: {run.currentAppName || run.currentPackageId || t('admin.scraper.noApp')}</p>
              <p>{t('admin.scraper.startedAt')}: {formatScraperDate(run.startedAt)}</p>
              <p>{t('admin.scraper.finishedAt')}: {run.finishedAt ? formatScraperDate(run.finishedAt) : '-'}</p>
            </div>
          </details>
        )) : <p className="empty-state">{t('admin.table.empty')}</p>}
      </section>
      <section className="admin-card scraper-activity" aria-labelledby="scraper-logs-heading">
        <div className="scraper-activity-toolbar">
          <h3 id="scraper-logs-heading">{t('admin.scraper.logs')}</h3>
          <div className="scraper-log-filters" role="group" aria-label={t('admin.scraper.logFilter')}>
            <button type="button" aria-pressed={logView === 'issues'} onClick={() => setLogView('issues')}>{t('admin.scraper.issues')}</button>
            <button type="button" aria-pressed={logView === 'all'} onClick={() => setLogView('all')}>{t('admin.scraper.allLogs')}</button>
          </div>
        </div>
        {logs.length ? logs.map((log) => {
          const metadata = parseSafeMetadata(log.safeMetadata);
          return (
            <details className="scraper-activity-item" key={log.id}>
              <summary>
                <span className="scraper-activity-heading">
                  <strong>{logStatusLabel(t, log.status)}</strong>
                  <span>{logPhaseLabel(t, log.phase)} · {String(metadata.app_name || metadata.winstall_id || t('admin.scraper.noApp'))}</span>
                  <time dateTime={log.createdAt}>{formatDate(log.createdAt)}</time>
                </span>
                <span className="scraper-activity-message">{log.message
                  ? formatLogMessage(t, log.message)
                  : String(metadata.reason
                    ? candidateReasonLabel(t, String(metadata.reason))
                    : metadata.detail || t('admin.scraper.logOpenDetails'))}</span>
              </summary>
              <div className="scraper-activity-detail">
                <p>{formatLogDetails(t, log)}</p>
                <CandidateDiagnostics t={t} value={metadata.candidate_diagnostics} />
              </div>
            </details>
          );
        }) : <p className="empty-state">{t(logView === 'issues' ? 'admin.scraper.noIssues' : 'admin.table.empty')}</p>}
      </section>
    </section>
  );
}

function parseUtcTimestamp(value: string): number {
  const hasTimezone = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value);
  return Date.parse(hasTimezone ? value : `${value}Z`);
}

function formatScraperDate(value: string): string {
  const timestamp = parseUtcTimestamp(value);
  return Number.isFinite(timestamp) ? formatDate(new Date(timestamp).toISOString()) : '-';
}

function formatScrapeProgress(t: Translator, run: ScraperRunSummary): string {
  return scrapeProgressItems(t, run).join(' · ');
}

const RUN_STATUS_KEYS: Record<string, string> = {
  running: 'admin.scraper.runStatus.running',
  completed: 'admin.scraper.runStatus.completed',
  partial: 'admin.scraper.runStatus.partial',
  failed: 'admin.scraper.runStatus.failed',
};

const SCOPE_KEYS: Record<string, string> = {
  incremental: 'admin.scraper.scope.incremental',
  unresolved: 'admin.scraper.scope.unresolved',
  selected: 'admin.scraper.scope.selected',
  full: 'admin.scraper.scope.full',
};

const LOG_PHASE_KEYS: Record<string, string> = {
  provider: 'admin.scraper.phase.provider',
  resolve: 'admin.scraper.phase.resolve',
  validate: 'admin.scraper.phase.validate',
  official_http: 'admin.scraper.phase.officialHttp',
  official_playwright: 'admin.scraper.phase.officialBrowser',
  github_releases: 'admin.scraper.phase.github',
  winstall_page: 'admin.scraper.phase.winstall',
  winstall_github_latest: 'admin.scraper.phase.winstallGithub',
  descriptor: 'admin.scraper.phase.descriptor',
  icon: 'admin.scraper.phase.icon',
  known_official: 'admin.scraper.phase.knownOfficial',
};

const LOG_STATUS_KEYS: Record<string, string> = {
  failed: 'admin.scraper.logStatus.failed',
  transient_failed: 'admin.scraper.logStatus.transientFailed',
  rejected: 'admin.scraper.logStatus.rejected',
  requires_manual_review: 'admin.scraper.logStatus.review',
  needs_review: 'admin.scraper.logStatus.review',
  missing: 'admin.scraper.logStatus.missing',
  broken: 'admin.scraper.logStatus.broken',
  expired_incompatible: 'admin.scraper.logStatus.expired',
  completed: 'admin.scraper.logStatus.completed',
};

const QUEUE_KEYS: Record<string, string> = {
  searcher_filter: 'admin.scraper.queue.searcherFilter',
  filter_scraper: 'admin.scraper.queue.filterScraper',
  scraper_so_filter: 'admin.scraper.queue.scraperSoFilter',
  so_filter_descriptor: 'admin.scraper.queue.soFilterDescriptor',
};

const MYSQL_REASON_KEYS: Record<string, string> = {
  '1038': 'admin.scraper.mysql.1038',
  '1205': 'admin.scraper.mysql.1205',
  '1213': 'admin.scraper.mysql.1213',
  '2003': 'admin.scraper.mysql.2003',
  '2013': 'admin.scraper.mysql.2013',
};

const FAILURE_CODE_KEYS: Record<string, string> = {
  database_pool_timeout: 'admin.scraper.failure.databasePool',
  llm_provider_not_configured: 'admin.scraper.failure.noLlmProvider',
  llm_models_cooling_down: 'admin.scraper.failure.llmCoolingDown',
  'Stopped by admin command': 'admin.scraper.failure.adminStopped',
};

const LOG_MESSAGE_KEYS: Record<string, string> = {
  'No safe installer candidate was found.': 'admin.scraper.logMessage.noCandidate',
  'No replacement was validated; the published installer was preserved.': 'admin.scraper.logMessage.preserved',
  'No safe installer candidate was found and active evidence exists.': 'admin.scraper.logMessage.confirmedMissing',
  'No safe installer candidate was found; manual evidence is required.': 'admin.scraper.logMessage.manualReview',
  'A previously published artifact no longer matches this app.': 'admin.scraper.logMessage.incompatible',
  'The package is absent from the stable Winstall snapshot.': 'admin.scraper.logMessage.absentProvider',
  no_safe_github_image: 'admin.scraper.logMessage.noIcon',
};

function runStatusLabel(t: Translator, status: string): string {
  return RUN_STATUS_KEYS[status] ? t(RUN_STATUS_KEYS[status]) : status;
}

function scopeLabel(t: Translator, scope: string): string {
  return SCOPE_KEYS[scope] ? t(SCOPE_KEYS[scope]) : scope;
}

function logPhaseLabel(t: Translator, phase: string): string {
  return LOG_PHASE_KEYS[phase] ? t(LOG_PHASE_KEYS[phase]) : phase;
}

function logStatusLabel(t: Translator, status: string): string {
  return LOG_STATUS_KEYS[status] ? t(LOG_STATUS_KEYS[status]) : status;
}

function queueLabel(t: Translator, queue: string): string {
  return QUEUE_KEYS[queue] ? t(QUEUE_KEYS[queue]) : queue;
}

function explainRunFailure(t: Translator, summary?: string | null): string {
  if (!summary) return t('admin.scraper.failure.unavailable');
  if (FAILURE_CODE_KEYS[summary]) return t(FAILURE_CODE_KEYS[summary]);
  const timeout = /^timeout_after_(\d+)s$/.exec(summary)?.[1];
  if (timeout) return t('admin.scraper.failure.appTimeout', { seconds: timeout });
  if (summary.startsWith('Scrape stopped after its heartbeat exceeded')) {
    return t('admin.scraper.failure.heartbeatExpired');
  }
  if (summary.startsWith('Recovered before startup')) {
    return t('admin.scraper.failure.schedulerRestarted');
  }
  const mysqlCode = /\bMySQL (\d+)\b/.exec(summary)?.[1];
  if (mysqlCode && MYSQL_REASON_KEYS[mysqlCode]) {
    return `${t(MYSQL_REASON_KEYS[mysqlCode])} (${summary.split(':')[0]})`;
  }
  if (/^[A-Za-z][A-Za-z0-9]*(Error|Exception)$/.test(summary)) {
    return t('admin.scraper.failure.legacy', { type: summary });
  }
  return summary;
}

function formatLogMessage(t: Translator, message: string): string {
  if (FAILURE_CODE_KEYS[message]) return t(FAILURE_CODE_KEYS[message]);
  if (LOG_MESSAGE_KEYS[message]) return t(LOG_MESSAGE_KEYS[message]);
  if (/^[A-Za-z][A-Za-z0-9]*(Error|Exception)$/.test(message)) {
    return t('admin.scraper.logMessage.exceptionOnly', { type: message });
  }
  return message;
}

function runOutcomeExplanation(t: Translator, run: ScraperRunSummary): string {
  if (run.errorSummary) return explainRunFailure(t, run.errorSummary);
  if (run.status === 'partial' && run.appsTransientFailed > 0) {
    return t('admin.scraper.failure.partialTransient', { count: run.appsTransientFailed });
  }
  if (run.status === 'partial') return t('admin.scraper.failure.partialUnknown');
  return explainRunFailure(t, null);
}

const DIAGNOSTIC_GROUP_KEYS: Record<string, string> = {
  direct: 'admin.scraper.diagnostics.direct',
  fallback: 'admin.scraper.diagnostics.fallback',
  fallback_refresh: 'admin.scraper.diagnostics.fallbackRefresh',
  publication: 'admin.scraper.diagnostics.publication',
};

const DIAGNOSTIC_COUNT_KEYS: Record<string, string> = {
  discovered: 'admin.scraper.diagnostics.discovered',
  eligible: 'admin.scraper.diagnostics.eligible',
  attempted: 'admin.scraper.diagnostics.attempted',
  valid: 'admin.scraper.diagnostics.valid',
  observed: 'admin.scraper.diagnostics.observed',
  publishable: 'admin.scraper.diagnostics.publishable',
  attested_or_non_public: 'admin.scraper.diagnostics.nonPublic',
  expired_incompatible: 'admin.scraper.diagnostics.expired',
};

const DIAGNOSTIC_REASON_KEYS: Record<string, string> = {
  skipped: 'admin.scraper.diagnostics.skipped',
  rejected: 'admin.scraper.diagnostics.rejected',
  errors: 'admin.scraper.diagnostics.errors',
  incompatible: 'admin.scraper.diagnostics.incompatible',
};

const CANDIDATE_REASON_KEYS: Record<string, string> = {
  html_response: 'admin.scraper.candidateReason.html',
  missing_installer_extension: 'admin.scraper.candidateReason.missingExtension',
  unsupported_installer_format: 'admin.scraper.candidateReason.unsupportedFormat',
  installer_content_type_mismatch: 'admin.scraper.candidateReason.contentType',
  installer_signature_mismatch: 'admin.scraper.candidateReason.signature',
  not_an_installer: 'admin.scraper.candidateReason.notInstaller',
  dns_not_public: 'admin.scraper.candidateReason.dns',
  file_too_large: 'admin.scraper.candidateReason.tooLarge',
  validation_budget_exhausted: 'admin.scraper.candidateReason.budget',
  no_platform_or_download_intent: 'admin.scraper.candidateReason.noPlatform',
  non_positive_score: 'admin.scraper.candidateReason.lowScore',
  operating_system_unresolved: 'admin.scraper.candidateReason.unknownOs',
  product_identity_mismatch: 'admin.scraper.candidateReason.identity',
};

function candidateReasonLabel(t: Translator, reason: string): string {
  const [code, detail] = reason.split(':', 2);
  if (CANDIDATE_REASON_KEYS[code]) {
    return `${t(CANDIDATE_REASON_KEYS[code])}${detail ? `: ${detail}` : ''}`;
  }
  return /^http_\d{3}$/.test(reason) ? reason.replace('http_', 'HTTP ') : reason.replace(/_/g, ' ');
}

function CandidateDiagnostics({ t, value }: { t: Translator; value: unknown }) {
  if (!isRecord(value)) return null;
  return (
    <div className="scraper-diagnostics">
      <strong>{t('admin.scraper.diagnostics.title')}</strong>
      {Object.entries(value).flatMap(([groupName, group]) => {
        if (!isRecord(group)) return [];
        const counts = Object.entries(DIAGNOSTIC_COUNT_KEYS)
          .filter(([key]) => typeof group[key] === 'number')
          .map(([key, label]) => t(label, { count: group[key] as number }));
        const reasons = Object.entries(DIAGNOSTIC_REASON_KEYS)
          .filter(([key]) => isRecord(group[key]) && Object.keys(group[key]).length > 0)
          .map(([key, label]) => `${t(label)}: ${Object.entries(group[key] as Record<string, unknown>)
            .map(([reason, count]) => `${candidateReasonLabel(t, reason)} (${count})`).join(', ')}`);
        return (
          [<div key={groupName}>
            <strong>{DIAGNOSTIC_GROUP_KEYS[groupName] ? t(DIAGNOSTIC_GROUP_KEYS[groupName]) : groupName}</strong>
            <p>{[...counts, ...reasons].join(' · ') || t('admin.log.noDetails')}</p>
          </div>]
        );
      })}
    </div>
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function scrapeProgressItems(t: Translator, run: ScraperRunSummary): string[] {
  return [
    t('admin.scraper.progress.resolved', { count: run.appsResolved }),
    t('admin.scraper.progress.discovered', { count: run.appsDiscovered }),
    t('admin.scraper.progress.failed', { count: run.appsFailed }),
    t('admin.scraper.progress.skipped', { count: run.appsSkipped }),
    t('admin.scraper.progress.review', { count: run.appsNeedsReview }),
    t('admin.scraper.progress.confirmedMissing', { count: run.appsConfirmedMissing }),
    t('admin.scraper.progress.transient', { count: run.appsTransientFailed }),
  ];
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

const LOG_DETAIL_LABELS = {
  error: 'admin.log.error',
  detail: 'admin.log.detail',
  app_name: 'admin.log.app',
  winstall_id: 'admin.log.winstallId',
  current_phase: 'admin.log.phase',
  last_known_step: 'admin.log.lastStep',
  domain: 'admin.log.domain',
  official_domain: 'admin.log.officialDomain',
  reason: 'admin.log.reason',
  elapsed_seconds: 'admin.log.elapsedSeconds',
  timeout_seconds: 'admin.log.timeoutSeconds',
  score: 'admin.log.score',
  extension: 'admin.log.extension',
  asset_kind: 'admin.log.type',
  source: 'admin.log.source',
  statement: 'admin.log.sqlStatement',
  provider: 'admin.log.provider',
  model: 'admin.log.model',
  filename: 'admin.log.filename',
  version: 'admin.log.version',
  count: 'admin.log.count',
} as const;
const PRIMARY_LOG_LABELS = new Map<boolean, string>([
  [true, 'admin.log.primary'],
  [false, 'admin.log.alternate'],
]);

function formatLogDetails(t: Translator, log: ResolverLogItem): string {
  const metadata = parseSafeMetadata(log.safeMetadata);
  const visibleMetadata: Record<string, unknown> = {
    ...metadata,
    last_known_step: metadata.last_known_step === metadata.current_phase
      ? undefined
      : metadata.last_known_step,
  };
  const details = Object.entries(LOG_DETAIL_LABELS)
    .map(([key, translation]) => [translation, visibleMetadata[key]] as const)
    .filter(([, value]) => Boolean(value) || value === 0)
    .map(([translation, value]) => t(translation, {
      value: translation === 'admin.log.reason'
        ? candidateReasonLabel(t, String(value))
        : String(value),
    }));
  const primaryTranslation = PRIMARY_LOG_LABELS.get(metadata.is_primary as boolean);
  const primaryDetail = primaryTranslation ? t(primaryTranslation) : undefined;
  const detailText = [...details, primaryDetail].filter(Boolean).join('; ');
  return [log.message ? formatLogMessage(t, log.message) : null, detailText]
    .filter(Boolean).join(' - ') || t('admin.log.noDetails');
}

function parseSafeMetadata(value?: string | null): Record<string, unknown> {
  try {
    return Object(JSON.parse(value || '{}')) as Record<string, unknown>;
  } catch {
    return {};
  }
}
