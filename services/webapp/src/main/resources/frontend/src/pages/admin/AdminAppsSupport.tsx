import { CheckCircle2, ExternalLink, Loader2, Wand2 } from 'lucide-react';
import { ApiRequestError } from '../../api/http';
import { useTranslation, type Translator } from '../../services/i18n';
import type {
  AdminAppFilter,
  AppDetails,
  CatalogApp,
  ManualFieldSuggestion,
  ManualInstallerInspection,
  ManualInstallerSuggestions,
  ManualSuggestionSource,
  OperatingSystem,
  WebsiteAppDiscovery,
} from '../../types/catalog';

export const EMPTY_FORM = {
  name: '',
  publisher: '',
  officialUrl: '',
  latestVersion: '',
  description: '',
  longDescription: '',
  iconUrl: '',
};
export const EMPTY_WEBSITE_INSTALLER_URLS: Record<OperatingSystem, string> = {
  windows: '',
  macos: '',
  linux: '',
};

export type EditorForm = typeof EMPTY_FORM;

export function EditorField({
  id,
  label,
  value,
  onChange,
  provenance,
  type = 'text',
  required = false,
  disabled = false,
  externalHref,
  externalLabel,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  provenance?: ManualSuggestionSource;
  type?: 'text' | 'url';
  required?: boolean;
  disabled?: boolean;
  externalHref?: string | null;
  externalLabel?: string;
}) {
  return (
    <div className="admin-app-field">
      <label className="admin-app-field-label" htmlFor={id}>
        {label}
        {provenance ? <ProvenanceBadge source={provenance} /> : null}
      </label>
      <div className={externalHref ? 'admin-app-field-control admin-app-field-control-linked' : 'admin-app-field-control'}>
        <input
          id={id}
          type={type}
          required={required}
          disabled={disabled}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
        {externalHref && externalLabel ? (
          <a
            className="icon-action admin-app-url-action"
            href={externalHref}
            target="_blank"
            rel="noreferrer"
            aria-label={externalLabel}
            title={externalLabel}
          >
            <ExternalLink size={17} />
          </a>
        ) : null}
      </div>
    </div>
  );
}
export function EditorTextarea({
  id,
  label,
  value,
  onChange,
  provenance,
  rows = 4,
  disabled = false,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  provenance?: ManualSuggestionSource;
  rows?: number;
  disabled?: boolean;
}) {
  return (
    <label className="admin-app-field" htmlFor={id}>
      <span>
        {label}
        {provenance ? <ProvenanceBadge source={provenance} /> : null}
      </span>
      <textarea
        id={id}
        rows={rows}
        disabled={disabled}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

export function ProvenanceBadge({ source }: { source: ManualSuggestionSource }) {
  const t = useTranslation();
  return (
    <small className={`provenance-badge provenance-${source}`}>
      {t(`admin.apps.provenance.${source}` as const)}
    </small>
  );
}

export function InspectionStatus({ inspection }: { inspection: ManualInstallerInspection }) {
  const t = useTranslation();
  const busy = inspection.status === 'queued' || inspection.status === 'running';
  return (
    <span className={`inspection-status inspection-status-${inspection.status}`} role="status">
      {busy ? <Loader2 className="spin" size={14} /> : null}
      {inspection.status === 'ready' ? <CheckCircle2 size={14} /> : null}
      {t(`admin.apps.inspection.status.${inspection.status}` as const)}
    </span>
  );
}

export function DiscoveryStatus({ discovery }: { discovery: WebsiteAppDiscovery }) {
  const t = useTranslation();
  const busy = discovery.status === 'queued' || discovery.status === 'running';
  return (
    <span className={`inspection-status inspection-status-${discovery.status}`} role="status">
      {busy ? <Loader2 className="spin" size={14} /> : null}
      {discovery.status === 'ready' ? <CheckCircle2 size={14} /> : null}
      {t(`admin.apps.website.status.${discovery.status}` as const)}
    </span>
  );
}

export function WebsiteDiscoveryFeedback({
  discovery,
}: {
  discovery: WebsiteAppDiscovery;
}) {
  const t = useTranslation();
  if (discovery.status === 'queued' || discovery.status === 'running') {
    return (
      <div className="inspection-progress" role="status" aria-live="polite">
        <Loader2 className="spin" size={18} />
        <div>
          <strong>{t('admin.apps.website.processing')}</strong>
          <span>{phaseLabel(t, discovery.phase)}</span>
        </div>
      </div>
    );
  }
  if (discovery.status === 'failed' || discovery.status === 'expired') {
    return (
      <div className="inspection-failure" role="alert">
        <strong>{t('admin.apps.website.failedTitle')}</strong>
        <span>{inspectionErrorLabel(t, discovery.errorCode)}</span>
      </div>
    );
  }
  if (discovery.warnings.length > 0) {
    return (
      <div className="inspection-warnings" role="status">
        <strong>{t('admin.apps.inspection.warnings')}</strong>
        <ul>
          {discovery.warnings.map((warning) => (
            <li key={warning}>{warningLabel(t, warning)}</li>
          ))}
        </ul>
      </div>
    );
  }
  return null;
}

export function WebsiteInstallerEvidence({
  discovery,
}: {
  discovery: WebsiteAppDiscovery;
}) {
  const t = useTranslation();
  return (
    <section
      className="website-installer-evidence"
      aria-labelledby="website-installers-title"
    >
      <div>
        <h5 id="website-installers-title">
          {t('admin.apps.website.installersTitle')}
        </h5>
        <span>
          {t('admin.apps.website.installersCount', {
            count: discovery.installers.length,
          })}
        </span>
      </div>
      {discovery.installers.length > 0 ? (
        <ul>
          {discovery.installers.map((installer) => (
            <li key={installer.id}>
              <strong>
                {installer.filename || t('admin.apps.website.installerFallback')}
              </strong>
              <span>
                {[
                  installer.operatingSystem,
                  installer.architecture,
                  installer.version,
                  formatBytes(t, installer.sizeBytes),
                  installer.finalDomain,
                ].filter(Boolean).join(' · ')}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p>{t('admin.apps.website.noInstallers')}</p>
      )}
      {discovery.ai?.status === 'ready' ? (
        <p className="ai-provenance">
          <Wand2 size={15} />
          {t('admin.apps.evidence.ai', {
            provider: discovery.ai.provider || t('common.notAvailable'),
            model: discovery.ai.model || t('common.notAvailable'),
          })}
        </p>
      ) : null}
    </section>
  );
}

export function InspectionFeedback({ inspection }: { inspection: ManualInstallerInspection }) {
  const t = useTranslation();
  if (inspection.status === 'queued' || inspection.status === 'running') {
    return (
      <div className="inspection-progress" role="status" aria-live="polite">
        <Loader2 className="spin" size={18} />
        <div>
          <strong>{t('admin.apps.inspection.processing')}</strong>
          <span>{phaseLabel(t, inspection.phase)}</span>
        </div>
      </div>
    );
  }
  if (inspection.status === 'failed' || inspection.status === 'expired') {
    return (
      <div className="inspection-failure" role="alert">
        <strong>{t('admin.apps.inspection.failedTitle')}</strong>
        <span>{inspectionErrorLabel(t, inspection.errorCode)}</span>
      </div>
    );
  }
  if (inspection.warnings.length > 0) {
    return (
      <div className="inspection-warnings" role="status">
        <strong>{t('admin.apps.inspection.warnings')}</strong>
        <ul>
          {inspection.warnings.map((warning) => <li key={warning}>{warningLabel(t, warning)}</li>)}
        </ul>
      </div>
    );
  }
  return null;
}

export function InstallerEvidence({ inspection }: { inspection: ManualInstallerInspection }) {
  const t = useTranslation();
  const installers = manualInspectionInstallers(inspection);
  return (
    <section className="installer-evidence" aria-labelledby="installer-evidence-title">
      <div>
        <h5 id="installer-evidence-title">{t('admin.apps.evidence.title')}</h5>
        <span>{t('admin.apps.evidence.validatedCount', { count: installers.length })}</span>
      </div>
      <div className="manual-installer-evidence-list">
        {installers.map((installer, index) => {
          const facts = [
            [t('admin.apps.evidence.file'), installer.filename || t('common.notAvailable')],
            [t('admin.apps.evidence.type'), installer.extension?.toUpperCase() || t('common.notAvailable')],
            [t('admin.apps.evidence.mime'), installer.contentType || t('common.notAvailable')],
            [t('admin.apps.evidence.size'), formatBytes(t, installer.sizeBytes)],
            [t('admin.apps.evidence.domain'), installer.finalDomain || t('common.notAvailable')],
            [t('admin.apps.evidence.version'), installer.version || t('common.notAvailable')],
            [t('admin.apps.evidence.architecture'), installer.architecture],
            [
              t('admin.apps.evidence.platform'),
              installer.operatingSystem || t('admin.apps.evidence.choosePlatform'),
            ],
          ];
          return (
            <article key={`${installer.operatingSystem || 'neutral'}-${installer.filename || index}`}>
              <strong>
                {installer.operatingSystem
                  ? operatingSystemLabel(installer.operatingSystem)
                  : t('admin.apps.evidence.neutralInstaller')}
              </strong>
              <dl>
                {facts.map(([label, value]) => (
                  <div key={label}>
                    <dt>{label}</dt>
                    <dd>{value || t('common.notAvailable')}</dd>
                  </div>
                ))}
              </dl>
            </article>
          );
        })}
      </div>
      {inspection.ai?.status === 'ready' ? (
        <p className="ai-provenance">
          <Wand2 size={15} />
          {t('admin.apps.evidence.ai', {
            provider: inspection.ai.provider || t('common.notAvailable'),
            model: inspection.ai.model || t('common.notAvailable'),
          })}
        </p>
      ) : null}
    </section>
  );
}

export function AdminAppIcon({ app }: { app: CatalogApp }) {
  return (
    <span className="admin-app-row-icon" aria-hidden="true">
      {app.iconUrl ? (
        <img className="app-mini-icon" src={app.iconUrl} alt="" loading="lazy" />
      ) : (
        <span className="app-mini-icon app-mini-icon-fallback">
          {app.name.slice(0, 1).toUpperCase()}
        </span>
      )}
    </span>
  );
}

export function AdminEditorIcon({ form }: { form: EditorForm }) {
  return form.iconUrl ? (
    <img className="admin-editor-icon" src={form.iconUrl} alt="" />
  ) : (
    <span className="admin-editor-icon admin-editor-icon-fallback" aria-hidden="true">
      {(form.name || '?').slice(0, 1).toUpperCase()}
    </span>
  );
}

export function formFromApp(app: AppDetails): EditorForm {
  return {
    name: app.name ?? '',
    publisher: app.publisher ?? '',
    officialUrl: app.officialUrl ?? '',
    latestVersion: app.latestVersion ?? '',
    description: app.description ?? '',
    longDescription: app.longDescription ?? '',
    iconUrl: app.iconUrl ?? '',
  };
}

export function formFromSuggestions(
  current: EditorForm,
  suggestions: ManualInstallerSuggestions,
): EditorForm {
  return {
    name: suggestions.name.value ?? current.name,
    publisher: suggestions.publisher.value ?? current.publisher,
    officialUrl: suggestions.officialUrl.value ?? current.officialUrl,
    latestVersion: suggestions.latestVersion.value ?? current.latestVersion,
    description: suggestions.description.value ?? current.description,
    longDescription: suggestions.longDescription.value ?? current.longDescription,
    iconUrl: suggestions.iconUrl.value ?? current.iconUrl,
  };
}

export function suggestionProvenance(
  suggestion: ManualFieldSuggestion | null | undefined,
  currentValue: string,
): ManualSuggestionSource | undefined {
  if (!suggestion) return undefined;
  return (suggestion.value ?? '').trim() === currentValue.trim()
    ? suggestion.source
    : 'manual';
}

export function editorPayload(form: EditorForm) {
  return {
    name: form.name.trim(),
    publisher: nullable(form.publisher),
    officialUrl: nullable(form.officialUrl),
    latestVersion: nullable(form.latestVersion),
    description: nullable(form.description),
    longDescription: nullable(form.longDescription),
    iconUrl: nullable(form.iconUrl),
  };
}

export function nullable(value: string): string | null {
  return value.trim() || null;
}

export function clickableHttpUrl(value: string): string | null {
  try {
    const url = new URL(value.trim());
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null;
  } catch {
    return null;
  }
}

export function isUnresolved(app: AppDetails): boolean {
  return !app.downloadable
    && ['requires_manual_review', 'missing', 'broken'].includes(app.resolutionStatus);
}

export function isUnresolvedFilter(value: AdminAppFilter): boolean {
  return value === 'unresolved' || value === 'review' || value === 'missing';
}

export function filterLabel(t: Translator, filter: AdminAppFilter): string {
  return filter === 'unresolved'
    ? t('admin.apps.filter.unresolved')
    : t(`catalog.filter.${filter}` as const);
}

export function manualInspectionInstallers(
  inspection: ManualInstallerInspection,
): ManualInstallerInspection['installers'] {
  if (inspection.installers?.length) return inspection.installers;
  return inspection.installer ? [inspection.installer] : [];
}

export function operatingSystemLabel(value: OperatingSystem): string {
  if (value === 'macos') return 'macOS';
  if (value === 'linux') return 'Linux';
  return 'Windows';
}

export function validateManualUrls(
  t: Translator,
  installerUrls: Record<OperatingSystem, string>,
  sourcePageUrl: string,
): string | null {
  const sourcePageError = validateHttpsUrl(
    t,
    sourcePageUrl,
    t('admin.apps.manual.sourcePageUrl'),
  );
  if (sourcePageError) return sourcePageError;

  const configured = (['windows', 'macos', 'linux'] as OperatingSystem[])
    .filter((operatingSystem) => installerUrls[operatingSystem].trim());
  if (configured.length === 0) {
    return t('admin.apps.validation.atLeastOneInstaller');
  }
  for (const operatingSystem of configured) {
    const validationError = validateHttpsUrl(
      t,
      installerUrls[operatingSystem],
      t(`admin.apps.manual.${operatingSystem}InstallerUrl` as const),
    );
    if (validationError) return validationError;
  }
  return null;
}

export function validateHttpsUrl(t: Translator, value: string, label: string): string | null {
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password) {
      return t('admin.apps.validation.https', { field: label });
    }
  } catch {
    return t('admin.apps.validation.https', { field: label });
  }
  return null;
}

export function validateOptionalWebsiteInstallerUrls(
  t: Translator,
  values: Record<OperatingSystem, string>,
): string | null {
  for (const operatingSystem of ['windows', 'macos', 'linux'] as OperatingSystem[]) {
    const value = values[operatingSystem].trim();
    if (!value) continue;
    const label = t(`admin.apps.website.${operatingSystem}InstallerUrl` as const);
    const validationError = validateHttpsUrl(t, value, label);
    if (validationError) return validationError;
  }
  return null;
}

export function errorMessage(t: Translator, error: unknown, fallbackKey: string): string {
  if (!(error instanceof ApiRequestError)) return t(fallbackKey as never);
  const knownKey = `admin.apps.error.code.${error.code}`;
  const translated = t(knownKey as never);
  return translated === knownKey ? t(fallbackKey as never) : translated;
}

export function phaseLabel(t: Translator, phase: string): string {
  const key = `admin.apps.inspection.phase.${phase}`;
  const translated = t(key as never);
  return translated === key ? phase.replace(/_/g, ' ') : translated;
}

export function inspectionErrorLabel(t: Translator, code?: string | null): string {
  if (!code) return t('admin.apps.inspection.failedGeneric');
  const key = `admin.apps.error.code.${code}`;
  const translated = t(key as never);
  return translated === key ? t('admin.apps.inspection.failedGeneric') : translated;
}

export function warningLabel(t: Translator, code: string): string {
  if (code.startsWith('ai:')) return t('admin.apps.warning.ai');
  if (code.startsWith('icon:')) return t('admin.apps.warning.icon');
  if (code === 'installers:not_found') return t('admin.apps.warning.installersNotFound');
  if (code.startsWith('installers:')) return t('admin.apps.warning.installersChanged');
  if (code.startsWith('official_url:query_removed_after_')) {
    return t('admin.apps.warning.officialUrlQueryFallback');
  }
  if (code.startsWith('official_url:')) return t('admin.apps.warning.officialUrl');
  if (code.startsWith('source_page:')) return t('admin.apps.warning.sourcePage');
  if (code.startsWith('retry:')) return t('admin.apps.warning.retry');
  return code;
}

export function formatBytes(t: Translator, value?: number | null): string {
  if (!value) return t('common.notAvailable');
  const units = ['B', 'KB', 'MB', 'GB'];
  let amount = value;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024;
    unit += 1;
  }
  return `${amount.toLocaleString('es-ES', { maximumFractionDigits: 1 })} ${units[unit]}`;
}
