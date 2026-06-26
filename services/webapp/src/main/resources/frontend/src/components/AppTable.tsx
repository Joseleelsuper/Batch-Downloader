import type { CatalogApp } from '../types/catalog';
import { t } from '../services/i18n';
import { AppStatusBadge } from './AppStatusBadge';
import { DownloadButton } from './DownloadButton';

interface Props {
  apps: CatalogApp[];
  selectedId?: string;
  onSelect: (app: CatalogApp) => void;
}

export function AppTable({ apps, selectedId, onSelect }: Props) {
  return (
    <div className="table-card">
      <table className="app-table">
        <thead>
          <tr>
            <th>{t('catalog.column.app')}</th>
            <th>{t('catalog.column.publisher')}</th>
            <th>{t('catalog.column.version')}</th>
            <th>{t('catalog.column.source')}</th>
            <th>{t('catalog.column.status')}</th>
            <th>{t('catalog.column.action')}</th>
          </tr>
        </thead>
        <tbody>
          {apps.map((app) => (
            <tr
              key={app.id}
              className={selectedId === app.id ? 'selected-row' : ''}
              onClick={() => onSelect(app)}
            >
              <td>
                <div className="app-cell">
                  <AppIcon app={app} />
                  <span>{app.name}</span>
                </div>
              </td>
              <td>{app.publisher ?? '-'}</td>
              <td>{app.latestVersion ?? '-'}</td>
              <td>{app.sourceLabel}</td>
              <td>
                <AppStatusBadge status={app.resolutionStatus} />
              </td>
              <td onClick={(event) => event.stopPropagation()}>
                <DownloadButton appId={app.id} disabled={!app.downloadable} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {apps.length === 0 ? <p className="empty-state">{t('catalog.empty')}</p> : null}
    </div>
  );
}

function AppIcon({ app }: { app: CatalogApp }) {
  if (app.iconUrl) {
    return <img className="app-icon" src={app.iconUrl} alt="" loading="lazy" />;
  }
  return <span className="app-icon app-icon-fallback">{app.name.slice(0, 1).toUpperCase()}</span>;
}
