import {
  FormEvent,
  useRef,
  useState
} from 'react';
import {
  applyManualInstallerInspection,
  applyWebsiteAppDiscovery,
  deleteAdminApp,
  deleteAllAdminApps,
  exportAdminAppsCsv,
  generateAdminDescription,
  patchAdminApp
} from '../../api/adminApps';
import { createScraperRun } from '../../api/scraperAdmin';
import { useTranslation } from '../../services/i18n';
import {
  editorPayload,
  errorMessage,
  formFromApp,
  isUnresolvedFilter,
  manualInspectionInstallers,
  warningLabel
} from './AdminAppsSupport';
import { useAdminAppsActivity, type ActivityKey } from './useAdminAppsActivity';

import { useAdminAppEditor, WEBSITE_DISCOVERY_STORAGE_KEY } from './useAdminAppEditor';
import { useAdminAppsList } from './useAdminAppsList';
/** Publica, guarda y elimina aplicaciones preservando bloqueos de inspección y selección vecina. */
export function useAdminAppsActions(editor: ReturnType<typeof useAdminAppEditor>, activity: ReturnType<typeof useAdminAppsActivity>, list: ReturnType<typeof useAdminAppsList>) {
  const t = useTranslation();
  const { selected, creating, form, inspection, websiteDiscovery, operatingSystem, searchInputRef, setEditor, dispatchEditor, openApp, inspectionLocksOrdinaryWrite } = editor;
  const { saving, applying, generatingDescription, deletingSelected, exportingCsv, deletingAll, retryingSelected, setMessage, setError, setOperation } = activity;
  const { filter, apps, dispatchList, refreshStats } = list;
  const [dangerConfirm, setDangerConfirm] = useState('');
  const dangerDialogRef = useRef<HTMLDialogElement>(null);
  /** Mantiene el indicador de esta acción y traduce sus fallos sin bloquear operaciones independientes. */
  async function runAction(key: ActivityKey, errorKey: Parameters<typeof errorMessage>[2], action: () => Promise<void>) {
    setOperation(key, true);
    try {
      await action();
    } catch (requestError) {
      setError(errorMessage(t, requestError, errorKey));
    } finally {
      setOperation(key, false);
    }
  }

  /** Guarda la edición o aplica un descubrimiento listo, conserva sus avisos y recarga el listado. */
  async function saveApp(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving || applying || inspectionLocksOrdinaryWrite) return;
    if (creating && websiteDiscovery?.status !== 'ready') {
      setError(t('admin.apps.website.validation.analyzeFirst'));
      return;
    }
    if (!form.name.trim()) {
      setError(t('admin.app.validation.nameRequired'));
      return;
    }
    await runAction('saving', 'admin.message.saveAppError', async () => {
      setMessage(null);
      setError(null);
      const payload = editorPayload(form);
      const websiteResult = creating && websiteDiscovery
        ? await applyWebsiteAppDiscovery(websiteDiscovery.id, {
          ...payload,
          officialUrl: form.officialUrl.trim(),
        })
        : null;
      const saved = selected
        ? await patchAdminApp(selected.id, payload)
        : websiteResult!.application;
      setEditor('selected', saved);
      setEditor('creating', false);
      setEditor('websiteDiscovery', null);
      window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
      setEditor('form', formFromApp(saved));
      const warningSummary = websiteResult?.warnings.map((warning) => warningLabel(t, warning)).join(' ');
      setMessage([
        t('admin.message.appSaved'),
        websiteResult
          ? t('admin.apps.website.createdInstallers', {
            count: websiteResult.installerCount,
          })
          : '',
        warningSummary,
      ].filter(Boolean).join(' '));
      dispatchList({ type: 'reload' });
    });
  }

  /** Publica los instaladores revisados y abre la ficha vecina o devuelve el foco al buscador. */
  async function publishInspection() {
    if (!selected || inspection?.status !== 'ready' || applying || saving) return;
    if (manualInspectionInstallers(inspection).some((installer) => installer.platformRequired)
      && !operatingSystem) {
      setError(t('admin.apps.validation.platformRequired'));
      return;
    }
    await runAction('applying', 'admin.apps.error.apply', async () => {
      setMessage(null);
      setError(null);
      const applied = await applyManualInstallerInspection(selected.id, inspection.id, {
        expectedAppVersion: inspection.expectedAppVersion,
        ...editorPayload(form),
        operatingSystem: operatingSystem || null,
      });
      const warningSummary = applied.warnings.map((warning) => warningLabel(t, warning)).join(' ');
      const successMessage = [
        t('admin.apps.publish.success', { name: selected.name }),
        warningSummary,
      ].filter(Boolean).join(' ');
      const selectedIndex = apps.findIndex((app) => app.id === selected.id);
      const remaining = apps.filter((app) => app.id !== selected.id);
      dispatchList({ type: 'removeUnresolved', remaining, filter });
      dispatchEditor({ type: 'clearSelection' });
      dispatchList({ type: 'reload' });
      refreshStats();
      if (isUnresolvedFilter(filter) && remaining.length > 0) {
        const next = remaining[Math.min(Math.max(selectedIndex, 0), remaining.length - 1)];
        await openApp(next);
      } else {
        window.requestAnimationFrame(() => searchInputRef.current?.focus());
      }
      setMessage(successMessage);
    });
  }

  /** Solicita generar la descripción de la ficha cuando ninguna inspección impide editarla. */
  async function queueDescription() {
    if (
      !selected
      || saving
      || applying
      || generatingDescription
      || inspectionLocksOrdinaryWrite
    ) return;
    await runAction('generatingDescription', 'admin.message.generateDescriptionError', async () => {
      setMessage(t('admin.message.descriptionGenerating'));
      setError(null);
      await generateAdminDescription(selected.id);
      setMessage(t('admin.message.descriptionQueued'));
    });
  }

  /** Tras la confirmación del usuario, elimina la ficha seleccionada y limpia la selección. */
  async function removeSelectedApp() {
    if (!selected || saving || applying || deletingSelected || inspectionLocksOrdinaryWrite) return;
    if (!window.confirm(t('admin.app.confirm.deleteOne', { name: selected.name }))) return;
    await runAction('deletingSelected', 'admin.message.deleteAppError', async () => {
      await deleteAdminApp(selected.id);
      dispatchEditor({ type: 'clearSelection' });
      setMessage(t('admin.message.appDeleted'));
      dispatchList({ type: 'reload' });
    });
  }

  /** Encola de nuevo la ficha elegida en el scraper y muestra el identificador de la solicitud. */
  async function retrySelectedApp() {
    if (!selected || retryingSelected) return;
    await runAction('retryingSelected', 'admin.message.sendCommandError', async () => {
      setMessage(null);
      setError(null);
      const request = await createScraperRun('selected', [selected.id]);
      setMessage(t('admin.apps.selectedRunQueued', { requestId: request.requestId }));
    });
  }

  /** Descarga el CSV del catálogo manteniendo un indicador independiente del editor. */
  async function exportCsv() {
    if (exportingCsv) return;
    await runAction('exportingCsv', 'admin.message.exportCsvError', async () => {
      setError(null);
      await exportAdminAppsCsv();
    });
  }

  /** Abre el diálogo de borrado completo con la frase de confirmación vacía. */
  function openDangerDialog() {
    setDangerConfirm('');
    dangerDialogRef.current?.showModal();
  }

  /** Borra el catálogo tras comprobar DELETE_ALL y actualiza listado, selección y diálogo. */
  async function removeAllApps() {
    if (deletingAll || dangerConfirm !== 'DELETE_ALL') return;
    await runAction('deletingAll', 'admin.message.deleteAllAppsError', async () => {
      setError(null);
      const result = await deleteAllAdminApps();
      dangerDialogRef.current?.close();
      dispatchList({ type: 'patch', value: { apps: [] } });
      setEditor('selected', null);
      setEditor('detailState', 'empty');
      setDangerConfirm('');
      setMessage(t('admin.message.allAppsDeleted', { count: result.deleted }));
      dispatchList({ type: 'reload' });
    });
  }

  return { saveApp, publishInspection, queueDescription, removeSelectedApp, retrySelectedApp, exportCsv, openDangerDialog, removeAllApps, dangerConfirm, setDangerConfirm, dangerDialogRef };
}
