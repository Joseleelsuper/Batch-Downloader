import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  ApiRequestError,
  cancelDownloadJob,
  connectDownloadJobEvents,
  createDownloadJob,
  downloadJobFileUrl,
} from '../api/catalog';
import type { CreateDownloadJobRequest } from '../api/catalog';
import { t } from '../services/i18n';
import type { DownloadJob } from '../types/catalog';

const STORAGE_KEY = 'batch-downloader.download-jobs.v1';

export const TERMINAL_DOWNLOAD_STATUSES = new Set([
  'READY',
  'PARTIAL',
  'MANUAL_ONLY',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
]);

export const DOWNLOADABLE_DOWNLOAD_STATUSES = new Set([
  'READY',
  'PARTIAL',
  'MANUAL_ONLY',
]);

export type DownloadJobRequest = CreateDownloadJobRequest;

export interface TrackedDownloadJob {
  id: string;
  label: string;
  job: DownloadJob | null;
  autoDownloadAttempted: boolean;
  minimized: boolean;
  cancelling: boolean;
  connectionError: boolean;
  actionError: string | null;
}

interface StoredDownloadJob {
  id: string;
  label: string;
  autoDownloadAttempted: boolean;
  minimized: boolean;
}

interface DownloadJobsContextValue {
  jobs: TrackedDownloadJob[];
  startError: string | null;
  start: (request: DownloadJobRequest, label?: string) => Promise<DownloadJob>;
  cancel: (jobId: string) => Promise<void>;
  dismiss: (jobId: string) => void;
  toggleMinimized: (jobId: string) => void;
  clearStartError: () => void;
}

const DownloadJobsContext = createContext<DownloadJobsContextValue | null>(null);

function readStoredJobs(): TrackedDownloadJob[] {
  try {
    const value = window.sessionStorage.getItem(STORAGE_KEY);
    if (!value) return [];
    const parsed = JSON.parse(value) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.flatMap((candidate): TrackedDownloadJob[] => {
      if (!candidate || typeof candidate !== 'object') return [];
      const stored = candidate as Partial<StoredDownloadJob>;
      if (typeof stored.id !== 'string' || typeof stored.label !== 'string') return [];
      return [{
        id: stored.id,
        label: stored.label,
        job: null,
        autoDownloadAttempted: stored.autoDownloadAttempted === true,
        minimized: stored.minimized === true,
        cancelling: false,
        connectionError: false,
        actionError: null,
      }];
    });
  } catch {
    return [];
  }
}

function persistJobs(jobs: TrackedDownloadJob[]): void {
  try {
    const stored: StoredDownloadJob[] = jobs.map((entry) => ({
      id: entry.id,
      label: entry.label,
      autoDownloadAttempted: entry.autoDownloadAttempted,
      minimized: entry.minimized,
    }));
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
  } catch {
    // El seguimiento en memoria continúa aunque el navegador rechace sessionStorage.
  }
}

function requestErrorMessage(cause: unknown): string {
  if (!(cause instanceof ApiRequestError)) return t('download.job.createFailed');
  const knownKey = `download.job.apiError.${cause.code}`;
  const translated = t(knownKey);
  return translated === knownKey ? t('download.job.createFailed') : translated;
}

