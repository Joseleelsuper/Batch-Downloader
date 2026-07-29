import { Copy, ExternalLink, ShieldCheck, X } from 'lucide-react';
import type { ReactNode } from 'react';
import { useEffect, useMemo, useState } from 'react';
import { isCatalogAppSelectable } from '../catalogSelection';
import type { AppDetails, DownloadOption } from '../types/catalog';
import { t } from '../services/i18n';
import { AppStatusBadge } from './AppStatusBadge';
import { DownloadButton } from './DownloadButton';

/**
 * Componente que representa un panel lateral (drawer) para mostrar los detalles de una aplicación específica.
 */
interface Props {
  app?: AppDetails | null;
  loading?: boolean;
  onClose: () => void;
}

/**
 * Componente que representa un panel lateral (drawer) para mostrar los detalles de una aplicación específica.
 * 
 * @param app  Objeto que contiene los detalles de la aplicación a mostrar. Puede ser nulo si no hay detalles disponibles.
 * @param loading  Indica si los detalles de la aplicación están en proceso de carga. Si es verdadero, se muestra un esqueleto de carga.
 * @param onClose  Función que se llama cuando el usuario cierra el panel lateral. 
 * @returns  Un elemento JSX que representa el panel lateral con los detalles de la aplicación, incluyendo nombre, descripción, estado, instaladores disponibles, y otros metadatos relevantes.
 */
export function AppDetailsDrawer({ app, loading, onClose }: Readonly<Props>) {
  const [copied, setCopied] = useState(false);
  const [selectedOptionId, setSelectedOptionId] = useState<string | null>(null);

  const selectedOption = useMemo(() => {
    const options = app?.downloadOptions ?? [];
    return options.find((option) => option.id === selectedOptionId) ?? preferredOption(options);
  }, [app?.downloadOptions, selectedOptionId]);

  useEffect(() => {
    setSelectedOptionId(preferredOption(app?.downloadOptions ?? [])?.id ?? null);
  }, [app?.id, app?.downloadOptions]);

  async function copyInstallerName() {
    const filename = selectedOption?.filename ?? app?.installerFilename;
    if (!filename) return;
    await navigator.clipboard.writeText(filename);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1600);
  }

  return (
    <aside className="details-drawer" aria-busy={loading ? 'true' : 'false'}>
      {loading || app ? (
        <button className="drawer-close" onClick={onClose} type="button" aria-label={t('common.close')}>
          <X size={22} />
        </button>
      ) : null}
      {loading && !app ? <DetailsSkeleton /> : null}
      {app ? (
        <div className={`drawer-content ${loading ? 'drawer-content-busy' : ''}`}>
          <h2>{app.name}</h2>
          <DetailBlock label={t('app.details.description')}>
            <p className="long-description">
              {app.longDescription || app.description || t('app.details.descriptionPending')}
            </p>
          </DetailBlock>
          {app.tags?.length ? (
            <DetailBlock label={t('app.details.tags')}>
              <div className="tag-list">
                {app.tags.map((tag) => (
                  <span className="tag-chip" key={tag}>
                    {tag}
                  </span>
                ))}
              </div>
            </DetailBlock>
          ) : null}
          <DetailBlock label={t('app.details.official')}>
            {app.officialUrl ? (
              <a href={app.officialUrl} target="_blank" rel="noreferrer">
                {app.officialUrl}
                <ExternalLink size={18} />
              </a>
            ) : (
              <span>-</span>
            )}
          </DetailBlock>
          <DetailBlock label={t('app.details.installer')}>
            <span>{selectedOption?.filename ?? app.installerFilename ?? '-'}</span>
            {selectedOption?.filename || app.installerFilename ? (
              <button
                className="icon-action"
                onClick={copyInstallerName}
                type="button"
                aria-label={t('app.details.copyInstaller')}
                title={t('app.details.copyInstaller')}
              >
                <Copy size={18} />
              </button>
            ) : null}
          </DetailBlock>
          {copied ? <p className="copy-feedback">{t('app.details.copied')}</p> : null}
          <DetailBlock label={t('app.details.type')}>
            {selectedOption ? platformLabel(selectedOption) : app.installerType ?? '-'}
          </DetailBlock>
          <DetailBlock label={t('app.details.confidence')}>
            <span className="confidence">
              <ShieldCheck size={20} />
              {confidenceLabel(app.score)}
            </span>
          </DetailBlock>
          <DetailBlock label={t('app.details.status')}>
            <AppStatusBadge status={app.resolutionStatus} />
          </DetailBlock>
          <DetailBlock label={t('app.details.version')}>{selectedOption?.version ?? app.latestVersion ?? '-'}</DetailBlock>
          <DetailBlock label={t('app.details.updated')}>
            {app.checkedAt ? formatDate(app.checkedAt) : '-'}
          </DetailBlock>
          <DetailBlock label={t('app.details.size')}>{formatSize(app.sizeBytes)}</DetailBlock>
          <DetailBlock label={t('app.details.source')}>{app.sourceLabel}</DetailBlock>
          {app.downloadOptions && app.downloadOptions.length ? (
            <DetailBlock label={t('app.details.installersDetected')}>
              <DownloadOptions
                options={app.downloadOptions}
                selectedOptionId={selectedOption?.id}
                onSelect={setSelectedOptionId}
              />
            </DetailBlock>
          ) : null}
          {selectedOption ? (
            <DownloadButton
              appId={app.id}
              appName={app.name}
              disabled={!isCatalogAppSelectable(app)}
            />
          ) : null}
          <DetailBlock label={t('app.details.notes')}>{app.notes}</DetailBlock>
          {app.originUrl ? (
            <a className="origin-button" href={app.originUrl} target="_blank" rel="noreferrer">
              <ExternalLink size={20} />
              {t('app.details.viewSource')}
            </a>
          ) : (
            <button className="origin-button" disabled type="button">
              <ExternalLink size={20} />
              {t('app.details.viewSource')}
            </button>
          )}
        </div>
      ) : null}
      {!loading && !app ? <p className="drawer-empty">{t('app.details.empty')}</p> : null}
    </aside>
  );
}

