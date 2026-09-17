import {
  ArrowLeft,
  Loader2,
  PackageCheck,
  Save,
  Search,
  Trash2,
  Wand2,
} from 'lucide-react';
import { useTranslation, type Translator } from '../../services/i18n';
import { AdminAppFields } from './AdminAppFields';
import { AdminEditorIcon, isUnresolved } from './AdminAppsSupport';
import { LinuxInstallProfiles } from './LinuxInstallProfiles';
import { useAdminAppsActivity } from './useAdminAppsActivity';
import { ManualInstallerPanel } from './ManualInstallerPanel';
import { useAdminAppEditor } from './useAdminAppEditor';
import { useAdminAppsActions } from './useAdminAppsActions';
import { useManualInstallerInspection } from './useManualInstallerInspection';
import { useWebsiteDiscovery } from './useWebsiteDiscovery';
import { WebsiteDiscoveryPanel } from './WebsiteDiscoveryPanel';

type AdminAppEditorPanelProps = Readonly<{
  editor: ReturnType<typeof useAdminAppEditor>;
  activity: ReturnType<typeof useAdminAppsActivity>;
  actions: ReturnType<typeof useAdminAppsActions>;
  inspectionActions: ReturnType<typeof useManualInstallerInspection>;
  discoveryActions: ReturnType<typeof useWebsiteDiscovery>;
}>;

type EditorStateProps = AdminAppEditorPanelProps & { t: Translator };

/** Presenta el estado de carga, el formulario y las acciones de la aplicación seleccionada. */
export function AdminAppEditorPanel({
  editor,
  activity,
  actions,
  inspectionActions,
  discoveryActions,
}: AdminAppEditorPanelProps) {
  const t = useTranslation();
  return (
    <section
      className="admin-apps-detail"
      aria-label={t('admin.apps.editor.label')}
      aria-busy={editor.detailState === 'loading'}
    >
      <AdminEditorState
        editor={editor}
        activity={activity}
        actions={actions}
        inspectionActions={inspectionActions}
        discoveryActions={discoveryActions}
        t={t}
      />
    </section>
  );
}

function AdminEditorState({
  editor,
  activity,
  actions,
  inspectionActions,
  discoveryActions,
  t,
}: EditorStateProps) {
  switch (editor.detailState) {
    case 'empty':
      return <EmptyState t={t} />;
    case 'loading':
      return <LoadingState t={t} closeMobileDetail={editor.closeMobileDetail} />;
    case 'error':
      return (
        <ErrorState
          t={t}
          closeMobileDetail={editor.closeMobileDetail}
          detailAppRef={editor.detailAppRef}
          openApp={editor.openApp}
        />
      );
    case 'ready':
      return (
        <ReadyEditor
          editor={editor}
          activity={activity}
          actions={actions}
          inspectionActions={inspectionActions}
          discoveryActions={discoveryActions}
          t={t}
        />
      );
    default:
      return null;
  }
}

function EmptyState({ t }: Readonly<{ t: Translator }>) {
  return (
    <div className="admin-app-detail-empty">
      <PackageCheck size={30} />
      <h3>{t('admin.apps.editor.emptyTitle')}</h3>
      <p>{t('admin.apps.editor.emptyDescription')}</p>
    </div>
  );
}

function LoadingState({
  t,
  closeMobileDetail,
}: Readonly<{ t: Translator; closeMobileDetail: () => void }>) {
  return (
    <div className="admin-app-detail-empty">
      <button className="admin-app-back" type="button" onClick={closeMobileDetail}>
        <ArrowLeft size={18} />
        {t('common.back')}
      </button>
      <output aria-live="polite">
        <Loader2 className="spin" size={28} />
        <span>{t('admin.apps.details.loading')}</span>
      </output>
    </div>
  );
}

