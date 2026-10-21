import { ChevronDown, ExternalLink } from 'lucide-react';
import type { ReactNode } from 'react';
import { useEffect, useMemo, useState } from 'react';
import { isCatalogAppSelectable } from '../catalogSelection';
import { useCompactLayout } from '../hooks/useCompactLayout';
import { useLocale, useTranslation } from '../services/i18n';
import type { AppDetails, DownloadOption, OperatingSystem } from '../types/catalog';
import { DownloadButton } from './DownloadButton';
import { LiteralHighlight } from './LiteralHighlight';

interface Props {
  app?: AppDetails | null;
  loading?: boolean;
  operatingSystem?: OperatingSystem;
  selectedSourceRef?: string | null;
  onSourceSelect?: (sourceRef: string) => void;
  selectionDisabled?: boolean;
  extraActions?: ReactNode;
  literalQuery?: string;
}

/** Contenido esencial que se despliega bajo una aplicación del catálogo o de un bundle. */
export function AppDetailsPanel({
  app, loading = false, operatingSystem, selectedSourceRef, onSourceSelect,
  selectionDisabled = false, extraActions, literalQuery,
}: Readonly<Props>) {
  const t = useTranslation();
  const locale = useLocale();
  const compact = useCompactLayout();
  const [selectedOptionId, setSelectedOptionId] = useState<string | null>(null);
  const options = useMemo(() => (app?.downloadOptions ?? []).filter(
    (option) => !operatingSystem || option.operatingSystem === operatingSystem,
  ), [app?.downloadOptions, operatingSystem]);
  const selectedOption = useMemo(() => {
    if (onSourceSelect) return options.find((option) => option.id === selectedSourceRef);
    return options.find((option) => option.id === selectedOptionId) ?? preferredOption(options);
  }, [onSourceSelect, options, selectedOptionId, selectedSourceRef]);

  useEffect(() => {
    if (!onSourceSelect) setSelectedOptionId(preferredOption(options)?.id ?? null);
  }, [app?.id, onSourceSelect, options]);

  if (loading && !app) return <DetailsSkeleton />;
  if (!app) return null;

  return (
    <section className={`app-detail-panel ${loading ? 'app-detail-panel-busy' : ''}`} aria-busy={loading}>
      <h3 className="sr-only">{app.name}</h3>
      <DetailBlock className="app-detail-description" label={t('app.details.description')} collapsible={compact}>
        <p className="long-description">
          <LiteralHighlight
            text={(locale === 'en' ? app.longDescriptionEn : app.longDescription)
              || app.longDescription || app.description || t('app.details.descriptionPending')}
            query={literalQuery}
          />
        </p>
      </DetailBlock>

      {app.tags?.length ? (
        <DetailBlock className="app-detail-tags" label={t('app.details.tags')} collapsible={compact}>
          <div className="tag-list">
            {app.tags.map((tag) => <span className="tag-chip" key={tag}>
              <LiteralHighlight text={tag} query={literalQuery} />
            </span>)}
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
          {formatDate(app.checkedAt ?? app.updatedAt, locale)}
        </DetailBlock>
        <DetailBlock label={t('app.details.size')}>{formatSize(app.sizeBytes, locale)}</DetailBlock>
      </div>

      <DetailBlock className="app-detail-installers" label={t('app.details.installersDetected')}>
        {options.length ? (
          <DownloadOptions
            options={options}
            selectedOptionId={selectedOption?.id}
            onSelect={onSourceSelect ?? setSelectedOptionId}
            disabled={selectionDisabled}
          />
        ) : <span>-</span>}
      </DetailBlock>

      <div className="app-detail-actions">
        {selectedOption && !onSourceSelect ? (
          <DownloadButton
            key={selectedOption.id}
            appId={app.id}
            appName={selectedOption.version ? `${app.name} ${selectedOption.version}` : app.name}
            sourceRef={selectedOption.id}
            operatingSystem={selectedOption.operatingSystem === 'linux' ? 'linux' : undefined}
            disabled={!isCatalogAppSelectable(app)}
          />
        ) : null}
        {extraActions}
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
  disabled,
}: Readonly<{
  options: DownloadOption[];
  selectedOptionId?: string;
  onSelect: (id: string) => void;
  disabled: boolean;
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
          disabled={disabled}
          onClick={() => onSelect(option.id)}
        >
          <span>{option.filename ?? option.finalDomain ?? '-'}</span>
          {option.operatingSystem === 'linux' ? (
            <small>{t(option.installationSupport === 'automatic' ? 'linux.support.automatic' : 'linux.support.manual')}</small>
          ) : null}
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
  collapsible = false,
}: {
  label: string;
  children: ReactNode;
  className?: string;
  collapsible?: boolean;
}) {
  if (collapsible) {
    return (
      <details className={`detail-block ${className}`}>
        <summary className="detail-label">{label}<ChevronDown size={18} aria-hidden="true" /></summary>
        <div className="detail-value">{children}</div>
      </details>
    );
  }
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

function formatDate(value: string | null | undefined, locale: string): string {
  if (!value) return '-';
  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

function formatSize(value: number | null | undefined, locale: string): string {
  if (!value) return '-';
  return `${(value / 1024 / 1024).toLocaleString(locale, { maximumFractionDigits: 1 })} MB`;
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
