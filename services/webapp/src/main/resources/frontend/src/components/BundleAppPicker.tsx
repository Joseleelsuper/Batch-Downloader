import { ArrowDown, ArrowUp, Check, ChevronLeft, ChevronRight, Plus, Search, X } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import { fetchAdminApps } from '../api/adminApps';
import { fetchApps } from '../api/catalogApps';
import { useTranslation } from '../services/i18n';
import type { CatalogApp } from '../types/catalog';
import { AppMiniIcon } from './AppMiniIcon';

const PAGE_SIZE = 20;
const MAX_APPS = 100;

interface Props {
  apps: CatalogApp[];
  onChange: (apps: CatalogApp[]) => void;
  administrator?: boolean;
}

/** Mantiene la selección independiente de la búsqueda paginada y del catálogo visible. */
export function BundleAppPicker({ apps, onChange, administrator = false }: Readonly<Props>) {
  const t = useTranslation();
  const id = useId();
  const [{ query, page }, setSearch] = useState({ query: '', page: 1 });
  const [results, setResults] = useState<CatalogApp[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const selectedIds = new Set(apps.map((app) => app.id));
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setFailed(false);
    const timer = window.setTimeout(() => {
      const params = { query, sort: 'relevance' as const, page, pageSize: PAGE_SIZE };
      const request = administrator
        ? fetchAdminApps({ ...params, filter: 'all' }, controller.signal)
        : fetchApps({ ...params, filter: 'available', searchMode: 'lexical' }, controller.signal);
      void request.then((response) => {
        if (controller.signal.aborted) return;
        setResults(response.data);
        setTotal(response.total);
      }).catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      }).finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    }, 180);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [query, page, administrator, retry]);

  function add(app: CatalogApp) {
    if (!selectedIds.has(app.id) && apps.length < MAX_APPS) onChange([...apps, app]);
  }

  function move(index: number, direction: -1 | 1) {
    const nextIndex = index + direction;
    if (nextIndex < 0 || nextIndex >= apps.length) return;
    const next = [...apps];
    [next[index], next[nextIndex]] = [next[nextIndex], next[index]];
    onChange(next);
    let action = direction === -1 ? 'up' : 'down';
    if (nextIndex === 0) action = 'down';
    else if (nextIndex === apps.length - 1) action = 'up';
    window.requestAnimationFrame(() => document.getElementById(`${id}-${action}-${apps[index].id}`)?.focus());
  }

  function remove(index: number) {
    const neighbor = apps[index + 1] ?? apps[index - 1];
    onChange(apps.filter((app) => app.id !== apps[index].id));
    window.requestAnimationFrame(() => {
      document.getElementById(neighbor ? `${id}-remove-${neighbor.id}` : `${id}-query`)?.focus();
    });
  }

  function searchStatus() {
    if (loading) return t('bundlePicker.loading');
    if (failed) return t('bundlePicker.failed');
    if (!query.trim()) return t('bundlePicker.popular');
    return t(total === 1 ? 'bundlePicker.oneResult' : 'bundlePicker.results', { count: total });
  }

  function renderResults() {
    if (loading) return <div className="bundle-picker-placeholder" aria-hidden="true" />;
    if (failed) return <button type="button" className="secondary-button" onClick={() => setRetry((value) => value + 1)}>{t('bundlePicker.retry')}</button>;
    if (!results.length) return <p className="bundle-picker-empty">{t('bundlePicker.empty')}</p>;
    return <ul className="bundle-picker-list">{results.map((app) => {
      const selected = selectedIds.has(app.id);
      return <li className="bundle-picker-row" key={app.id}>
        <AppMiniIcon app={app} />
        <div className="bundle-picker-app"><strong>{app.name}</strong><small>{app.publisher || app.packageId}</small></div>
        <button type="button" className="bundle-picker-add" disabled={selected || apps.length >= MAX_APPS}
          aria-label={t(selected ? 'bundlePicker.addedApp' : 'bundlePicker.addApp', { name: app.name })}
          onClick={() => add(app)}>
          {selected ? <Check size={17} aria-hidden="true" /> : <Plus size={17} aria-hidden="true" />}
          <span>{t(selected ? 'bundlePicker.added' : 'bundlePicker.add')}</span>
        </button>
      </li>;
    })}</ul>;
  }

  return <div className="bundle-app-picker">
    <section className="bundle-picker-panel" aria-labelledby={`${id}-search-title`}>
      <header className="bundle-picker-heading">
        <h3 id={`${id}-search-title`}>{t('bundlePicker.find')}</h3>
        <p>{t('bundlePicker.searchHint')}</p>
      </header>
      <label className="bundle-picker-search" htmlFor={`${id}-query`}>
        <span className="sr-only">{t('bundlePicker.search')}</span>
        <Search size={18} aria-hidden="true" />
        <input id={`${id}-query`} type="search" value={query}
          placeholder={t('bundlePicker.search')}
          onChange={(event) => setSearch({ query: event.target.value, page: 1 })}
          onKeyDown={(event) => { if (event.key === 'Enter') event.preventDefault(); }} />
      </label>
      <p className="bundle-picker-status"><output>{searchStatus()}</output></p>
      <div className="bundle-picker-results" aria-busy={loading}>
        {renderResults()}
      </div>
      <nav className="bundle-picker-pagination" aria-label={t('bundlePicker.pagination')}>
        <button type="button" disabled={loading || failed || page <= 1} aria-label={t('catalog.pagination.previous')}
          onClick={() => setSearch({ query, page: page - 1 })}><ChevronLeft size={18} aria-hidden="true" /></button>
        <span>{t('bundlePicker.page', { page, pages })}</span>
        <button type="button" disabled={loading || failed || page >= pages} aria-label={t('catalog.pagination.next')}
          onClick={() => setSearch({ query, page: page + 1 })}><ChevronRight size={18} aria-hidden="true" /></button>
      </nav>
    </section>
    <section className="bundle-picker-panel bundle-picker-selection" aria-labelledby={`${id}-selected-title`}>
      <header className="bundle-picker-heading">
        <h3 id={`${id}-selected-title`}>{t('bundlePicker.selection')}</h3>
        <p><output>{t('bundlePicker.selected', { count: apps.length, limit: MAX_APPS })}</output></p>
      </header>
      {apps.length >= MAX_APPS ? <p className="bundle-picker-limit"><output>{t('bundlePicker.limit')}</output></p> : null}
      {apps.length ? <ol className="bundle-picker-list bundle-picker-selected-list">{apps.map((app, index) => <li className="bundle-picker-row" key={app.id}>
        <AppMiniIcon app={app} />
        <div className="bundle-picker-app"><strong>{app.name}</strong><small>{app.publisher || app.packageId}</small></div>
        <div className="bundle-picker-row-actions">
          <button id={`${id}-up-${app.id}`} type="button" disabled={index === 0} aria-label={t('bundlePicker.moveUp', { name: app.name })}
            title={t('bundlePicker.moveUp', { name: app.name })} onClick={() => move(index, -1)}><ArrowUp size={16} aria-hidden="true" /></button>
          <button id={`${id}-down-${app.id}`} type="button" disabled={index === apps.length - 1} aria-label={t('bundlePicker.moveDown', { name: app.name })}
            title={t('bundlePicker.moveDown', { name: app.name })} onClick={() => move(index, 1)}><ArrowDown size={16} aria-hidden="true" /></button>
          <button id={`${id}-remove-${app.id}`} type="button" aria-label={t('bundlePicker.remove', { name: app.name })}
            title={t('bundlePicker.remove', { name: app.name })} onClick={() => remove(index)}><X size={16} aria-hidden="true" /></button>
        </div>
      </li>)}</ol> : <p className="bundle-picker-empty">{t('bundlePicker.selectionEmpty')}</p>}
    </section>
  </div>;
}