export function DownloadJobsProvider({ children }: Readonly<{ children: ReactNode }>) {
  const [jobs, setJobs] = useState<TrackedDownloadJob[]>(readStoredJobs);
  const [startError, setStartError] = useState<string | null>(null);
  const jobsRef = useRef(jobs);
  jobsRef.current = jobs;
  const attemptedDownloads = useRef(new Set(
    jobs.filter((entry) => entry.autoDownloadAttempted).map((entry) => entry.id),
  ));

  useEffect(() => {
    persistJobs(jobs);
  }, [jobs]);

  const updateJob = useCallback((jobId: string, job: DownloadJob) => {
    setJobs((current) => current.map((entry) => entry.id === jobId
      ? { ...entry, job, connectionError: false }
      : entry));
  }, []);

  const removeJob = useCallback((jobId: string) => {
    attemptedDownloads.current.delete(jobId);
    setJobs((current) => current.filter((entry) => entry.id !== jobId));
  }, []);

  const reportConnectionError = useCallback((jobId: string, cause?: unknown) => {
    if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 404)) {
      removeJob(jobId);
      return;
    }
    setJobs((current) => current.map((entry) => entry.id === jobId
      ? { ...entry, connectionError: true }
      : entry));
  }, [removeJob]);

  const claimAutoDownload = useCallback((jobId: string): boolean => {
    if (attemptedDownloads.current.has(jobId)) return false;
    attemptedDownloads.current.add(jobId);
    const next = jobsRef.current.map((entry) => entry.id === jobId
      ? { ...entry, autoDownloadAttempted: true }
      : entry);
    jobsRef.current = next;
    persistJobs(next);
    setJobs(next);
    return true;
  }, []);

  const start = useCallback(async (request: DownloadJobRequest, label?: string) => {
    setStartError(null);
    try {
      const created = await createDownloadJob(request);
      setJobs((current) => {
        const withoutStaleCopy = current.filter((entry) => entry.id !== created.id);
        return [...withoutStaleCopy, {
          id: created.id,
          label: label?.trim() || t('download.job.title'),
          job: created,
          autoDownloadAttempted: false,
          minimized: false,
          cancelling: false,
          connectionError: false,
          actionError: null,
        }];
      });
      return created;
    } catch (cause) {
      setStartError(requestErrorMessage(cause));
      throw cause;
    }
  }, []);

  const cancel = useCallback(async (jobId: string) => {
    const entry = jobs.find((candidate) => candidate.id === jobId);
    if (!entry?.job || TERMINAL_DOWNLOAD_STATUSES.has(entry.job.status)) return;
    setJobs((current) => current.map((candidate) => candidate.id === jobId
      ? { ...candidate, cancelling: true, actionError: null }
      : candidate));
    try {
      updateJob(jobId, await cancelDownloadJob(jobId));
    } catch (cause) {
      setJobs((current) => current.map((candidate) => candidate.id === jobId
        ? { ...candidate, actionError: requestErrorMessage(cause) }
        : candidate));
      throw cause;
    } finally {
      setJobs((current) => current.map((candidate) => candidate.id === jobId
        ? { ...candidate, cancelling: false }
        : candidate));
    }
  }, [jobs, updateJob]);

  const dismiss = useCallback((jobId: string) => {
    setJobs((current) => {
      const entry = current.find((candidate) => candidate.id === jobId);
      if (!entry?.job || !TERMINAL_DOWNLOAD_STATUSES.has(entry.job.status)) return current;
      attemptedDownloads.current.delete(jobId);
      return current.filter((candidate) => candidate.id !== jobId);
    });
  }, []);

  const toggleMinimized = useCallback((jobId: string) => {
    setJobs((current) => current.map((entry) => entry.id === jobId
      ? { ...entry, minimized: !entry.minimized }
      : entry));
  }, []);

  const value = useMemo<DownloadJobsContextValue>(() => ({
    jobs,
    startError,
    start,
    cancel,
    dismiss,
    toggleMinimized,
    clearStartError: () => setStartError(null),
  }), [cancel, dismiss, jobs, start, startError, toggleMinimized]);

  return (
    <DownloadJobsContext.Provider value={value}>
      {children}
      {jobs.map((entry) => (
        <DownloadJobTracker
          entry={entry}
          key={entry.id}
          onJob={updateJob}
          onConnectionError={reportConnectionError}
          claimAutoDownload={claimAutoDownload}
        />
      ))}
    </DownloadJobsContext.Provider>
  );
}

function DownloadJobTracker({
  entry,
  onJob,
  onConnectionError,
  claimAutoDownload,
}: Readonly<{
  entry: TrackedDownloadJob;
  onJob: (jobId: string, job: DownloadJob) => void;
  onConnectionError: (jobId: string, cause?: unknown) => void;
  claimAutoDownload: (jobId: string) => boolean;
}>) {
  const terminal = entry.job ? TERMINAL_DOWNLOAD_STATUSES.has(entry.job.status) : false;

  useEffect(() => {
    if (terminal) return undefined;
    return connectDownloadJobEvents(
      entry.id,
      (job) => onJob(entry.id, job),
      (cause) => onConnectionError(entry.id, cause),
    );
  }, [entry.id, onConnectionError, onJob, terminal]);

  useEffect(() => {
    if (!entry.job || !DOWNLOADABLE_DOWNLOAD_STATUSES.has(entry.job.status)) return;
    if (!claimAutoDownload(entry.id)) return;
    const link = document.createElement('a');
    link.href = downloadJobFileUrl(entry.id);
    link.hidden = true;
    link.setAttribute('aria-hidden', 'true');
    document.body.appendChild(link);
    link.click();
    link.remove();
  }, [claimAutoDownload, entry.id, entry.job?.status]);

  return null;
}

export function useDownloadJobs(): DownloadJobsContextValue {
  const context = useContext(DownloadJobsContext);
  if (!context) {
    throw new Error('useDownloadJobs must be used within DownloadJobsProvider');
  }
  return context;
}
