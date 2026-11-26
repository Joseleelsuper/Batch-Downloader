import { Check, ChevronDown, Search } from 'lucide-react';
import type { InputHTMLAttributes } from 'react';
import { useListbox } from '../hooks/useListbox';
import { useTranslation } from '../services/i18n';
import type { SearchMode, SortKey } from '../types/catalog';

interface Props<S extends string = SortKey> {
  value: string;
  sort: S;
  searchMode: SearchMode;
  onChange: (value: string) => void;
  onSortChange: (sort: S) => void;
  onSearchModeChange: (mode: SearchMode) => void;
  inputProps?: Pick<InputHTMLAttributes<HTMLInputElement>, 'id' | 'type' | 'onKeyDown'>;
  showSortDescriptions?: boolean;
  sortOptions?: readonly S[];
  labelPrefix?: string;
  placeholder?: string;
}

export function AppSearchBar<S extends string = SortKey>({
  value,
  sort,
  searchMode,
  onChange,
  onSortChange,
  onSearchModeChange,
  inputProps,
  showSortDescriptions = true,
  sortOptions: configuredOptions,
  labelPrefix = 'catalog.sort',
  placeholder,
}: Props<S>) {
  const t = useTranslation();
  const sortOptions = configuredOptions ?? ['downloads', 'updated', 'name', 'relevance'] as S[];
  const { close, visible, rootProps, triggerProps, listProps, optionProps } = useListbox(
    sortOptions.length, sortOptions.indexOf(sort),
  );

  return (
    <div className="search-row">
      <label className="search-input">
        <Search size={22} />
        <input
          {...inputProps}
          aria-label={placeholder ?? t('catalog.searchPlaceholder')}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder ?? t('catalog.searchPlaceholder')}
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
      <div className="sort-menu" {...rootProps}>
        <button
          {...triggerProps}
          className="secondary-button sort-menu-trigger"
          type="button"
          aria-label={t('catalog.sort.toggle')}
          aria-haspopup="listbox"
        >
          <span>{t(`${labelPrefix}.${sort}`)}</span>
          <ChevronDown className="sort-menu-chevron" size={17} aria-hidden="true" />
        </button>
        {visible ? (
          <div
            {...listProps}
            className="sort-menu-popover"
            role="listbox"
            aria-label={t('catalog.sort.toggle')}
          >
            {sortOptions.map((option, index) => (
              <button
                {...optionProps(index)}
                className="sort-option"
                key={option}
                type="button"
                role="option"
                aria-selected={sort === option}
                onClick={() => {
                  onSortChange(option);
                  close();
                }}
              >
                <span className="sort-option-copy">
                  <strong>{t(`${labelPrefix}.${option}`)}</strong>
                  {showSortDescriptions ? <small>{t(`${labelPrefix}.${option}.description`)}</small> : null}
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
