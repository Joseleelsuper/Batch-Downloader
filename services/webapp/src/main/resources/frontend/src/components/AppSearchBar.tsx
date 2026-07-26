import { Check, ChevronDown, Search } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
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
  const [sortOpen, setSortOpen] = useState(false);
  const sortMenuRef = useRef<HTMLDivElement>(null);
  const sortOptions: SortKey[] = ['downloads', 'updated', 'name'];

  useEffect(() => {
    if (!sortOpen) return;
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (!sortMenuRef.current?.contains(event.target as Node)) setSortOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setSortOpen(false);
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [sortOpen]);

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
        {(['lexical', 'semantic'] as const).map((mode) => (
          <button
            key={mode}
            className={searchMode === mode ? 'search-mode-active' : ''}
            type="button"
            aria-pressed={searchMode === mode}
            onClick={() => onSearchModeChange(mode)}
          >
            {mode === 'lexical'
              ? t('catalog.search.mode.lexical')
              : t('catalog.search.mode.semantic')}
          </button>
        ))}
      </div>
      <div className="sort-menu" ref={sortMenuRef}>
        <button
          className="secondary-button sort-menu-trigger"
          onClick={() => setSortOpen((open) => !open)}
          type="button"
          aria-label={t('catalog.sort.toggle')}
          aria-haspopup="listbox"
          aria-expanded={sortOpen}
        >
          <span>{t(`catalog.sort.${sort}`)}</span>
          <ChevronDown className="sort-menu-chevron" size={17} aria-hidden="true" />
        </button>
        {sortOpen ? (
          <div className="sort-menu-popover" role="listbox" aria-label={t('catalog.sort.toggle')}>
            {sortOptions.map((option) => (
              <button
                className="sort-option"
                key={option}
                type="button"
                role="option"
                aria-selected={sort === option}
                onClick={() => {
                  onSortChange(option);
                  setSortOpen(false);
                }}
              >
                <span className="sort-option-copy">
                  <strong>{t(`catalog.sort.${option}`)}</strong>
                  <small>{t(`catalog.sort.${option}.description`)}</small>
                </span>
                {sort === option ? (
                  <span className="sort-option-check" aria-hidden="true">
                    <Check size={15} />
                  </span>
                ) : null}
              </button>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  );
}
