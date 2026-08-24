import { ChevronDown } from 'lucide-react';
import { Fragment } from 'react';
import { isCatalogAppSelectable } from '../catalogSelection';
import { useTranslation } from '../services/i18n';
import type { AppDetails, CatalogApp } from '../types/catalog';
import { AppDetailsPanel } from './AppDetailsPanel';
import { OperatingSystemList } from './OperatingSystemIcons';

interface Props {
  apps: CatalogApp[];
  selectedId?: string;
  selectedIds?: Set<string>;
  selectedCount?: number;
  loading?: boolean;
  details?: AppDetails | null;
  loadingDetails?: boolean;
  onToggleDetails: (app: CatalogApp) => void;
  onToggleSelection?: (app: CatalogApp) => void;
}

export function AppTable({
  apps,
  selectedId,
  selectedIds = new Set(),
  selectedCount = 0,
  loading = false,
  details,
  loadingDetails = false,
  onToggleDetails,
  onToggleSelection,
}: Props) {
  const t = useTranslation();
  return (
    <div className="table-card" aria-busy={loading}>
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
            <th className="details-toggle-column"><span className="sr-only">{t('catalog.column.details')}</span></th>
          </tr>
        </thead>
        <tbody>
          {apps.map((app) => {
            const checked = selectedIds.has(app.id);
            const disabled = !isCatalogAppSelectable(app) || (!checked && selectedCount >= 100);
            const expanded = selectedId === app.id;
            const detailsId = `app-details-${app.id}`;
            return (
              <Fragment key={app.id}>
                <tr
                  className={`app-summary-row ${expanded ? 'selected-row' : ''}`}
                  onClick={() => onToggleDetails(app)}
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
                  <td className="details-toggle-column" onClick={(event) => event.stopPropagation()}>
                    <button
                      className="details-toggle"
                      type="button"
                      aria-expanded={expanded}
                      aria-controls={detailsId}
                      aria-label={expanded
                        ? t('catalog.details.close', { name: app.name })
                        : t('catalog.details.open', { name: app.name })}
                      onClick={() => onToggleDetails(app)}
                    >
                      <ChevronDown size={20} />
                    </button>
                  </td>
                </tr>
                <tr
                  className={`app-detail-row ${expanded ? 'app-detail-row-open' : ''}`}
                  aria-hidden={!expanded}
                >
                  <td colSpan={6}>
                    <div className="app-detail-expander">
                      <div
                        className="app-detail-expander-inner"
                        id={detailsId}
                        aria-hidden={!expanded}
                        ref={(element) => {
                          if (element) element.inert = !expanded;
                        }}
                      >
                        <AppDetailsPanel
                          app={details?.id === app.id ? details : null}
                          loading={expanded && loadingDetails}
                        />
                      </div>
                    </div>
                  </td>
                </tr>
              </Fragment>
            );
          })}
        </tbody>
      </table>
      {loading ? <p className="loading-label">{t('common.loading')}</p> : null}
      {!loading && apps.length === 0 ? <p className="empty-state">{t('catalog.empty')}</p> : null}
    </div>
  );
}

function AppIcon({ app }: { app: CatalogApp }) {
  if (app.iconUrl) {
    return <img className="app-icon" src={app.iconUrl} alt="" loading="lazy" />;
  }
  return <span className="app-icon app-icon-fallback">{app.name.slice(0, 1).toUpperCase()}</span>;
}