function ErrorState({
  t,
  closeMobileDetail,
  detailAppRef,
  openApp,
}: Readonly<{
  t: Translator;
  closeMobileDetail: () => void;
  detailAppRef: ReturnType<typeof useAdminAppEditor>['detailAppRef'];
  openApp: ReturnType<typeof useAdminAppEditor>['openApp'];
}>) {
  return (
    <div className="admin-app-detail-empty" role="alert">
      <button className="admin-app-back" type="button" onClick={closeMobileDetail}>
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
  );
}

function ReadyEditor({
  editor,
  activity,
  actions,
  inspectionActions,
  discoveryActions,
  t,
}: EditorStateProps) {
  const { selected, creating, websiteDiscovery, inspectionLocksOrdinaryWrite } = editor;
  const { saving, applying, generatingDescription, deletingSelected } = activity;
  return (
    <form
      className="admin-app-editor"
      aria-busy={saving || applying || generatingDescription || deletingSelected}
      onSubmit={actions.saveApp}
    >
      <EditorHeader editor={editor} activity={activity} actions={actions} t={t} />
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
          <EditorActions
            editor={editor}
            activity={activity}
            actions={actions}
            t={t}
            inspectionLocksOrdinaryWrite={inspectionLocksOrdinaryWrite}
          />
        </>
      ) : null}
    </form>
  );
}

function EditorHeader({
  editor,
  activity,
  actions,
  t,
}: Readonly<{
  editor: ReturnType<typeof useAdminAppEditor>;
  activity: ReturnType<typeof useAdminAppsActivity>;
  actions: ReturnType<typeof useAdminAppsActions>;
  t: Translator;
}>) {
  const { selected, creating, form, detailHeadingRef, closeMobileDetail } = editor;
  const { retryingSelected } = activity;
  return (
    <div className="admin-app-editor-header">
      <button className="admin-app-back" type="button" onClick={closeMobileDetail}>
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
          onClick={() => void actions.retrySelectedApp()}
        >
          {retryingSelected ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
          {t('admin.apps.retrySelected')}
        </button>
      ) : null}
    </div>
  );
}

function EditorActions({
  editor,
  activity,
  actions,
  t,
  inspectionLocksOrdinaryWrite,
}: Readonly<{
  editor: ReturnType<typeof useAdminAppEditor>;
  activity: ReturnType<typeof useAdminAppsActivity>;
  actions: ReturnType<typeof useAdminAppsActions>;
  t: Translator;
  inspectionLocksOrdinaryWrite: boolean;
}>) {
  const { selected, inspection } = editor;
  const { saving, applying, generatingDescription, deletingSelected } = activity;
  const saveLabel = saveButtonLabel(t, saving, editor.creating);
  return (
    <div className="admin-app-editor-actions">
      {inspectionLocksOrdinaryWrite ? (
        <output className="admin-app-write-lock" aria-live="polite">
          {t('admin.apps.preview.writeLock')}
        </output>
      ) : null}
      {inspection?.status === 'ready' && selected && isUnresolved(selected) ? (
        <button
          type="button"
          className="primary-button manual-publish-button"
          onClick={() => void actions.publishInspection()}
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
        {saveLabel}
      </button>
      <button
        type="button"
        className="secondary-button"
        onClick={() => void actions.queueDescription()}
        disabled={!selected || saving || applying || generatingDescription || inspectionLocksOrdinaryWrite}
      >
        {generatingDescription ? <Loader2 className="spin" size={17} /> : <Wand2 size={17} />}
        {generatingDescription
          ? t('admin.message.descriptionGenerating')
          : t('admin.app.generateDescription')}
      </button>
      <button
        type="button"
        className="danger-button"
        onClick={() => void actions.removeSelectedApp()}
        disabled={!selected || saving || applying || deletingSelected || inspectionLocksOrdinaryWrite}
      >
        {deletingSelected ? <Loader2 className="spin" size={17} /> : <Trash2 size={17} />}
        {t('admin.app.deleteOne')}
      </button>
    </div>
  );
}

function saveButtonLabel(t: Translator, saving: boolean, creating: boolean): string {
  if (saving) return t('common.saving');
  if (creating) return t('admin.app.titleCreate');
  return t('common.saveChanges');
}
