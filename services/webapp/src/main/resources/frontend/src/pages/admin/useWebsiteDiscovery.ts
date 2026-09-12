import {
  KeyboardEvent,
  useEffect
} from 'react';
import {
  createWebsiteAppDiscovery,
  fetchWebsiteAppDiscovery
} from '../../api/adminApps';
import { usePollingTask } from '../../hooks/usePollingTask';
import { useTranslation } from '../../services/i18n';
import {
  errorMessage,
  formFromSuggestions,
  validateHttpsUrl,
  validateOptionalWebsiteInstallerUrls
} from './AdminAppsSupport';
import { useAdminAppsActivity } from './useAdminAppsActivity';

import { INSPECTION_POLL_MS, useAdminAppEditor, WEBSITE_DISCOVERY_STORAGE_KEY } from './useAdminAppEditor';
/** Recupera y consulta el descubrimiento web; ignora respuestas de solicitudes sustituidas. */
export function useWebsiteDiscovery(editor: ReturnType<typeof useAdminAppEditor>, activity: ReturnType<typeof useAdminAppsActivity>) {
  const t = useTranslation();
  const { creating, websiteDiscovery, websiteUrl, websiteInstallerUrls, detailRequestRef, hydratedWebsiteDiscoveryRef, websiteRecoveryRequestRef, setEditor, dispatchEditor } = editor;
  const { saving, discoveringWebsite, setMessage, setError, setOperation } = activity;
  useEffect(() => {
    let recovery: { id?: string; officialUrl?: string } | null = null;
    try {
      const stored = window.sessionStorage.getItem(WEBSITE_DISCOVERY_STORAGE_KEY);
      recovery = stored
        ? JSON.parse(stored) as { id?: string; officialUrl?: string }
        : null;
    } catch {
      window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
    }
    if (!recovery?.id) return;

    const controller = new AbortController();
    const requestId = ++websiteRecoveryRequestRef.current;
    detailRequestRef.current += 1;
    dispatchEditor({ type: 'create', websiteUrl: recovery.officialUrl || '' });
    setOperation('discoveringWebsite', true);
    hydratedWebsiteDiscoveryRef.current = null;

    void fetchWebsiteAppDiscovery(recovery.id, controller.signal)
      .then((recovered) => {
        if (
          controller.signal.aborted
          || requestId !== websiteRecoveryRequestRef.current
        ) {
          return;
        }
        if (['applied', 'expired'].includes(recovered.status)) {
          window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
          setEditor('creating', false);
          setEditor('detailOpen', false);
          setEditor('detailState', 'empty');
          return;
        }
        setEditor('websiteDiscovery', recovered);
      })
      .catch(() => {
        if (
          controller.signal.aborted
          || requestId !== websiteRecoveryRequestRef.current
        ) {
          return;
        }
        window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
        setEditor('creating', false);
        setEditor('detailOpen', false);
        setEditor('detailState', 'empty');
      })
      .finally(() => {
        if (requestId === websiteRecoveryRequestRef.current) {
          setOperation('discoveringWebsite', false);
        }
      });

    return () => controller.abort();
  }, [detailRequestRef, dispatchEditor, hydratedWebsiteDiscoveryRef, setEditor, setOperation, websiteRecoveryRequestRef]);

  const websiteDiscoveryPolling = Boolean(
    creating
    && websiteDiscovery
    && ['queued', 'running'].includes(websiteDiscovery.status),
  );
  usePollingTask({
    enabled: websiteDiscoveryPolling,
    intervalMs: INSPECTION_POLL_MS,
    pollKey: websiteDiscovery?.id ?? null,
    task: async (signal) => {
      if (!websiteDiscovery) return false;
      const next = await fetchWebsiteAppDiscovery(websiteDiscovery.id, signal);
      if (signal.aborted) return false;
      setEditor('websiteDiscovery', next);
      setError(null);
      return next.status === 'queued' || next.status === 'running';
    },
    onError: (requestError) => {
      setError(errorMessage(t, requestError, 'admin.apps.website.error.progress'));
    },
  });

  useEffect(() => {
    if (
      websiteDiscovery?.status !== 'ready'
      || !websiteDiscovery.suggestions
      || hydratedWebsiteDiscoveryRef.current === websiteDiscovery.id
    ) {
      return;
    }
    hydratedWebsiteDiscoveryRef.current = websiteDiscovery.id;
    setEditor('form', (current) => formFromSuggestions(current, websiteDiscovery.suggestions!));
    setEditor('websiteUrl',
      websiteDiscovery.suggestions.officialUrl.value
      || websiteUrl,
    );
    setMessage(t('admin.apps.website.ready'));
  }, [hydratedWebsiteDiscoveryRef, setEditor, setMessage, t, websiteDiscovery, websiteUrl]);

  async function startWebsiteDiscovery() {
    if (discoveringWebsite || saving) return;
    const validationError = validateHttpsUrl(
      t,
      websiteUrl,
      t('admin.apps.website.officialUrl'),
    ) || validateOptionalWebsiteInstallerUrls(t, websiteInstallerUrls);
    if (validationError) {
      setError(validationError);
      return;
    }
    const requestId = ++websiteRecoveryRequestRef.current;
    setOperation('discoveringWebsite', true);
    setMessage(null);
    setError(null);
    setEditor('websiteDiscovery', null);
    hydratedWebsiteDiscoveryRef.current = null;
    try {
      const discovery = await createWebsiteAppDiscovery({
        officialUrl: websiteUrl.trim(),
        installerUrls: {
          windows: websiteInstallerUrls.windows.trim() || null,
          macos: websiteInstallerUrls.macos.trim() || null,
          linux: websiteInstallerUrls.linux.trim() || null,
        },
      });
      if (requestId !== websiteRecoveryRequestRef.current) return;
      setEditor('websiteDiscovery', discovery);
      window.sessionStorage.setItem(
        WEBSITE_DISCOVERY_STORAGE_KEY,
        JSON.stringify({
          id: discovery.id,
          officialUrl: websiteUrl.trim(),
        }),
      );
      setMessage(t('admin.apps.website.queued'));
    } catch (requestError) {
      if (requestId !== websiteRecoveryRequestRef.current) return;
      setError(errorMessage(t, requestError, 'admin.apps.website.error.create'));
    } finally {
      if (requestId === websiteRecoveryRequestRef.current) {
        setOperation('discoveringWebsite', false);
      }
    }
  }

  function discoverWebsiteOnEnter(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    void startWebsiteDiscovery();
  }

  return { startWebsiteDiscovery, discoverWebsiteOnEnter };
}
