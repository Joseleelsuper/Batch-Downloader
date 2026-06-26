import { Copy, ExternalLink, ShieldCheck, X } from 'lucide-react';
import type { ReactNode } from 'react';
import { useState } from 'react';
import type { AppDetails } from '../types/catalog';
import { t } from '../services/i18n';
import { AppStatusBadge } from './AppStatusBadge';

interface Props {
  app?: AppDetails | null;
  loading?: boolean;
  onClose: () => void;
}

export function AppDetailsDrawer({ app, loading, onClose }: Props) {
  const [copied, setCopied] = useState(false);

  async function copyInstallerName() {
    if (!app?.installerFilename) return;
    await navigator.clipboard.writeText(app.installerFilename);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1600);
  }

  return (
    <aside className="details-drawer">
      <button className="drawer-close" onClick={onClose} type="button" aria-label="Cerrar">
        <X size={22} />
      </button>
      {loading ? <DetailsSkeleton /> : null}
      {!loading && app ? (
        <>
          <h2>{app.name}</h2>
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
            <span>{app.installerFilename ?? '-'}</span>
            {app.installerFilename ? (
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
          <DetailBlock label={t('app.details.type')}>{app.installerType ?? '-'}</DetailBlock>
          <DetailBlock label={t('app.details.confidence')}>
            <span className="confidence">
              <ShieldCheck size={20} />
              {confidenceLabel(app.score)}
            </span>
          </DetailBlock>
          <DetailBlock label={t('app.details.status')}>
            <AppStatusBadge status={app.resolutionStatus} />
          </DetailBlock>
          <DetailBlock label={t('app.details.version')}>{app.latestVersion ?? '-'}</DetailBlock>
          <DetailBlock label={t('app.details.updated')}>
            {app.checkedAt ? formatDate(app.checkedAt) : '-'}
          </DetailBlock>
          <DetailBlock label={t('app.details.size')}>{formatSize(app.sizeBytes)}</DetailBlock>
          <DetailBlock label={t('app.details.source')}>{app.sourceLabel}</DetailBlock>
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
        </>
      ) : null}
      {!loading && !app ? <p className="drawer-empty">Selecciona una aplicacion.</p> : null}
    </aside>
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
