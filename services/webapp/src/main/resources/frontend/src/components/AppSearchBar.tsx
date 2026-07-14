import { Search } from 'lucide-react';
import { t } from '../services/i18n';
import type { SortKey } from '../types/catalog';

interface Props {
  value: string;
  sort: SortKey;
  onChange: (value: string) => void;
  onSortChange: (sort: SortKey) => void;
}

export function AppSearchBar({ value, sort, onChange, onSortChange }: Props) {
  const nextSort = sort === 'updated' ? 'name' : 'updated';

  return (
    <div className="search-row">
      <label className="search-input">
        <Search size={22} />
        <input
          aria-label={t('catalog.searchPlaceholder')}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={t('catalog.searchPlaceholder')}
        />
      </label>
      <button
        className="secondary-button"
        onClick={() => onSortChange(nextSort)}
        type="button"
        aria-label={t('catalog.sort.toggle')}
      >
        {sort === 'updated' ? t('catalog.sort.updated') : t('catalog.sort.name')}
      </button>
    </div>
  );
}
