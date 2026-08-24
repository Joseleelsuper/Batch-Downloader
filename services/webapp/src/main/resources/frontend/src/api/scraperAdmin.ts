import type {
  ContentEnqueueResult,
  ResolverLogItem,
  ScraperEvent,
  ScraperMetricItem,
  ScraperQueueMaintenanceResult,
  ScraperQueueState,
  ScraperRunRequestResponse,
  ScraperRunSummary,
  ScraperSnapshotItem,
  ScrapeScope,
} from '../types/catalog';
import { API_BASE, requestJson } from './http';
import { apiWebSocketUrl, connectJsonWebSocket, type LiveConnectionState } from './liveConnection';

export function fetchAdminRuns(): Promise<ScraperRunSummary[]> {
  return requestJson('/api/v1/admin/scraper/runs');
}

export function fetchAdminCurrentRun(): Promise<ScraperRunSummary | null> {
  return requestJson('/api/v1/admin/scraper/current');
}

export function createScraperRun(
  scope: ScrapeScope,
  appIds?: string[],
): Promise<ScraperRunRequestResponse> {
  return requestJson('/api/v1/admin/scraper/runs', {
    method: 'POST', body: JSON.stringify({ scope, appIds }),
  });
}

export function fetchAdminLogs(): Promise<ResolverLogItem[]> {
  return requestJson('/api/v1/admin/scraper/logs');
}

export function fetchAdminQueues(): Promise<ScraperQueueState[]> {
  return requestJson('/api/v1/admin/scraper/queues');
}

export function fetchAdminMetrics(): Promise<ScraperMetricItem[]> {
  return requestJson('/api/v1/admin/scraper/metrics');
}

export function fetchAdminSnapshots(): Promise<ScraperSnapshotItem[]> {
  return requestJson('/api/v1/admin/scraper/snapshots');
}

export function recoverStuckScraperQueueItems(): Promise<ScraperQueueMaintenanceResult> {
  return requestJson('/api/v1/admin/scraper/queues/recover-stuck', { method: 'POST' });
}

export function retryFailedScraperQueueItems(): Promise<ScraperQueueMaintenanceResult> {
  return requestJson('/api/v1/admin/scraper/queues/retry-failed', { method: 'POST' });
}

export function pruneTerminalScraperQueueItems(): Promise<ScraperQueueMaintenanceResult> {
  return requestJson('/api/v1/admin/scraper/queues/prune-terminal', { method: 'POST' });
}

export function enqueueMissingScraperDescriptions(): Promise<ContentEnqueueResult> {
  return requestJson('/api/v1/admin/scraper/descriptions/enqueue-missing', { method: 'POST' });
}

export function scraperWebSocketUrl(): string {
  return apiWebSocketUrl('/api/v1/admin/scraper/ws', API_BASE);
}

export function connectScraperEvents(
  onEvent: (event: ScraperEvent) => void,
  onState?: (state: LiveConnectionState) => void,
): () => void {
  return connectJsonWebSocket(scraperWebSocketUrl(), 'scraper.changed', onEvent, onState);
}

export async function sendScraperCommand(
  command: 'pause' | 'resume' | 'stop' | 'force_stop' | 'run_once',
): Promise<void> {
  await requestJson<void>('/api/v1/admin/scraper/commands', {
    method: 'POST', body: JSON.stringify({ command }),
  });
}
