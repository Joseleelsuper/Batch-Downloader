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
  cancelDownloadJob,
  connectDownloadJobEvents,
  createDownloadJob,
  fetchDownloadJobFileLink,
  reportDownloadActivity,
} from '../api/downloads';
import type { CreateDownloadJobRequest, LinuxSelection } from '../api/downloads';
import { LinuxTargetDialog } from './LinuxTargetDialog';
import { ApiRequestError } from '../api/http';
import { useTranslation, type Translator } from '../services/i18n';
import type { DownloadJob } from '../types/catalog';
import { chooseDownloadDestination, isDestinationCancelled, retryDeliveryRequest, saveDownload, type DownloadFileHandle } from './delivery';

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
  saving?: boolean;
  locallySaved?: boolean;
}

interface StoredDownloadJob {
  id: string;
  label: string;
  autoDownloadAttempted: boolean;
  minimized: boolean;
  locallySaved?: boolean;
}

interface DownloadJobsContextValue {
  jobs: TrackedDownloadJob[];
  startError: string | null;
  start: (request: DownloadJobRequest, label?: string,
    destination?: Promise<DownloadFileHandle | undefined>) => Promise<DownloadJob>;
  cancel: (jobId: string) => Promise<void>;
  download: (jobId: string) => Promise<void>;
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
        locallySaved: stored.locallySaved === true,
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
      locallySaved: entry.locallySaved === true,
    }));
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
  } catch {
    // El seguimiento en memoria continúa aunque el navegador rechace sessionStorage.
  }
}

function requestErrorMessage(t: Translator, cause: unknown): string {
  if (!(cause instanceof ApiRequestError)) return t('download.job.createFailed');
  const knownKey = `download.job.apiError.${cause.code}`;
  const translated = t(knownKey);
  return translated === knownKey ? t('download.job.createFailed') : translated;
}

function downloadLinkErrorMessage(t: Translator, cause: unknown): string {
  if (cause instanceof ApiRequestError) {
    const knownKey = `download.job.apiError.${cause.code}`;
    const translated = t(knownKey);
    if (translated !== knownKey) return translated;
  }
  return t('download.job.linkFailed');
}

