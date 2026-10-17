import { ChevronDown } from 'lucide-react';
import { Fragment } from 'react';
import { isCatalogAppSelectable } from '../catalogSelection';
import { useCompactLayout } from '../hooks/useCompactLayout';
import { useTranslation } from '../services/i18n';
import type { AppDetails, CatalogApp } from '../types/catalog';
import { AppDetailExpander } from './AppDetailExpander';
import { AppDetailsPanel } from './AppDetailsPanel';
import { OperatingSystemList } from './OperatingSystemIcons';

interface Props {
  apps: CatalogApp[];
  selectedId?: string;
  selectedIds?: Set<string>;
  selectedCount?: number;
  loading?: boolean;
  showLoadingLabel?: boolean;
  showEmptyState?: boolean;
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
  showLoadingLabel = true,
  showEmptyState = true,
  details,
  loadingDetails = false,
  onToggleDetails,
  onToggleSelection,
}: Props) {
  const t = useTranslation();
  const compact = useCompactLayout();
  const selectionHeading = <th className="selection-column"><span className="sr-only">{t('catalog.select')}</span></th>;
  return (
    <div className="table-card" aria-busy={loading}>
      <table className="app-table">
        <thead>
          <tr>
            {!compact ? selectionHeading : null}
            <th>{t('catalog.column.app')}</th>
            {compact ? selectionHeading : <>
              <th>{t('catalog.column.publisher')}</th>
              <th>S.O.</th>
              <th>{t('catalog.column.version')}</th>
            </>}
            <th className="details-toggle-column"><span className="sr-only">{t('catalog.column.details')}</span></th>
          </tr>
        </thead>
        <tbody>
          {apps.map((app) => {
            const checked = selectedIds.has(app.id);
            const disabled = !isCatalogAppSelectable(app) || (!checked && selectedCount >= 100);
            const expanded = selectedId === app.id;
            const detailsId = `app-details-${app.id}`;
            const selectionCell = (
              <td className="selection-column" onClick={(event) => event.stopPropagation()}>
                <label className="app-selection-target">
                  <input
                    type="checkbox"
                    aria-label={t('catalog.selectApp', { name: app.name })}
                    checked={checked}
                    disabled={disabled}
                    onChange={() => onToggleSelection?.(app)}
                  />
                </label>
              </td>
            );
            const appLink = <a href={`/catalog/app/${app.id}`} onClick={(event) => {
              event.stopPropagation();
              if (event.button || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
              event.preventDefault();
              onToggleDetails(app);
            }}>{app.name}</a>;
            return (
              <Fragment key={app.id}>
                <tr
                  className={`app-summary-row ${expanded ? 'selected-row' : ''}`}
                  onClick={() => onToggleDetails(app)}
                >
                  {!compact ? selectionCell : null}
                  <td className="app-name-cell">
                    <div className="app-cell">
                      <AppIcon app={app} />
                      {compact ? <div className="app-summary-content">
                        {appLink}
                        <span className="app-summary-publisher">{app.publisher ?? '-'}</span>
                        <div className="app-summary-metadata">
                          <OperatingSystemList operatingSystems={app.operatingSystems} />
                          <span className="app-summary-version" aria-label={`${t('catalog.column.version')} ${app.latestVersion ?? '-'}`}>
                            {app.latestVersion ? `v${app.latestVersion}` : '-'}
                          </span>
                        </div>
                      </div> : appLink}
                    </div>
                  </td>
                  {compact ? selectionCell : <>
                    <td className="app-publisher-cell">{app.publisher ?? '-'}</td>
                    <td className="app-platforms-cell"><OperatingSystemList operatingSystems={app.operatingSystems} /></td>
                    <td className="app-version-cell" data-label={t('catalog.column.version')}>{app.latestVersion ?? '-'}</td>
                  </>}
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
                  <td colSpan={compact ? 3 : 6}>
                    <AppDetailExpander expanded={expanded} id={detailsId}>
                      <AppDetailsPanel
                        app={details?.id === app.id ? details : null}
                        loading={expanded && loadingDetails}
                      />
                    </AppDetailExpander>
                  </td>
                </tr>
              </Fragment>
            );
          })}
        </tbody>
      </table>
      {loading && showLoadingLabel ? <p className="loading-label">{t('common.loading')}</p> : null}
      {!loading && showEmptyState && apps.length === 0 ? <p className="empty-state">{t('catalog.empty')}</p> : null}
    </div>
  );
}

function AppIcon({ app }: { app: CatalogApp }) {
  if (app.iconUrl) {
    return <img className="app-icon" src={app.iconUrl} alt="" loading="lazy" />;
  }
  return <span className="app-icon app-icon-fallback">{app.name.slice(0, 1).toUpperCase()}</span>;
}
