import { Search } from 'lucide-react';
import { t } from '../services/i18n';
import type { SearchMode, SortKey } from '../types/catalog';

interface Props {
  value: string;
  sort: SortKey;
  searchMode: SearchMode;
  onChange: (value: string) => void;
  onSortChange: (sort: SortKey) => void;
  onSearchModeChange: (mode: SearchMode) => void;
}

export function AppSearchBar({
  value,
  sort,
  searchMode,
  onChange,
  onSortChange,
  onSearchModeChange,
}: Props) {
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
      <div className="search-mode-toggle" role="group" aria-label={t('catalog.search.mode')}>
        {(['lexical', 'hybrid'] as const).map((mode) => (
          <button
            key={mode}
            className={searchMode === mode ? 'search-mode-active' : ''}
            type="button"
            aria-pressed={searchMode === mode}
            onClick={() => onSearchModeChange(mode)}
          >
            {mode === 'lexical'
              ? t('catalog.search.mode.lexical')
              : t('catalog.search.mode.hybrid')}
          </button>
        ))}
      </div>
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
