import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { useEffect, useMemo, useState } from 'react';
import {
  Link, useSearchParams
} from 'react-router-dom';
import {
  fetchCatalogFacets
} from '../../api/catalogApps';
import {
  catalogFiltersToSearchParams,
  nextFilters,
  normalizeCatalogStatus,
  parseCatalogFilters,
  preferredCatalogFilter,
  preferredCatalogSearchMode, toggleValue,
  type CatalogFilterState
} from '../../catalogFilters';
import { useLocale, useTranslation } from '../../services/i18n';
import type { FacetItem } from '../../types/catalog';

import { prefetchCatalogPage } from './catalogCache';

const FACET_ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')];

export function FacetDirectoryPage({ kind }: { kind: 'tags' | 'publishers' }) {
  const t = useTranslation();
  const locale = useLocale();
  const [searchParams, setSearchParams] = useSearchParams();
  const searchKey = searchParams.toString();
  const preferredSearchMode = useMemo(
    () => preferredCatalogSearchMode(
      searchKey,
      localStorage.getItem('catalog.search.mode'),
    ),
    [searchKey],
  );
  const preferredFilter = useMemo(
    () => preferredCatalogFilter(
      searchKey,
      localStorage.getItem('catalog.filter.status'),
    ),
    [searchKey],
  );
  const canonicalSearchKey = useMemo(
    () => normalizeCatalogStatus(searchKey, preferredSearchMode, preferredFilter),
    [preferredFilter, preferredSearchMode, searchKey],
  );
  const catalogStatusCanonical = canonicalSearchKey === searchKey;
  const filters = useMemo(() => parseCatalogFilters(canonicalSearchKey), [canonicalSearchKey]);
  const resource = useAsyncResource(() => fetchCatalogFacets({
    query: filters.query, filter: filters.filter, tags: filters.tags, publisher: filters.publisher,
    operatingSystems: filters.operatingSystems.length === 3 ? undefined : filters.operatingSystems,
    architecture: filters.architecture, minimumTags: filters.minimumTags, searchMode: filters.searchMode,
  }), { scope: canonicalSearchKey, enabled: catalogStatusCanonical });
  const facets = resource.data ?? { tags: [], publishers: [] };
  const [activeLetter, setActiveLetter] = useState('A');
  const loading = resource.loading || !catalogStatusCanonical;
  const error = resource.error;
  const items = kind === 'tags' ? facets.tags : facets.publishers;
  const selectedValues = kind === 'tags' ? filters.tags : filters.publisher ? [filters.publisher] : [];
  const selectedSet = new Set(selectedValues);
  const lettersWithItems = useMemo(() => new Set(items.map((item) => item.letter)), [items]);
  const visibleItems = items.filter((item) => item.letter === activeLetter);
  const title = kind === 'tags' ? t('facet.tags.title') : t('facet.publishers.title');
  const subtitle = kind === 'tags' ? t('facet.tags.subtitle') : t('facet.publishers.subtitle');

  useEffect(() => {
    if (!catalogStatusCanonical) {
      setSearchParams(canonicalSearchKey, { replace: true });
    }
  }, [canonicalSearchKey, catalogStatusCanonical, setSearchParams]);

  useEffect(() => {
    if (!items.length || lettersWithItems.has(activeLetter)) return;
    const firstLetter = FACET_ALPHABET.find((letter) => lettersWithItems.has(letter)) ?? 'A';
    setActiveLetter(firstLetter);
  }, [activeLetter, items.length, lettersWithItems]);

  function updateFilters(patch: Partial<CatalogFilterState>) {
    setSearchParams(catalogFiltersToSearchParams(nextFilters(filters, patch)));
  }

  function toggleFacet(item: FacetItem) {
    if (kind === 'tags') {
      const tags = toggleValue(filters.tags, item.value);
      prefetchCatalogPage(nextFilters(filters, { tags }));
      updateFilters({ tags });
      return;
    }
    const publisher = filters.publisher === item.value ? undefined : item.value;
    prefetchCatalogPage(nextFilters(filters, { publisher }));
    updateFilters({ publisher });
  }

  return (
    <main className="facet-page">
      <section className="facet-header">
        <div>
          <span>{t('facet.header')}</span>
          <h1>{title}</h1>
          <p>{subtitle}</p>
        </div>
        <Link className="secondary-button facet-back-link" to={{ pathname: '/catalog', search: canonicalSearchKey }}>
          {t('facet.backToCatalog')}
        </Link>
      </section>

      <AsyncContent loading={loading} error={error} hasData={resource.data !== undefined} onRetry={resource.refresh} errorMessage={t('facet.loadError')}>
      {!loading && !error && !items.length ? (
        <section className="facet-empty-state">
          <p>{t('facet.emptyCompatible')}</p>
          {filters.tags.length || filters.publisher ? (
            <button
              className="secondary-button"
              type="button"
              onClick={() => updateFilters({ tags: [], publisher: undefined })}
            >
              {t('facet.resetFilters')}
            </button>
          ) : null}
        </section>
      ) : null}
      {items.length ? (
        <>
          <nav className="facet-letter-nav" aria-label={t('facet.lettersAria', { title })}>
            {FACET_ALPHABET.map((letter) => (
              <button
                key={letter}
                className={activeLetter === letter ? 'facet-letter-active' : ''}
                type="button"
                disabled={!lettersWithItems.has(letter)}
                onClick={() => setActiveLetter(letter)}
              >
                {letter}
              </button>
            ))}
          </nav>
          <section className="facet-chip-grid" aria-label={t('facet.availableAria', { title })}>
            {visibleItems.map((item) => (
              <button
                key={`${kind}-${item.normalizedValue}`}
                className={`facet-chip ${selectedSet.has(item.value) ? 'facet-chip-active' : ''}`}
                type="button"
                onClick={() => toggleFacet(item)}
              >
                <span>{item.label}</span>
                <strong>{item.count.toLocaleString(locale)}</strong>
              </button>
            ))}
            {!visibleItems.length ? (
              <p className="empty-state">{t('facet.emptyLetter')}</p>
            ) : null}
          </section>
        </>
      ) : null}
      </AsyncContent>
    </main>
  );
}
