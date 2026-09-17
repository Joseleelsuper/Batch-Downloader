import {
  Loader2,
  Wand2
} from 'lucide-react';
import { useTranslation } from '../../services/i18n';
import type {
  OperatingSystem
} from '../../types/catalog';
import {
  InspectionFeedback,
  InspectionStatus,
  InstallerEvidence,
  manualInspectionInstallers
} from './AdminAppsSupport';
import { InstallerUrlFields } from './InstallerUrlFields';
import { useAdminAppsActivity } from './useAdminAppsActivity';

import { useAdminAppEditor } from './useAdminAppEditor';
import { useManualInstallerInspection } from './useManualInstallerInspection';
/** Recoge URLs por plataforma y presenta evidencias antes de publicar instaladores. */
export function ManualInstallerPanel({ editor, activity, inspectionActions }: Readonly<{ editor: ReturnType<typeof useAdminAppEditor>; activity: ReturnType<typeof useAdminAppsActivity>; inspectionActions: ReturnType<typeof useManualInstallerInspection>; }>) {
  const t = useTranslation();
  const { inspection, manualInstallerUrls, sourcePageUrl, operatingSystem, setEditor } = editor;
  const { inspecting, applying } = activity;
  const { startInspection, inspectOnEnter } = inspectionActions;
  return (
    <section className="manual-installer-panel" aria-labelledby="manual-installer-title">
      <div className="manual-installer-heading">
        <div>
          <span>{t('admin.apps.manual.kicker')}</span>
          <h4 id="manual-installer-title">{t('admin.apps.manual.title')}</h4>
        </div>
        {inspection ? <InspectionStatus inspection={inspection} /> : null}
      </div>
      <p>{t('admin.apps.manual.description')}</p>
      <div className="manual-installer-steps" aria-label={t('admin.apps.manual.stepsLabel')}>
        <span className={inspection ? 'is-complete' : 'is-current'}>
          <strong>1</strong>{t('admin.apps.manual.step.urls')}
        </span>
        <span className={stepTwoClass(inspection)}>
          <strong>2</strong>{t('admin.apps.manual.step.preview')}
        </span>
        <span className={inspection?.status === 'ready' ? 'is-current' : ''}>
          <strong>3</strong>{t('admin.apps.manual.step.publish')}
        </span>
      </div>
      <div
        className="manual-installer-form"
        aria-busy={inspecting || inspection?.status === 'queued' || inspection?.status === 'running'}
      >
        <label className="manual-source-page-field" htmlFor="source-page-url">
          <span>{t('admin.apps.manual.sourcePageUrl')}</span>
          <input
            id="source-page-url"
            type="url"
            inputMode="url"
            maxLength={2048}
            value={sourcePageUrl}
            onChange={(event) => setEditor('sourcePageUrl', event.target.value)}
            onKeyDown={inspectOnEnter}
            placeholder="https://example.com/download"
            aria-describedby="manual-source-page-help"
            disabled={inspecting || applying}
          />
          <small id="manual-source-page-help">
            {t('admin.apps.manual.sourcePageHelp')}
          </small>
        </label>
        <InstallerUrlFields mode="manual" values={manualInstallerUrls}
          onChange={(value) => setEditor('manualInstallerUrls', value)} disabled={inspecting || applying} onKeyDown={inspectOnEnter} />
        <button
          type="button"
          className="secondary-button"
          onClick={() => void startInspection()}
          disabled={inspecting || applying || inspection?.status === 'queued' || inspection?.status === 'running'}
        >
          {inspecting || inspection?.status === 'queued' || inspection?.status === 'running'
            ? <Loader2 className="spin" size={17} />
            : <Wand2 size={17} />}
          {inspection?.status === 'ready'
            ? t('admin.apps.manual.inspectAgain')
            : t('admin.apps.manual.inspect')}
        </button>
      </div>
      {inspection ? <InspectionFeedback inspection={inspection} /> : null}
      {inspection?.status === 'ready' && manualInspectionInstallers(inspection).length > 0 ? (
        <>
          <InstallerEvidence inspection={inspection} />
          {manualInspectionInstallers(inspection).some((installer) => installer.platformRequired) ? (
            <label className="manual-platform-field" htmlFor="manual-platform">
              <span>{t('admin.apps.manual.platform')}</span>
              <select
                id="manual-platform"
                value={operatingSystem}
                onChange={(event) => setEditor('operatingSystem', event.target.value as OperatingSystem)}
              >
                <option value="">{t('admin.apps.manual.platformPlaceholder')}</option>
                <option value="windows">Windows</option>
                <option value="macos">macOS</option>
                <option value="linux">Linux</option>
              </select>
              <small>{t('admin.apps.manual.platformHelp')}</small>
            </label>
          ) : null}
        </>
      ) : null}
    </section>
  );
}

function stepTwoClass(inspection: ReturnType<typeof useAdminAppEditor>['inspection']): string {
  if (inspection?.status === 'ready') return 'is-complete';
  if (inspection) return 'is-current';
  return '';
}