export function DownloadJobsProvider({ children }: Readonly<{ children: ReactNode }>) {
  const t = useTranslation();
  const [jobs, setJobs] = useState<TrackedDownloadJob[]>(readStoredJobs);
  const [startError, setStartError] = useState<string | null>(null);
  const jobsRef = useRef(jobs);
  const destinations = useRef(new Map<string, DownloadFileHandle>());
  const deliveries = useRef(new Set<string>());
  const [linuxRequest, setLinuxRequest] = useState<DownloadJobRequest | null>(null);
  const linuxPending = useRef<{
    resolve: (selection: LinuxSelection) => void;
    reject: (error: Error) => void;
  } | null>(null);
  useEffect(() => () => {
    linuxPending.current?.reject(new Error('linux_selection_cancelled'));
    linuxPending.current = null;
  }, []);
  jobsRef.current = jobs;
  const attemptedDownloads = useRef(new Set(
    jobs.filter((entry) => entry.autoDownloadAttempted).map((entry) => entry.id),
  ));

  useEffect(() => {
    persistJobs(jobs);
  }, [jobs]);

  const updateJob = useCallback((jobId: string, job: DownloadJob) => {
    setJobs((current) => current.map((entry) => entry.id === jobId
      ? { ...entry, job: { ...job, linux: job.linux ?? entry.job?.linux,
          deliveryStatus: entry.locallySaved && job.deliveryStatus !== 'CLEANING' ? 'SAVED' : job.deliveryStatus,
        }, connectionError: false }
      : entry));
  }, []);

  const removeJob = useCallback((jobId: string) => {
    attemptedDownloads.current.delete(jobId);
    destinations.current.delete(jobId);
    try { window.localStorage.removeItem(`${STORAGE_KEY}.${jobId}.attempted`); } catch { /* unavailable */ }
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

  const reportAutoDownloadError = useCallback((jobId: string, cause?: unknown) => {
    setJobs((current) => current.map((entry) => entry.id === jobId
      ? { ...entry, actionError: downloadLinkErrorMessage(t, cause) }
      : entry));
  }, [t]);

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

  const deliver = useCallback(async (jobId: string, automatic: boolean) => {
    const currentEntry = jobsRef.current.find((entry) => entry.id === jobId);
    const job = currentEntry?.job;
    if (!job || !DOWNLOADABLE_DOWNLOAD_STATUSES.has(job.status)
        || currentEntry.locallySaved
        || job.deliveryStatus === 'SAVED' || job.deliveryStatus === 'CLEANING'
        || deliveries.current.has(jobId)) return;
    if (automatic && !claimAutoDownload(jobId)) return;
    deliveries.current.add(jobId);
    let locallySaved = false;
    const existingHandle = destinations.current.get(jobId);
    const handlePromise = existingHandle || automatic
      ? Promise.resolve(existingHandle) : chooseDownloadDestination();
    void handlePromise.catch(() => undefined);
    const perform = async () => {
      const handle = await handlePromise;
      const marker = `${STORAGE_KEY}.${jobId}.attempted`;
      if (automatic) {
        try {
          if (window.localStorage.getItem(marker)) return;
          window.localStorage.setItem(marker, 'true');
        } catch { /* Web Locks still prevent overlapping transfers when storage is unavailable. */ }
      }
      setJobs((current) => current.map((entry) => entry.id === jobId
        ? { ...entry, saving: Boolean(handle), actionError: null }
        : entry));
      if (handle) {
        destinations.current.set(jobId, handle);
        let lastRender = 0;
        await saveDownload(job, handle, (bytes) => {
          // Network chunks may arrive thousands of times per second.
          if (bytes !== job.artifactSizeBytes && Date.now() - lastRender < 250) return;
          lastRender = Date.now();
          setJobs((current) => current.map((entry) => entry.id === jobId && entry.job
            ? { ...entry, job: { ...entry.job, deliveryStatus: 'TRANSFERRING', deliveryBytes: bytes } }
            : entry));
        }, () => {
          locallySaved = true;
          setJobs((current) => current.map((entry) => entry.id === jobId && entry.job
            ? { ...entry, locallySaved: true, job: { ...entry.job, deliveryStatus: 'SAVED' } }
            : entry));
        });
      } else {
        const { url } = await retryDeliveryRequest(() => fetchDownloadJobFileLink(jobId));
        const link = document.createElement('a');
        link.href = url;
        link.hidden = true;
        link.setAttribute('aria-hidden', 'true');
        document.body.appendChild(link);
        link.click();
        link.remove();
      }
    };
    try {
      if (navigator.locks) {
        await navigator.locks.request(`download:${jobId}`, { ifAvailable: true },
          async (lock) => { if (lock) await perform(); });
      } else {
        await perform();
      }
    } catch (cause) {
      if (cause instanceof ApiRequestError && cause.status === 404) removeJob(jobId);
      else if (locallySaved) setJobs((current) => current.map((entry) => entry.id === jobId
        ? { ...entry, actionError: t('download.job.receiptFailed') } : entry));
      else if (!isDestinationCancelled(cause)) reportAutoDownloadError(jobId, cause);
    } finally {
      deliveries.current.delete(jobId);
      setJobs((current) => current.map((entry) => entry.id === jobId
        ? { ...entry, saving: false } : entry));
    }
  }, [claimAutoDownload, removeJob, reportAutoDownloadError, t]);

  const download = useCallback((jobId: string) => deliver(jobId, false), [deliver]);

  const start = useCallback(async (request: DownloadJobRequest, label?: string,
    chosenDestination?: Promise<DownloadFileHandle | undefined>) => {
    setStartError(null);
    try {
      // Must run directly in the original click, before any API request or Linux dialog.
      const destination = await (chosenDestination ?? chooseDownloadDestination());
      let selectedRequest = request;
      if (request.operatingSystems?.length === 1 && request.operatingSystems[0] === 'linux'
          && (!request.linuxTarget || !request.targetArchitecture)) {
        if (linuxPending.current) throw new Error('linux_selection_pending');
        const selection = await new Promise<LinuxSelection>((resolve, reject) => {
          linuxPending.current = { resolve, reject };
          setLinuxRequest(request);
        });
        selectedRequest = { ...request, ...selection };
      }
      const created = await createDownloadJob(selectedRequest);
      if (destination) destinations.current.set(created.id, destination);
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
      if (!isDestinationCancelled(cause)
          && !(cause instanceof Error && cause.message === 'linux_selection_cancelled')) {
        setStartError(requestErrorMessage(t, cause));
      }
      throw cause;
    }
  }, [t]);

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
        ? { ...candidate, actionError: requestErrorMessage(t, cause) }
        : candidate));
      throw cause;
    } finally {
      setJobs((current) => current.map((candidate) => candidate.id === jobId
        ? { ...candidate, cancelling: false }
        : candidate));
    }
  }, [jobs, t, updateJob]);

  const dismiss = useCallback((jobId: string) => {
    setJobs((current) => {
      const entry = current.find((candidate) => candidate.id === jobId);
      if (!entry?.job || !TERMINAL_DOWNLOAD_STATUSES.has(entry.job.status)) return current;
      attemptedDownloads.current.delete(jobId);
      destinations.current.delete(jobId);
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
    download,
    dismiss,
    toggleMinimized,
    clearStartError: () => setStartError(null),
  }), [cancel, dismiss, download, jobs, start, startError, toggleMinimized]);

  return (
    <DownloadJobsContext.Provider value={value}>
      {children}
      {linuxRequest ? <LinuxTargetDialog request={linuxRequest}
        onSelect={(selection) => {
          linuxPending.current?.resolve(selection);
          linuxPending.current = null;
          setLinuxRequest(null);
        }}
        onCancel={() => {
          linuxPending.current?.reject(new Error('linux_selection_cancelled'));
          linuxPending.current = null;
          setLinuxRequest(null);
        }} /> : null}
      {jobs.map((entry) => (
        <DownloadJobTracker
          entry={entry}
          key={entry.id}
          onJob={updateJob}
          onConnectionError={reportConnectionError}
          deliver={deliver}
        />
      ))}
    </DownloadJobsContext.Provider>
  );
}

