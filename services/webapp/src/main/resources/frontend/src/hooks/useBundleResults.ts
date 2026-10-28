import { useCallback, useEffect, useRef, useState } from 'react';
import { bundleSearch, fetchBundles, type BundleSearchParams } from '../api/bundles';
import { useAuth } from '../auth/AuthContext';
import type { BundleResponse } from '../types/catalog';

const cache = new Map<string, { response: BundleResponse; time: number }>();

/** Carga bundles sin reutilizar votos de otra cuenta y cancela las peticiones sustituidas. */
export function useBundleResults(params: BundleSearchParams, cacheEnabled = false) {
  const { account, status } = useAuth();
  const scope = status === 'checking' ? 'checking' : account?.id ?? 'anonymous';
  const key = bundleSearch(params).toString();
  const requestRef = useRef(params);
  requestRef.current = params;
  const [page, setPage] = useState<{ scope: string; key: string; response: BundleResponse } | null>(null);
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);

  const refresh = useCallback(() => {
    cache.clear();
    setRevision((value) => value + 1);
  }, []);

  useEffect(() => {
    if (scope === 'checking') return;
    const controller = new AbortController();
    const cacheKey = `${scope}:${key}`;
    const cached = cacheEnabled ? cache.get(cacheKey) : undefined;
    if (cached && Date.now() - cached.time < 15_000) setPage({ scope, key, response: cached.response });
    setLoading(true);
    setError(false);
    void fetchBundles(requestRef.current, controller.signal).then((response) => {
      if (controller.signal.aborted) return;
      if (cacheEnabled) {
        cache.set(cacheKey, { response, time: Date.now() });
        if (cache.size > 32) cache.delete(cache.keys().next().value!);
      }
      setPage({ scope, key, response });
    }).catch(() => { if (!controller.signal.aborted) setError(true); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [cacheEnabled, key, revision, scope]);

  return { response: page?.scope === scope ? page.response : null,
    isCurrent: page?.scope === scope && page.key === key, error, loading, refresh, scope };
}
