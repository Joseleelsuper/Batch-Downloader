import { Check, ChevronDown, Search } from 'lucide-react';
import {
  type KeyboardEvent as ReactKeyboardEvent,
  useEffect,
  useRef,
  useState,
} from 'react';
import { useTranslation } from '../services/i18n';
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
  const t = useTranslation();
  const [sortOpen, setSortOpen] = useState(false);
  const [activeSortIndex, setActiveSortIndex] = useState(0);
  const sortMenuRef = useRef<HTMLDivElement>(null);
  const sortTriggerRef = useRef<HTMLButtonElement>(null);
  const sortOptionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const sortOptions: SortKey[] = ['downloads', 'updated', 'name'];

  function focusSortOption(index: number) {
    const normalizedIndex = Math.max(0, Math.min(sortOptions.length - 1, index));
    setActiveSortIndex(normalizedIndex);
    window.requestAnimationFrame(() => sortOptionRefs.current[normalizedIndex]?.focus());
  }

  function openSortMenu(index = Math.max(0, sortOptions.indexOf(sort))) {
    setSortOpen(true);
    focusSortOption(index);
  }

  function closeSortMenu({ restoreFocus = false } = {}) {
    setSortOpen(false);
    if (restoreFocus) {
      window.requestAnimationFrame(() => sortTriggerRef.current?.focus());
    }
  }

  function moveSortOption(event: ReactKeyboardEvent<HTMLButtonElement>, index: number) {
    const nextIndex = event.key === 'ArrowDown'
      ? (index + 1) % sortOptions.length
      : event.key === 'ArrowUp'
        ? (index - 1 + sortOptions.length) % sortOptions.length
        : event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? sortOptions.length - 1
            : null;
    if (nextIndex !== null) {
      event.preventDefault();
      focusSortOption(nextIndex);
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      closeSortMenu({ restoreFocus: true });
    }
  }

  useEffect(() => {
    if (!sortOpen) return;
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (!sortMenuRef.current?.contains(event.target as Node)) setSortOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeSortMenu({ restoreFocus: true });
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
          ref={sortTriggerRef}
          className="secondary-button sort-menu-trigger"
          onClick={() => {
            if (sortOpen) closeSortMenu();
            else openSortMenu();
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault();
              openSortMenu(event.key === 'ArrowDown' ? 0 : sortOptions.length - 1);
            }
          }}
          type="button"
          aria-label={t('catalog.sort.toggle')}
          aria-haspopup="listbox"
          aria-expanded={sortOpen}
          aria-controls="catalog-sort-options"
        >
          <span>{t(`catalog.sort.${sort}`)}</span>
          <ChevronDown className="sort-menu-chevron" size={17} aria-hidden="true" />
        </button>
        {sortOpen ? (
          <div
            id="catalog-sort-options"
            className="sort-menu-popover"
            role="listbox"
            aria-label={t('catalog.sort.toggle')}
          >
            {sortOptions.map((option, index) => (
              <button
                ref={(element) => { sortOptionRefs.current[index] = element; }}
                className="sort-option"
                key={option}
                type="button"
                role="option"
                aria-selected={sort === option}
                tabIndex={activeSortIndex === index ? 0 : -1}
                onClick={() => {
                  onSortChange(option);
                  closeSortMenu({ restoreFocus: true });
                }}
                onFocus={() => setActiveSortIndex(index)}
                onKeyDown={(event) => moveSortOption(event, index)}
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
