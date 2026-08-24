import type { DownloadJob, OperatingSystem } from '../types/catalog';
import { API_BASE, requestJson } from './http';
import { createRetryScheduler } from './liveConnection';

export type CreateDownloadJobRequest =
  | {
    appIds: string[];
    sourceRef?: string;
    operatingSystems?: OperatingSystem[];
    notifyWhenReady?: boolean;
  }
  | { bundleId: string; operatingSystems?: OperatingSystem[]; notifyWhenReady?: boolean };

const pendingCreations = new Map<string, Promise<DownloadJob>>();
const TERMINAL_STATUSES = new Set([
  'READY', 'PARTIAL', 'MANUAL_ONLY', 'FAILED', 'CANCELLED', 'EXPIRED',
]);

export async function createDownloadJob(request: CreateDownloadJobRequest): Promise<DownloadJob> {
  const key = JSON.stringify(request);
  const pending = pendingCreations.get(key);
  if (pending) return pending;
  const creation = requestJson<DownloadJob>('/api/v1/download-jobs', {
    method: 'POST', body: JSON.stringify(request),
  });
  pendingCreations.set(key, creation);
  try {
    return await creation;
  } finally {
    pendingCreations.delete(key);
  }
}

export function fetchDownloadJob(jobId: string): Promise<DownloadJob> {
  return requestJson<DownloadJob>(`/api/v1/download-jobs/${encodeURIComponent(jobId)}`);
}

export function cancelDownloadJob(jobId: string): Promise<DownloadJob> {
  return requestJson<DownloadJob>(`/api/v1/download-jobs/${encodeURIComponent(jobId)}`, {
    method: 'DELETE',
  });
}

export function downloadJobFileUrl(jobId: string): string {
  return `${API_BASE}/api/v1/download-jobs/${encodeURIComponent(jobId)}/file`;
}

export function connectDownloadJobEvents(
  jobId: string,
  onJob: (job: DownloadJob) => void,
  onError?: (cause?: unknown) => void,
): () => void {
  let stopped = false;
  let source: EventSource | undefined;
  let pollingAttempt = 0;
  const polling = createRetryScheduler(() => void poll());

  const accept = (job: DownloadJob) => {
    if (stopped) return;
    onJob(job);
    if (TERMINAL_STATUSES.has(job.status)) {
      source?.close();
      polling.stop();
    }
  };
  const poll = async () => {
    if (stopped) return;
    try {
      const job = await fetchDownloadJob(jobId);
      accept(job);
      if (!TERMINAL_STATUSES.has(job.status)) {
        pollingAttempt = 0;
        polling.schedule(pollingAttempt);
      }
    } catch (cause) {
      onError?.(cause);
      polling.schedule(++pollingAttempt);
    }
  };
  const consume = (event: MessageEvent<string>) => {
    try {
      accept(JSON.parse(event.data) as DownloadJob);
    } catch {
      onError?.();
    }
  };

  if (typeof EventSource === 'undefined') {
    polling.schedule(0);
  } else {
    source = new EventSource(
      `${API_BASE}/api/v1/download-jobs/${encodeURIComponent(jobId)}/events`,
      { withCredentials: true },
    );
    source.addEventListener('message', consume as EventListener);
    source.addEventListener('job', consume as EventListener);
    source.addEventListener('error', () => {
      source?.close();
      polling.schedule(0);
    });
  }

  return () => {
    stopped = true;
    source?.close();
    polling.stop();
  };
}
