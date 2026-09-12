import {
  FileDown,
  Loader2,
  Plus,
  Trash2
} from 'lucide-react';
import { useTranslation } from '../../services/i18n';
import { useAdminAppsActivity } from './useAdminAppsActivity';

import { AdminAppEditorPanel } from './AdminAppEditorPanel';
import { AdminAppsList } from './AdminAppsList';
import { useAdminAppEditor } from './useAdminAppEditor';
import { useAdminAppsActions } from './useAdminAppsActions';
import { useAdminAppsList } from './useAdminAppsList';
import { useManualInstallerInspection } from './useManualInstallerInspection';
import { useWebsiteDiscovery } from './useWebsiteDiscovery';
/** Compone el catálogo administrativo y los flujos de edición, inspección y descubrimiento. */
export function AdminAppsPage() {
  const t = useTranslation();
  const activity = useAdminAppsActivity();
  const list = useAdminAppsList(activity.setError);
  const editor = useAdminAppEditor(list.apps, activity);
  const discoveryActions = useWebsiteDiscovery(editor, activity);
  const inspectionActions = useManualInstallerInspection(editor, activity);
  const actions = useAdminAppsActions(editor, activity, list);
  const { detailOpen, detailTriggerRef, startNewApp } = editor;
  const { message, error, exportingCsv, deletingAll } = activity;
  const { absenceSummary } = list;
  const { exportCsv, openDangerDialog, removeAllApps, dangerConfirm, setDangerConfirm, dangerDialogRef } = actions;
  return (
    <section className={`admin-apps-page ${detailOpen ? 'admin-apps-detail-open' : ''}`}>
      <header className="admin-apps-toolbar">
        <div>
          <h2>{t('admin.app.title')}</h2>
          <p>{t('admin.apps.subtitle')}</p>
        </div>
        <div className="button-row">
          <button
            className="secondary-button compact-button"
            type="button"
            onClick={() => void exportCsv()}
            disabled={exportingCsv}
          >
            {exportingCsv ? <Loader2 className="spin" size={17} /> : <FileDown size={17} />}
            {exportingCsv ? t('admin.app.exportingCsv') : t('admin.app.exportCsv')}
          </button>
          <button
            className="secondary-button compact-button"
            type="button"
            onClick={(event) => {
              detailTriggerRef.current = event.currentTarget;
              startNewApp();
            }}
          >
            <Plus size={17} />
            {t('admin.app.new')}
          </button>
          <button className="danger-button compact-button" type="button" onClick={openDangerDialog}>
            <Trash2 size={17} />
            {t('admin.apps.danger.open')}
          </button>
        </div>
      </header>

      {message ? <div className="admin-apps-notice" role="status">{message}</div> : null}
      {error ? <div className="admin-apps-error" role="alert">{error}</div> : null}
      {absenceSummary ? (
        <div className="admin-apps-notice" role="status">
          {t('admin.apps.absenceSummary', {
            active: absenceSummary.active,
            missing: absenceSummary.missing,
            unjustified: absenceSummary.missingWithoutActiveEvidence,
          })}
        </div>
      ) : null}

      <div className="admin-apps-workbench">
        <AdminAppsList editor={editor} list={list} />
        <AdminAppEditorPanel editor={editor} activity={activity} actions={actions} inspectionActions={inspectionActions} discoveryActions={discoveryActions} />
      </div>

      <dialog
        ref={dangerDialogRef}
        className="admin-danger-dialog"
        aria-labelledby="admin-danger-title"
        onClose={() => setDangerConfirm('')}
      >
        <form method="dialog" onSubmit={(event) => event.preventDefault()}>
          <div>
            <h3 id="admin-danger-title">{t('admin.app.danger.title')}</h3>
            <p>{t('admin.app.danger.description')}</p>
          </div>
          <label htmlFor="delete-all-confirmation">
            {t('admin.apps.danger.confirmLabel')}
          </label>
          <input
            id="delete-all-confirmation"
            value={dangerConfirm}
            onChange={(event) => setDangerConfirm(event.target.value)}
            placeholder="DELETE_ALL"
            autoComplete="off"
            disabled={deletingAll}
          />
          <div className="button-row">
            <button
              type="button"
              className="secondary-button"
              onClick={() => dangerDialogRef.current?.close()}
              disabled={deletingAll}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="danger-button"
              onClick={() => void removeAllApps()}
              disabled={deletingAll || dangerConfirm !== 'DELETE_ALL'}
            >
              {deletingAll ? <Loader2 className="spin" size={17} /> : <Trash2 size={17} />}
              {t('admin.app.deleteAll')}
            </button>
          </div>
        </form>
      </dialog>
    </section>
  );
}
