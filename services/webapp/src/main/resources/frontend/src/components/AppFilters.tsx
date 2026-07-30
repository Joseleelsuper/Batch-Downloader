import {
  AlertTriangle,
  Building2,
  CheckCircle2,
  Download,
  Grid2X2,
  Tags,
  type LucideIcon,
  SlidersHorizontal,
  X,
  XCircle,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import type { CatalogApp, FilterKey, OperatingSystem } from '../types/catalog';
import { t } from '../services/i18n';
import { OperatingSystemIcon, operatingSystemLabel } from './OperatingSystemIcons';

const MAX_SELECTED_APPS = 100;
const VISIBLE_SELECTED_APPS = 17;

const filters: Array<{
  key: FilterKey;
  label: string;
  icon: LucideIcon;
}> = [
  { key: 'all', label: t('catalog.filter.all'), icon: Grid2X2 },
  { key: 'available', label: t('catalog.filter.available'), icon: CheckCircle2 },
  { key: 'review', label: t('catalog.filter.review'), icon: AlertTriangle },
  { key: 'missing', label: t('catalog.filter.missing'), icon: XCircle },
];

interface Props {
  active: FilterKey;
  counts: Record<FilterKey, number>;
  onChange: (filter: FilterKey) => void;
  selectedApps?: CatalogApp[];
  selectedTags?: string[];
  selectedPublishers?: string[];
  tagMatchMin?: number;
  catalogSearch?: string;
  downloading?: boolean;
  onDownloadSelected?: () => void;
  onClearSelection?: () => void;
  onRemoveSelected?: (appId: string) => void;
  onRemoveTag?: (tag: string) => void;
  onRemovePublisher?: (publisher: string) => void;
  onClearFacets?: () => void;
  operatingSystems?: OperatingSystem[];
  onToggleOperatingSystem?: (operatingSystem: OperatingSystem) => void;
}

export function AppFilters({
  active,
  counts,
  onChange,
  selectedApps = [],
  selectedTags = [],
  selectedPublishers = [],
  tagMatchMin = 0,
  catalogSearch = '',
  downloading = false,
  onDownloadSelected,
  onClearSelection,
  onRemoveSelected,
  onRemoveTag,
  onRemovePublisher,
  onClearFacets,
  operatingSystems = ['windows', 'linux', 'macos'],
  onToggleOperatingSystem,
}: Props) {
  const facetSearch = catalogSearch ? `?${catalogSearch}` : '';
  const activeFacetCount = selectedTags.length + selectedPublishers.length;
  const selectedCount = selectedApps.length;
  const visibleSelectedApps = selectedApps.slice(-VISIBLE_SELECTED_APPS).reverse();
  const hiddenSelectedCount = Math.max(0, selectedCount - VISIBLE_SELECTED_APPS);
  return (
    <aside className="filter-rail" id="catalog-filters">
      <div className="filter-header">
        <span>{t('catalog.filters')}</span>
        <SlidersHorizontal size={18} />
      </div>
      <nav aria-label={t('catalog.filters')}>
        {filters.map((filter) => {
          const Icon = filter.icon;
          return (
            <button
              key={filter.key}
              className={`filter-item ${active === filter.key ? 'filter-item-active' : ''}`}
              aria-pressed={active === filter.key}
              onClick={() => onChange(filter.key)}
              type="button"
            >
              <Icon size={20} />
              <span>{filter.label}</span>
              <strong>{(counts[filter.key] ?? 0).toLocaleString('es-ES')}</strong>
            </button>
          );
        })}
      </nav>
      <section className="facet-links-panel">
        <Link className="facet-link-button" to={`/catalog/tags${facetSearch}`}>
          <Tags size={18} />
          <span>{t('facet.tags.title')}</span>
          <strong>{selectedTags.length.toLocaleString('es-ES')}</strong>
        </Link>
        <Link className="facet-link-button" to={`/catalog/editors${facetSearch}`}>
          <Building2 size={18} />
          <span>{t('facet.publishers.title')}</span>
          <strong>{selectedPublishers.length.toLocaleString('es-ES')}</strong>
        </Link>
        {activeFacetCount ? (
          <div className="active-facet-list">
            <div>
              <span>{t('catalog.activeFilters')}</span>
              <button type="button" onClick={onClearFacets}>
                {t('catalog.selection.clear')}
              </button>
            </div>
            {selectedTags.length ? (
              <small>{t('facet.matchMinValue', { min: tagMatchMin, total: selectedTags.length })}</small>
            ) : null}
            {[...selectedTags.map((value) => ({ value, type: 'tag' as const })), ...selectedPublishers.map((value) => ({ value, type: 'publisher' as const }))].map((item) => (
              <button
                key={`${item.type}-${item.value}`}
                className="active-facet-chip"
                type="button"
                onClick={() => (
                  item.type === 'tag' ? onRemoveTag?.(item.value) : onRemovePublisher?.(item.value)
                )}
              >
                <span>{item.value}</span>
                <X size={14} />
              </button>
            ))}
          </div>
        ) : null}
      </section>
      <section className="platform-filter" aria-label={t('catalog.platforms')}>
        <span>S.O.</span>
        <div>
          {(['windows', 'linux', 'macos'] as OperatingSystem[]).map((operatingSystem) => {
            const active = operatingSystems.includes(operatingSystem);
            return (
              <button
                key={operatingSystem}
                className={`platform-filter-button ${active ? 'platform-filter-button-active' : ''}`}
                type="button"
                aria-pressed={active}
                title={operatingSystemLabel(operatingSystem)}
                aria-label={operatingSystemLabel(operatingSystem)}
                onClick={() => onToggleOperatingSystem?.(operatingSystem)}
              >
                <OperatingSystemIcon operatingSystem={operatingSystem} decorative />
              </button>
            );
          })}
        </div>
      </section>
      <fieldset
        className="selection-panel"
        aria-label={t('catalog.selection.summary', { count: selectedCount, limit: MAX_SELECTED_APPS })}
      >
        <legend className="selection-count" aria-live="polite">
          {selectedCount}/{MAX_SELECTED_APPS}
        </legend>
        <div className="selection-content">
          <div className="selection-app-grid">
            {visibleSelectedApps.map((app) => (
              <button
                key={app.id}
                className="selection-app-button"
                type="button"
                disabled={downloading}
                title={app.name}
                aria-label={t('catalog.selection.removeApp', { name: app.name })}
                onClick={() => onRemoveSelected?.(app.id)}
              >
                {app.iconUrl ? (
                  <img src={app.iconUrl} alt="" loading="lazy" />
                ) : (
                  <span aria-hidden="true">{app.name.slice(0, 1).toUpperCase()}</span>
                )}
              </button>
            ))}
            {hiddenSelectedCount > 0 ? (
              <span
                className="selection-app-more"
                title={t('catalog.selection.more', { count: hiddenSelectedCount })}
                aria-label={t('catalog.selection.more', { count: hiddenSelectedCount })}
              >
                +{hiddenSelectedCount}
              </span>
            ) : null}
          </div>
          <button
            className="primary-button selection-download"
            type="button"
            disabled={selectedCount < 1 || downloading}
            onClick={onDownloadSelected}
          >
            <Download size={17} />
            {downloading ? t('catalog.selection.downloading') : t('catalog.selection.downloadZip')}
          </button>
          <button
            className="secondary-button selection-clear"
            type="button"
            disabled={selectedCount < 1 || downloading}
            onClick={onClearSelection}
          >
            <X size={17} />
            {t('catalog.selection.clear')}
          </button>
        </div>
      </fieldset>
    </aside>
  );
}
