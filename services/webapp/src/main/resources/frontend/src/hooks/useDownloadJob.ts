import { useCallback, useEffect, useState } from 'react';
import {
  cancelDownloadJob,
  connectDownloadJobEvents,
  createDownloadJob,
} from '../api/catalog';
import type { DownloadJob } from '../types/catalog';
import type { OperatingSystem } from '../types/catalog';

const TERMINAL_STATUSES = new Set(['READY', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED']);
export type DownloadJobRequest =
  | { appIds: string[]; operatingSystems?: OperatingSystem[]; notifyWhenReady?: boolean }
  | { bundleId: string; operatingSystems?: OperatingSystem[]; notifyWhenReady?: boolean };

export function useDownloadJob() {
  const [job, setJob] = useState<DownloadJob | null>(null);
  const [starting, setStarting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!job || TERMINAL_STATUSES.has(job.status)) return undefined;
    return connectDownloadJobEvents(job.id, setJob, () => setError(true));
  }, [job?.id, job?.status]);

  const start = useCallback(async (request: DownloadJobRequest) => {
    setStarting(true);
    setError(false);
    try {
      const created = await createDownloadJob(request);
      setJob(created);
      return created;
    } catch (cause) {
      setError(true);
      throw cause;
    } finally {
      setStarting(false);
    }
  }, []);

  const cancel = useCallback(async () => {
    if (!job || TERMINAL_STATUSES.has(job.status)) return;
    setCancelling(true);
    setError(false);
    try {
      setJob(await cancelDownloadJob(job.id));
    } catch (cause) {
      setError(true);
      throw cause;
    } finally {
      setCancelling(false);
    }
  }, [job]);

  const clear = useCallback(() => {
    setJob(null);
    setError(false);
  }, []);

  return { job, starting, cancelling, error, start, cancel, clear };
}
