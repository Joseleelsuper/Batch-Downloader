import { Search, SlidersHorizontal } from 'lucide-react';
import { t } from '../services/i18n';

interface Props {
  value: string;
  onChange: (value: string) => void;
}

export function AppSearchBar({ value, onChange }: Props) {
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
      <button className="secondary-button" type="button">
        <SlidersHorizontal size={18} />
        {t('catalog.filters')}
      </button>
      <button className="secondary-button" type="button">
        {t('catalog.sort.updated')}
      </button>
    </div>
  );
}
