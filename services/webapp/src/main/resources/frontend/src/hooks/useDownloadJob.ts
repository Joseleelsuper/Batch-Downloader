import { useCallback, useRef, useState } from 'react';
import {
  TERMINAL_DOWNLOAD_STATUSES,
  type DownloadJobRequest,
  useDownloadJobs,
} from '../downloads/DownloadJobsContext';
import type { DownloadJob } from '../types/catalog';

export type { DownloadJobRequest };

export function useDownloadJob() {
  const downloads = useDownloadJobs();
  const [jobId, setJobId] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const startInFlight = useRef<Promise<DownloadJob> | null>(null);
  const [localError, setLocalError] = useState(false);
  const entry = jobId ? downloads.jobs.find((candidate) => candidate.id === jobId) : undefined;

  const start = useCallback(async (request: DownloadJobRequest, label?: string) => {
    if (startInFlight.current) return startInFlight.current;
    setStarting(true);
    setLocalError(false);
    const operation = downloads.start(request, label);
    startInFlight.current = operation;
    try {
      const created = await operation;
      setJobId(created.id);
      return created;
    } catch (cause) {
      setLocalError(true);
      throw cause;
    } finally {
      startInFlight.current = null;
      setStarting(false);
    }
  }, [downloads]);

  const cancel = useCallback(async () => {
    if (!entry?.job || TERMINAL_DOWNLOAD_STATUSES.has(entry.job.status)) return;
    setLocalError(false);
    try {
      await downloads.cancel(entry.id);
    } catch (cause) {
      setLocalError(true);
      throw cause;
    }
  }, [downloads, entry]);

  const clear = useCallback(() => {
    if (entry?.job && TERMINAL_DOWNLOAD_STATUSES.has(entry.job.status)) {
      downloads.dismiss(entry.id);
      setJobId(null);
    }
    setLocalError(false);
  }, [downloads, entry]);

  return {
    job: entry?.job ?? null,
    starting,
    cancelling: entry?.cancelling ?? false,
    error: localError || Boolean(entry?.connectionError || entry?.actionError),
    start,
    cancel,
    clear,
  };
}
