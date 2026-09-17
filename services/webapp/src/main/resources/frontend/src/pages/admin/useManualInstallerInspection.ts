import {
  KeyboardEvent,
  useEffect
} from 'react';
import {
  createManualInstallerInspection,
  fetchManualInstallerInspection
} from '../../api/adminApps';
import { usePollingTask } from '../../hooks/usePollingTask';
import { useTranslation } from '../../services/i18n';
import {
  errorMessage,
  formFromSuggestions,
  manualInspectionInstallers,
  validateManualUrls
} from './AdminAppsSupport';
import { useAdminAppsActivity } from './useAdminAppsActivity';

import { INSPECTION_POLL_MS, useAdminAppEditor } from './useAdminAppEditor';
/** Inspecciona URLs manuales y aplica las sugerencias una sola vez por inspección. */
export function useManualInstallerInspection(editor: ReturnType<typeof useAdminAppEditor>, activity: ReturnType<typeof useAdminAppsActivity>) {
  const t = useTranslation();
  const { selected, inspection, manualInstallerUrls, sourcePageUrl, hydratedInspectionRef, detailRequestRef, setEditor } = editor;
  const { inspecting, applying, setMessage, setError, setOperation } = activity;
  const inspectionPolling = Boolean(
    inspection
    && selected
    && ['queued', 'running'].includes(inspection.status),
  );
  usePollingTask({
    enabled: inspectionPolling,
    intervalMs: INSPECTION_POLL_MS,
    pollKey: selected && inspection ? `${selected.id}:${inspection.id}` : null,
    task: async (signal) => {
      if (!selected || !inspection) return false;
      const appId = selected.id;
      const next = await fetchManualInstallerInspection(appId, inspection.id, signal);
      if (signal.aborted || next.appId !== appId) return false;
      setEditor('inspection', next);
      setError(null);
      return next.status === 'queued' || next.status === 'running';
    },
    onError: (requestError) => {
      setError(errorMessage(t, requestError, 'admin.apps.error.inspectionProgress'));
    },
  });

  useEffect(() => {
    if (
      inspection?.status !== 'ready'
      || !inspection.suggestions
      || hydratedInspectionRef.current === inspection.id
    ) {
      return;
    }
    hydratedInspectionRef.current = inspection.id;
    setEditor('form', (current) => formFromSuggestions(current, inspection.suggestions!));
    const readyInstallers = manualInspectionInstallers(inspection);
    const detectedOperatingSystem = readyInstallers.length === 1
      ? readyInstallers[0].operatingSystem
      : null;
    if (detectedOperatingSystem) {
      setEditor('operatingSystem', detectedOperatingSystem);
    }
    setMessage(t('admin.apps.inspection.ready'));
  }, [inspection, hydratedInspectionRef, setEditor, setMessage, t]);

  async function startInspection() {
    if (!selected || inspecting || applying) return;
    const validationError = validateManualUrls(
      t,
      manualInstallerUrls,
      sourcePageUrl,
    );
    if (validationError) {
      setError(validationError);
      return;
    }
    const requestId = detailRequestRef.current;
    setOperation('inspecting', true);
    setMessage(null);
    setError(null);
    try {
      const createdInspection = await createManualInstallerInspection(selected.id, {
        installerUrls: {
          windows: manualInstallerUrls.windows.trim() || null,
          macos: manualInstallerUrls.macos.trim() || null,
          linux: manualInstallerUrls.linux.trim() || null,
        },
        sourcePageUrl: sourcePageUrl.trim(),
      });
      if (requestId !== detailRequestRef.current) return;
      setEditor('inspection', createdInspection);
      hydratedInspectionRef.current = null;
      setMessage(t('admin.apps.inspection.queued'));
    } catch (requestError) {
      if (requestId === detailRequestRef.current) {
        setError(errorMessage(t, requestError, 'admin.apps.error.inspectionCreate'));
      }
    } finally {
      setOperation('inspecting', false);
    }
  }

  function inspectOnEnter(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    void startInspection();
  }

  return { startInspection, inspectOnEnter };
}
