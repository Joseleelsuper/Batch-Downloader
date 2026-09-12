import {
  Loader2,
  PackageCheck,
  Search
} from 'lucide-react';
import { AppStatusBadge } from '../../components/AppStatusBadge';
import { Pagination } from '../../components/Pagination';
import { useTranslation } from '../../services/i18n';
import type {
  AdminAppFilter
} from '../../types/catalog';
import {
  AdminAppIcon,
  filterLabel
} from './AdminAppsSupport';

import { useAdminAppEditor } from './useAdminAppEditor';
import { useAdminAppsList } from './useAdminAppsList';
const FILTERS: AdminAppFilter[] = [
  'unresolved',
  'review',
  'missing',
  'available',
  'all',
];

/** Presenta filtros y resultados con navegación por teclado y paginación. */
export function AdminAppsList({ editor, list }: { editor: ReturnType<typeof useAdminAppEditor>; list: ReturnType<typeof useAdminAppsList>; }) {
  const t = useTranslation();
  const { detailRequestRef, appListRef, searchInputRef, detailTriggerRef, dispatchEditor, openApp, moveListSelection, selectedListId } = editor;
  const { queryInput, query, filter, page, pageSize, total, apps, dispatchList, listRequestRef, filterCounts } = list;
  const { state: listState } = list;
  return (
    <section className="admin-apps-master" aria-label={t('admin.apps.list.label')}>
      <div className="admin-app-filters" aria-label={t('admin.apps.filters.label')}>
        {FILTERS.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={filter === value}
            className={filter === value ? 'admin-app-filter-active' : ''}
            onClick={() => {
              detailRequestRef.current += 1;
              listRequestRef.current += 1;
              detailTriggerRef.current = null;
              dispatchList({
                type: 'patch',
                value: { filter: value, page: 1, apps: [], total: 0, state: 'loading' },
              });
              dispatchEditor({ type: 'clearSelection' });
            }}
          >
            <span>{filterLabel(t, value)}</span>
            <strong>{filterCounts[value].toLocaleString('es-ES')}</strong>
          </button>
        ))}
      </div>
      <label className="admin-app-search">
        <span className="sr-only">{t('common.searchApps')}</span>
        <Search size={18} aria-hidden="true" />
        <input
          ref={searchInputRef}
          value={queryInput}
          onChange={(event) => dispatchList({
            type: 'patch',
            value: { queryInput: event.target.value },
          })}
          placeholder={t('admin.apps.search.placeholder')}
          autoComplete="off"
        />
      </label>
      <div
        ref={appListRef}
        className="admin-app-list"
        role="listbox"
        aria-busy={listState === 'loading'}
        aria-label={t('admin.apps.list.label')}
      >
        {listState === 'loading' ? (
          <div className="admin-app-list-state" role="status">
            <Loader2 className="spin" size={22} />
            <span>{t('admin.apps.loading')}</span>
          </div>
        ) : null}
        {listState === 'error' ? (
          <div className="admin-app-list-state">
            <p>{t('admin.apps.error.load')}</p>
            <button
              type="button"
              className="secondary-button"
              onClick={() => dispatchList({ type: 'reload' })}
            >
              {t('common.retry')}
            </button>
          </div>
        ) : null}
        {listState === 'ready' && apps.length === 0 ? (
          <div className="admin-app-list-state">
            <PackageCheck size={26} />
            <strong>{t('admin.apps.empty.title')}</strong>
            <p>
              {query
                ? t('admin.apps.empty.search')
                : filter === 'unresolved'
                  ? t('admin.apps.empty.unresolved')
                  : t('admin.apps.empty.filtered')}
            </p>
          </div>
        ) : null}
        {listState === 'ready' ? apps.map((app, index) => (
          <button
            type="button"
            role="option"
            aria-selected={selectedListId === app.id}
            key={app.id}
            className="admin-app-row"
            onClick={(event) => {
              detailTriggerRef.current = event.currentTarget;
              void openApp(app);
            }}
            onKeyDown={(event) => moveListSelection(event, index)}
          >
            <AdminAppIcon app={app} />
            <span className="admin-app-row-copy">
              <strong>{app.name}</strong>
              <small>{app.publisher || t('admin.apps.publisherUnknown')}</small>
            </span>
            <AppStatusBadge status={app.resolutionStatus} />
          </button>
        )) : null}
      </div>
      <Pagination
        page={page}
        pageSize={pageSize}
        total={total}
        onPageChange={(page) => dispatchList({ type: 'patch', value: { page } })}
        onPageSizeChange={(size) => {
          dispatchList({ type: 'patch', value: { pageSize: size, page: 1 } });
        }}
      />
    </section>
  );
}
