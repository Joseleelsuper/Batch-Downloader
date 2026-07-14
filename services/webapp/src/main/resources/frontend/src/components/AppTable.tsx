import type { CatalogApp } from '../types/catalog';
import { t } from '../services/i18n';
import { AppStatusBadge } from './AppStatusBadge';
import { OperatingSystemList } from './OperatingSystemIcons';

interface Props {
  apps: CatalogApp[];
  selectedId?: string;
  selectedIds?: Set<string>;
  selectedCount?: number;
  onSelect: (app: CatalogApp) => void;
  onToggleSelection?: (app: CatalogApp) => void;
}

export function AppTable({
  apps,
  selectedId,
  selectedIds = new Set(),
  selectedCount = 0,
  onSelect,
  onToggleSelection,
}: Props) {
  return (
    <div className="table-card">
      <table className="app-table">
        <thead>
          <tr>
            <th className="selection-column">
              <span className="sr-only">{t('catalog.select')}</span>
            </th>
            <th>{t('catalog.column.app')}</th>
            <th>{t('catalog.column.publisher')}</th>
            <th>S.O.</th>
            <th>{t('catalog.column.version')}</th>
            <th>{t('catalog.column.status')}</th>
          </tr>
        </thead>
        <tbody>
          {apps.map((app) => {
            const checked = selectedIds.has(app.id);
            const disabled = !app.downloadable || (!checked && selectedCount >= 100);
            return (
              <tr
                key={app.id}
                className={selectedId === app.id ? 'selected-row' : ''}
                onClick={() => onSelect(app)}
              >
                <td className="selection-column" onClick={(event) => event.stopPropagation()}>
                  <input
                    type="checkbox"
                    aria-label={t('catalog.selectApp', { name: app.name })}
                    checked={checked}
                    disabled={disabled}
                    onChange={() => onToggleSelection?.(app)}
                  />
                </td>
                <td>
                  <div className="app-cell">
                    <AppIcon app={app} />
                    <span>{app.name}</span>
                  </div>
                </td>
                <td>{app.publisher ?? '-'}</td>
                <td><OperatingSystemList operatingSystems={app.operatingSystems} /></td>
                <td>{app.latestVersion ?? '-'}</td>
                <td>
                  <AppStatusBadge status={app.resolutionStatus} />
                </td>
              </tr>
            );
          })}
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