function DownloadJobTracker({
  entry,
  onJob,
  onConnectionError,
  deliver,
}: Readonly<{
  entry: TrackedDownloadJob;
  onJob: (jobId: string, job: DownloadJob) => void;
  onConnectionError: (jobId: string, cause?: unknown) => void;
  deliver: (jobId: string, automatic: boolean) => Promise<void>;
}>) {
  const terminal = entry.job ? TERMINAL_DOWNLOAD_STATUSES.has(entry.job.status) : false;
  const jobStatus = entry.job?.status;

  useEffect(() => {
    return connectDownloadJobEvents(
      entry.id,
      (job) => onJob(entry.id, job),
      (cause) => onConnectionError(entry.id, cause),
    );
  }, [entry.id, onConnectionError, onJob]);

  useEffect(() => {
    if (terminal) return;
    let pending = false;
    const heartbeat = () => {
      if (pending) return;
      pending = true;
      void reportDownloadActivity(entry.id, 'waiting')
        .catch((cause: unknown) => onConnectionError(entry.id, cause))
        .finally(() => { pending = false; });
    };
    heartbeat();
    const timer = window.setInterval(heartbeat, 15_000);
    return () => window.clearInterval(timer);
  }, [entry.id, onConnectionError, terminal]);

  useEffect(() => {
    if (!jobStatus || !DOWNLOADABLE_DOWNLOAD_STATUSES.has(jobStatus)) return;
    void deliver(entry.id, true);
  }, [deliver, entry.id, jobStatus]);

  return null;
}

export function useDownloadJobs(): DownloadJobsContextValue {
  const context = useContext(DownloadJobsContext);
  if (!context) {
    throw new Error('useDownloadJobs must be used within DownloadJobsProvider');
  }
  return context;
}
