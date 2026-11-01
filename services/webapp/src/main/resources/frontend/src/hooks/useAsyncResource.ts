import { useCallback, useState } from 'react';
import { usePollingTask } from './usePollingTask';

interface Options {
  scope?: string;
  enabled?: boolean;
  pollMs?: number;
  reloadToken?: number;
}

/** Publica cada recurso al terminar, conserva sus datos y descarta respuestas sustituidas. */
export function useAsyncResource<T>(load: (signal: AbortSignal) => Promise<T>, {
  scope = '', enabled = true, pollMs, reloadToken = 0,
}: Options = {}) {
  const [page, setPage] = useState<{ scope: string; data: T }>();
  const [request, setRequest] = useState<{ scope: string; loading: boolean; error?: unknown }>();
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision((value) => value + 1), []);

  usePollingTask({
    enabled,
    immediate: true,
    intervalMs: pollMs ?? 0,
    pollKey: JSON.stringify([scope, reloadToken, revision]),
    task: async (signal) => {
      setRequest({ scope, loading: true });
      try {
        const data = await load(signal);
        if (!signal.aborted) {
          setPage({ scope, data });
          setRequest({ scope, loading: false });
        }
      } catch (error) {
        if (!signal.aborted) setRequest({ scope, loading: false, error: error ?? new Error('load_failed') });
      }
      return Boolean(pollMs);
    },
  });

  return {
    data: page?.scope === scope ? page.data : undefined,
    loading: enabled && (request?.scope !== scope || request.loading),
    error: request?.scope === scope ? request.error : undefined,
    refresh,
  };
}
