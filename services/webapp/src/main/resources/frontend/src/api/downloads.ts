import type { DownloadJob, OperatingSystem } from '../types/catalog';
import { API_BASE, requestJson } from './http';
import { createRetryScheduler } from './liveConnection';

export type LinuxTarget = 'apt' | 'dnf' | 'pacman' | 'zypper' | 'portable';
export type LinuxArchitecture = 'x86_64' | 'x86' | 'aarch64';
export interface LinuxSelection { linuxTarget: LinuxTarget; targetArchitecture: LinuxArchitecture }
export interface LinuxPreview {
  target: LinuxTarget;
  architecture: LinuxArchitecture;
  totalCount: number;
  automaticCount: number;
  manualCount: number;
  omittedCount: number;
  items: { appId: string; name: string; sourceRef: string | null; installationSupport: string; dependency: boolean }[];
}

export type CreateDownloadJobRequest = Partial<LinuxSelection> & (
  | {
    appIds: string[];
    sourceRef?: string;
    operatingSystems?: OperatingSystem[];
  }
  | { bundleId: string; operatingSystems?: OperatingSystem[] });

export function previewLinuxDownload(request: CreateDownloadJobRequest): Promise<LinuxPreview> {
  return requestJson<LinuxPreview>('/api/v1/download-jobs/linux-preview', {
    method: 'POST', body: JSON.stringify(request),
  });
}

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

export function fetchDownloadJobFileLink(jobId: string): Promise<{ url: string }> {
  return requestJson<{ url: string }>(
    `/api/v1/download-jobs/${encodeURIComponent(jobId)}/file-link`,
  );
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
