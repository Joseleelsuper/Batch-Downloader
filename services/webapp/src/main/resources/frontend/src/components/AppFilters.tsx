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
import type { FilterKey } from '../types/catalog';
import { t } from '../services/i18n';

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
  selectedCount?: number;
  selectedTags?: string[];
  selectedPublishers?: string[];
  tagMatchMin?: number;
  catalogSearch?: string;
  downloading?: boolean;
  onDownloadSelected?: () => void;
  onClearSelection?: () => void;
  onRemoveTag?: (tag: string) => void;
  onRemovePublisher?: (publisher: string) => void;
  onClearFacets?: () => void;
}

export function AppFilters({
  active,
  counts,
  onChange,
  selectedCount = 0,
  selectedTags = [],
  selectedPublishers = [],
  tagMatchMin = 0,
  catalogSearch = '',
  downloading = false,
  onDownloadSelected,
  onClearSelection,
  onRemoveTag,
  onRemovePublisher,
  onClearFacets,
}: Props) {
  const facetSearch = catalogSearch ? `?${catalogSearch}` : '';
  const activeFacetCount = selectedTags.length + selectedPublishers.length;
  return (
    <aside className="filter-rail">
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
              onClick={() => onChange(filter.key)}
              type="button"
            >
              <Icon size={20} />
              <span>{filter.label}</span>
              <strong>{counts[filter.key].toLocaleString('es-ES')}</strong>
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
      <section className="selection-panel">
        <div>
          <span>{t('catalog.selection.selected')}</span>
          <strong>{selectedCount} / 100</strong>
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
      </section>
    </aside>
  );
}
