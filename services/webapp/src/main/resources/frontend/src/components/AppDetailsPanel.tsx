import { ExternalLink } from 'lucide-react';
import type { ReactNode } from 'react';
import { useEffect, useMemo, useState } from 'react';
import { isCatalogAppSelectable } from '../catalogSelection';
import { useTranslation } from '../services/i18n';
import type { AppDetails, DownloadOption } from '../types/catalog';
import { DownloadButton } from './DownloadButton';

interface Props {
  app?: AppDetails | null;
  loading?: boolean;
}

/** Contenido esencial que se despliega bajo una aplicación del catálogo. */
export function AppDetailsPanel({ app, loading = false }: Readonly<Props>) {
  const t = useTranslation();
  const [selectedOptionId, setSelectedOptionId] = useState<string | null>(null);
  const selectedOption = useMemo(() => {
    const options = app?.downloadOptions ?? [];
    return options.find((option) => option.id === selectedOptionId) ?? preferredOption(options);
  }, [app?.downloadOptions, selectedOptionId]);

  useEffect(() => {
    setSelectedOptionId(preferredOption(app?.downloadOptions ?? [])?.id ?? null);
  }, [app?.id, app?.downloadOptions]);

  if (loading && !app) return <DetailsSkeleton />;
  if (!app) return null;

  return (
    <section className={`app-detail-panel ${loading ? 'app-detail-panel-busy' : ''}`} aria-busy={loading}>
      <h3 className="sr-only">{app.name}</h3>
      <DetailBlock className="app-detail-description" label={t('app.details.description')}>
        <p className="long-description">
          {app.longDescription || app.description || t('app.details.descriptionPending')}
        </p>
      </DetailBlock>

      {app.tags?.length ? (
        <DetailBlock className="app-detail-tags" label={t('app.details.tags')}>
          <div className="tag-list">
            {app.tags.map((tag) => <span className="tag-chip" key={tag}>{tag}</span>)}
          </div>
        </DetailBlock>
      ) : null}

      <div className="app-detail-metadata">
        <DetailBlock label={t('app.details.official')}>
          {app.officialUrl ? (
            <a href={app.officialUrl} target="_blank" rel="noreferrer">
              <span>{app.officialUrl}</span>
              <ExternalLink size={17} />
            </a>
          ) : <span>-</span>}
        </DetailBlock>
        <DetailBlock label={t('app.details.version')}>
          {selectedOption?.version ?? app.latestVersion ?? '-'}
        </DetailBlock>
        <DetailBlock label={t('app.details.updated')}>
          {formatDate(app.checkedAt ?? app.updatedAt)}
        </DetailBlock>
        <DetailBlock label={t('app.details.size')}>{formatSize(app.sizeBytes)}</DetailBlock>
      </div>

      <DetailBlock className="app-detail-installers" label={t('app.details.installersDetected')}>
        {app.downloadOptions?.length ? (
          <DownloadOptions
            options={app.downloadOptions}
            selectedOptionId={selectedOption?.id}
            onSelect={setSelectedOptionId}
          />
        ) : <span>-</span>}
      </DetailBlock>

      <div className="app-detail-actions">
        {selectedOption ? (
          <DownloadButton
            key={selectedOption.id}
            appId={app.id}
            appName={selectedOption.version ? `${app.name} ${selectedOption.version}` : app.name}
            sourceRef={selectedOption.id}
            disabled={!isCatalogAppSelectable(app)}
          />
        ) : null}
        {app.originUrl ? (
          <a className="origin-button" href={app.originUrl} target="_blank" rel="noreferrer">
            <ExternalLink size={18} />
            {t('app.details.viewSource')}
          </a>
        ) : (
          <button className="origin-button" disabled type="button">
            <ExternalLink size={18} />
            {t('app.details.viewSource')}
          </button>
        )}
      </div>
    </section>
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
  const t = useTranslation();
  return (
    <div className="download-options">
      {options.map((option) => (
        <button
          className={`download-option ${option.id === selectedOptionId ? 'download-option-selected' : ''}`}
          key={option.id}
          type="button"
          aria-pressed={option.id === selectedOptionId}
          onClick={() => onSelect(option.id)}
        >
          <span>{option.filename ?? option.finalDomain ?? '-'}</span>
          <small>
            {installerPlatformLabel(option)}
            {option.isLatest ? ` · ${t('app.details.latestInstaller')}` : ''}
          </small>
        </button>
      ))}
    </div>
  );
}

function DetailBlock({
  label,
  children,
  className = '',
}: {
  label: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={`detail-block ${className}`}>
      <span className="detail-label">{label}</span>
      <div className="detail-value">{children}</div>
    </div>
  );
}

function DetailsSkeleton() {
  const t = useTranslation();
  return (
    <div className="details-skeleton" aria-label={t('common.loading')}>
      <span /><span /><span /><span />
    </div>
  );
}

function formatDate(value?: string | null): string {
  if (!value) return '-';
  return new Intl.DateTimeFormat('es-ES', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

function formatSize(value?: number | null): string {
  if (!value) return '-';
  return `${(value / 1024 / 1024).toLocaleString('es-ES', { maximumFractionDigits: 1 })} MB`;
}

function preferredOption(options: DownloadOption[]): DownloadOption | undefined {
  if (!options.length) return undefined;
  const os = detectedOperatingSystem();
  return options.find((option) => option.operatingSystem === os && option.isLatest)
    ?? options.find((option) => option.operatingSystem === os)
    ?? options.find((option) => option.isPrimary)
    ?? options[0];
}

function detectedOperatingSystem(): string {
  const text = `${navigator.platform} ${navigator.userAgent}`.toLowerCase();
  if (text.includes('mac')) return 'macos';
  if (text.includes('linux')) return 'linux';
  return 'windows';
}

function installerPlatformLabel(option: DownloadOption): string {
  const os = option.operatingSystem === 'macos' ? 'macOS' : option.operatingSystem;
  return [os, option.architecture, option.version].filter(Boolean).join(' · ');
}
