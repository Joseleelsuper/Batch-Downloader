import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchBundleFacets } from '../../api/bundles';
import { bundleFiltersToSearch, bundleRequest } from '../../bundleCatalogFilters';
import { toggleValue } from '../../catalogFilters';
import { useTranslation } from '../../services/i18n';

import { useBundleFilters } from './useBundleFilters';

const ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'];
const BASE = '/catalog/bundles';

export function BundleFacetDirectoryPage({ kind }: { kind: 'tags' | 'creators' }) {
  const { filters, key, update } = useBundleFilters();
  const t = useTranslation();
  const resource = useAsyncResource((signal) => fetchBundleFacets(bundleRequest(filters), signal), { scope: key });
  const facets = resource.data ?? { tags: [], creators: [] };
  const loading = resource.loading;
  const error = resource.error;
  const [letter, setLetter] = useState('A');
  const title = t(kind === 'tags' ? 'facet.tags.title' : 'bundle.catalog.creators');
  const items = facets[kind];
  const activeLetter = items.some((item) => item.letter === letter) ? letter
    : ALPHABET.find((candidate) => items.some((item) => item.letter === candidate)) ?? 'A';
  return <main className="facet-page content-page">
    <section className="facet-header"><div><h1>{title}</h1></div>
      <Link className="secondary-button facet-back-link" to={`${BASE}?${bundleFiltersToSearch(filters)}`}>{t('facet.backToCatalog')}</Link>
    </section>
    <AsyncContent {...resource} hasData={resource.data !== undefined} onRetry={resource.refresh} errorMessage={t('bundle.loadError')}>
    <nav className="facet-letter-nav" aria-label={t('facet.lettersAria', { title })}>
      {ALPHABET.map((value) => <button type="button" key={value} disabled={!items.some((item) => item.letter === value)}
        className={activeLetter === value ? 'facet-letter-active' : ''} aria-pressed={activeLetter === value} onClick={() => setLetter(value)}>{value}</button>)}
    </nav>
    <section className="facet-list-panel"><div className="facet-chip-grid">
      {items.filter((item) => item.letter === activeLetter).map((item) => {
        const selected = kind === 'tags' ? filters.tags.includes(item.value) : filters.creator === item.value;
        return <button type="button" key={item.value} className={`facet-chip ${selected ? 'facet-chip-active' : ''}`} aria-pressed={selected}
          onClick={() => update(kind === 'tags' ? { tags: toggleValue(filters.tags, item.value) } : { creator: selected ? undefined : item.value })}>
          <span>{item.label}</span><strong>{item.count}</strong>
        </button>;
      })}
    </div>{!loading && !error && !items.some((item) => item.letter === activeLetter) ? <p className="empty-state">{t('facet.emptyLetter')}</p> : null}</section>
    </AsyncContent>
  </main>;
}
