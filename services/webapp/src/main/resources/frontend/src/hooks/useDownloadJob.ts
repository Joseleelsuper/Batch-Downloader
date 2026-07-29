import { useCallback, useState } from 'react';
import {
  TERMINAL_DOWNLOAD_STATUSES,
  type DownloadJobRequest,
  useDownloadJobs,
} from '../downloads/DownloadJobsContext';

export type { DownloadJobRequest };

export function useDownloadJob() {
  const downloads = useDownloadJobs();
  const [jobId, setJobId] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [localError, setLocalError] = useState(false);
  const entry = jobId ? downloads.jobs.find((candidate) => candidate.id === jobId) : undefined;

  const start = useCallback(async (request: DownloadJobRequest, label?: string) => {
    setStarting(true);
    setLocalError(false);
    try {
      const created = await downloads.start(request, label);
      setJobId(created.id);
      return created;
    } catch (cause) {
      setLocalError(true);
      throw cause;
    } finally {
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