function DownloadOptions({
  options,
  selectedOptionId,
  onSelect,
}: Readonly<{
  options: DownloadOption[];
  selectedOptionId?: string;
  onSelect: (id: string) => void;
}>) {
  return (
    <div className="download-options">
      {options.map((option) => (
        <button
          className={`download-option ${option.id === selectedOptionId ? 'download-option-selected' : ''}`}
          key={option.id}
          onClick={() => onSelect(option.id)}
          type="button"
        >
          <span>{option.filename ?? option.finalDomain ?? '-'}</span>
          <small>
            {platformLabel(option)} - {option.sourceLabel} - {option.score}
            {option.isLatest ? ` - ${t('app.details.latestInstaller')}` : ''}
          </small>
        </button>
      ))}
    </div>
  );
}

function DetailBlock({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="detail-block">
      <span className="detail-label">{label}</span>
      <div className="detail-value">{children}</div>
    </div>
  );
}

function DetailsSkeleton() {
  return (
    <div className="details-skeleton">
      <span />
      <span />
      <span />
      <span />
    </div>
  );
}

function confidenceLabel(score?: number | null): string {
  if (!score) return t('confidence.low');
  if (score >= 90) return t('confidence.high');
  if (score >= 50) return t('confidence.medium');
  return t('confidence.low');
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('es-ES', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

function formatSize(value?: number | null): string {
  if (!value) return '-';
  return `${(value / 1024 / 1024).toLocaleString('es-ES', {
    maximumFractionDigits: 1,
  })} MB`;
}

function preferredOption(options: DownloadOption[]): DownloadOption | undefined {
  if (!options.length) return undefined;
  const os = detectedOperatingSystem();
  return (
    options.find((option) => option.operatingSystem === os && option.isLatest) ??
    options.find((option) => option.operatingSystem === os) ??
    options.find((option) => option.isPrimary) ??
    options[0]
  );
}

function detectedOperatingSystem(): string {
  const text = `${navigator.platform} ${navigator.userAgent}`.toLowerCase();
  if (text.includes('mac')) return 'macos';
  if (text.includes('linux')) return 'linux';
  return 'windows';
}

function platformLabel(option: DownloadOption): string {
  const os = option.operatingSystem === 'macos' ? 'macOS' : option.operatingSystem;
  const extension = option.extension?.toUpperCase().replace('.', '') ?? '-';
  return `${os} · ${option.architecture} · ${option.version ?? '-'} · ${extension}`;
}
