import {
  ArrowLeft,
  Loader2,
  PackageCheck,
  Save,
  Search,
  Trash2,
  Wand2
} from 'lucide-react';
import { useTranslation } from '../../services/i18n';
import { AdminAppFields } from './AdminAppFields';
import {
  AdminEditorIcon,
  isUnresolved
} from './AdminAppsSupport';
import { LinuxInstallProfiles } from './LinuxInstallProfiles';
import { useAdminAppsActivity } from './useAdminAppsActivity';

import { ManualInstallerPanel } from './ManualInstallerPanel';
import { useAdminAppEditor } from './useAdminAppEditor';
import { useAdminAppsActions } from './useAdminAppsActions';
import { useManualInstallerInspection } from './useManualInstallerInspection';
import { useWebsiteDiscovery } from './useWebsiteDiscovery';
import { WebsiteDiscoveryPanel } from './WebsiteDiscoveryPanel';
/** Presenta el estado de carga, el formulario y las acciones de la aplicación seleccionada. */
export function AdminAppEditorPanel({ editor, activity, actions, inspectionActions, discoveryActions }: { editor: ReturnType<typeof useAdminAppEditor>; activity: ReturnType<typeof useAdminAppsActivity>; actions: ReturnType<typeof useAdminAppsActions>; inspectionActions: ReturnType<typeof useManualInstallerInspection>; discoveryActions: ReturnType<typeof useWebsiteDiscovery>; }) {
  const t = useTranslation();
  const { selected, detailState, creating, form, inspection, websiteDiscovery, detailHeadingRef, detailAppRef, openApp, closeMobileDetail, inspectionLocksOrdinaryWrite } = editor;
  const { saving, applying, generatingDescription, deletingSelected, retryingSelected } = activity;
  const { saveApp, publishInspection, queueDescription, removeSelectedApp, retrySelectedApp } = actions;
  return (
    <section
      className="admin-apps-detail"
      aria-label={t('admin.apps.editor.label')}
      aria-busy={detailState === 'loading'}
    >
      {detailState === 'empty' ? (
        <div className="admin-app-detail-empty">
          <PackageCheck size={30} />
          <h3>{t('admin.apps.editor.emptyTitle')}</h3>
          <p>{t('admin.apps.editor.emptyDescription')}</p>
        </div>
      ) : null}
      {detailState === 'loading' ? (
        <div className="admin-app-detail-empty" role="status">
          <button
            className="admin-app-back"
            type="button"
            onClick={closeMobileDetail}
          >
            <ArrowLeft size={18} />
            {t('common.back')}
          </button>
          <Loader2 className="spin" size={28} />
          <p>{t('admin.apps.details.loading')}</p>
        </div>
      ) : null}
      {detailState === 'error' ? (
        <div className="admin-app-detail-empty" role="alert">
          <button
            className="admin-app-back"
            type="button"
            onClick={closeMobileDetail}
          >
            <ArrowLeft size={18} />
            {t('common.back')}
          </button>
          <h3>{t('admin.apps.details.errorTitle')}</h3>
          <p>{t('admin.apps.error.details')}</p>
          <button
            type="button"
            className="secondary-button"
            onClick={() => {
              if (detailAppRef.current) void openApp(detailAppRef.current);
            }}
          >
            {t('common.retry')}
          </button>
        </div>
      ) : null}
      {detailState === 'ready' ? (
        <form
          className="admin-app-editor"
          aria-busy={saving || applying || generatingDescription || deletingSelected}
          onSubmit={saveApp}
        >
          <div className="admin-app-editor-header">
            <button
              className="admin-app-back"
              type="button"
              onClick={closeMobileDetail}
            >
              <ArrowLeft size={18} />
              {t('common.back')}
            </button>
            <div className="admin-app-editor-title">
              <AdminEditorIcon form={form} />
              <div>
                <span>{creating ? t('admin.app.newApp') : t('admin.app.editing')}</span>
                <h3 ref={detailHeadingRef} tabIndex={-1}>
                  {creating ? t('admin.app.titleCreate') : selected?.name}
                </h3>
              </div>
            </div>
            {selected ? <small title={selected.id}>{selected.id}</small> : null}
            {selected && isUnresolved(selected) ? (
              <button
                type="button"
                className="secondary-button compact-button"
                disabled={retryingSelected}
                onClick={() => void retrySelectedApp()}
              >
                {retryingSelected
                  ? <Loader2 className="spin" size={16} />
                  : <Search size={16} />}
                {t('admin.apps.retrySelected')}
              </button>
            ) : null}
          </div>

          {selected ? <LinuxInstallProfiles key={selected.id} app={selected} /> : null}
          {creating ? (
            <WebsiteDiscoveryPanel editor={editor} activity={activity} discoveryActions={discoveryActions} />
          ) : null}

          {selected && isUnresolved(selected) ? (
            <ManualInstallerPanel editor={editor} activity={activity} inspectionActions={inspectionActions} />
          ) : null}

          {!creating || websiteDiscovery?.status === 'ready' ? (
            <>
              <AdminAppFields editor={editor} />

              <div className="admin-app-editor-actions">
                {inspectionLocksOrdinaryWrite ? (
                  <p className="admin-app-write-lock" role="status">
                    {t('admin.apps.preview.writeLock')}
                  </p>
                ) : null}
                {inspection?.status === 'ready' && selected && isUnresolved(selected) ? (
                  <button
                    type="button"
                    className="primary-button manual-publish-button"
                    onClick={() => void publishInspection()}
                    disabled={applying || saving}
                  >
                    {applying ? <Loader2 className="spin" size={17} /> : <PackageCheck size={17} />}
                    {applying ? t('admin.apps.publish.applying') : t('admin.apps.publish.action')}
                  </button>
                ) : null}
                <button
                  className="primary-button"
                  type="submit"
                  disabled={saving || applying || inspectionLocksOrdinaryWrite}
                >
                  {saving ? <Loader2 className="spin" size={17} /> : <Save size={17} />}
                  {saving
                    ? t('common.saving')
                    : creating
                      ? t('admin.app.titleCreate')
                      : t('common.saveChanges')}
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  onClick={() => void queueDescription()}
                  disabled={
                    !selected
                    || saving
                    || applying
                    || generatingDescription
                    || inspectionLocksOrdinaryWrite
                  }
                >
                  {generatingDescription
                    ? <Loader2 className="spin" size={17} />
                    : <Wand2 size={17} />}
                  {generatingDescription
                    ? t('admin.message.descriptionGenerating')
                    : t('admin.app.generateDescription')}
                </button>
                <button
                  type="button"
                  className="danger-button"
                  onClick={() => void removeSelectedApp()}
                  disabled={
                    !selected
                    || saving
                    || applying
                    || deletingSelected
                    || inspectionLocksOrdinaryWrite
                  }
                >
                  {deletingSelected
                    ? <Loader2 className="spin" size={17} />
                    : <Trash2 size={17} />}
                  {t('admin.app.deleteOne')}
                </button>
              </div>
            </>
          ) : null}
        </form>
      ) : null}
    </section>
  );
}
