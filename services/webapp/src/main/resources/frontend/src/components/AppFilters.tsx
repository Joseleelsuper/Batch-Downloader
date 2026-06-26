import {
  AlertTriangle,
  CheckCircle2,
  Grid2X2,
  type LucideIcon,
  SlidersHorizontal,
  XCircle,
} from 'lucide-react';
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
}

export function AppFilters({ active, counts, onChange }: Props) {
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
    </aside>
  );
}
