import {
  useCallback,
  useEffect,
  useReducer,
  useRef
} from 'react';
import {
  fetchAbsenceVerificationSummary,
  fetchAdminApps
} from '../../api/adminApps';
import { fetchCatalogStats } from '../../api/catalogApps';
import { useTranslation } from '../../services/i18n';
import type {
  AdminAppFilter,
  CatalogApp,
  CatalogStats,
  InstallerAbsenceVerificationSummary
} from '../../types/catalog';
import {
  errorMessage,
  isUnresolvedFilter
} from './AdminAppsSupport';

const PAGE_SIZE = 12;
type ListState = 'loading' | 'ready' | 'error';

interface AdminAppsListModel {
  queryInput: string;
  query: string;
  filter: AdminAppFilter;
  page: number;
  pageSize: number;
  total: number;
  apps: CatalogApp[];
  stats: CatalogStats | null;
  absenceSummary: InstallerAbsenceVerificationSummary | null;
  state: ListState;
  reloadToken: number;
}

type AdminAppsListAction =
  | { type: 'patch'; value: Partial<AdminAppsListModel> }
  | { type: 'reload' }
  | { type: 'removeUnresolved'; remaining: CatalogApp[]; filter: AdminAppFilter };

const INITIAL_LIST: AdminAppsListModel = {
  queryInput: '',
  query: '',
  filter: 'unresolved',
  page: 1,
  pageSize: PAGE_SIZE,
  total: 0,
  apps: [],
  stats: null,
  absenceSummary: null,
  state: 'loading',
  reloadToken: 0,
};

function listReducer(
  current: AdminAppsListModel,
  action: AdminAppsListAction,
): AdminAppsListModel {
  if (action.type === 'patch') return { ...current, ...action.value };
  if (action.type === 'reload') {
    return { ...current, reloadToken: current.reloadToken + 1 };
  }
  return {
    ...current,
    apps: action.remaining,
    total: Math.max(0, current.total - (isUnresolvedFilter(action.filter) ? 1 : 0)),
  };
}

/** Consulta y pagina el catálogo, descartando respuestas de filtros anteriores. */
export function useAdminAppsList(setError: (message: string | null) => void) {
  const t = useTranslation();
  const [list, dispatchList] = useReducer(listReducer, INITIAL_LIST);
  const {
    queryInput,
    query,
    filter,
    page,
    pageSize,
    stats,
    reloadToken,
  } = list;

  const listRequestRef = useRef(0);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      dispatchList({ type: 'patch', value: { query: queryInput.trim(), page: 1 } });
    }, 300);
    return () => window.clearTimeout(timer);
  }, [queryInput]);

  const refreshStats = useCallback(() => {
    fetchCatalogStats()
      .then((stats) => dispatchList({ type: 'patch', value: { stats } }))
      .catch(() => dispatchList({ type: 'patch', value: { stats: null } }));
    fetchAbsenceVerificationSummary()
      .then((absenceSummary) => dispatchList({ type: 'patch', value: { absenceSummary } }))
      .catch(() => dispatchList({ type: 'patch', value: { absenceSummary: null } }));
  }, []);

  useEffect(() => {
    refreshStats();
  }, [refreshStats, reloadToken]);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++listRequestRef.current;
    dispatchList({ type: 'patch', value: { state: 'loading' } });
    setError(null);
    fetchAdminApps(
      {
        query,
        filter,
        sort: 'updated',
        page,
        pageSize,
      },
      controller.signal,
    )
      .then((response) => {
        if (requestId !== listRequestRef.current) return;
        dispatchList({
          type: 'patch',
          value: { apps: response.data, total: response.total, state: 'ready' },
        });
        const pages = Math.max(1, Math.ceil(response.total / pageSize));
        if (page > pages) dispatchList({ type: 'patch', value: { page: pages } });
      })
      .catch((requestError) => {
        if (controller.signal.aborted || requestId !== listRequestRef.current) return;
        dispatchList({ type: 'patch', value: { apps: [], total: 0, state: 'error' } });
        setError(errorMessage(t, requestError, 'admin.apps.error.load'));
      });
    return () => controller.abort();
  }, [filter, page, pageSize, query, reloadToken, setError, t]);

  const unresolvedCount = (stats?.filters.review ?? 0) + (stats?.filters.missing ?? 0);
  const filterCounts: Record<AdminAppFilter, number> = {
    unresolved: unresolvedCount,
    all: stats?.filters.all ?? stats?.total ?? 0,
    available: stats?.filters.available ?? 0,
    review: stats?.filters.review ?? 0,
    missing: stats?.filters.missing ?? 0,
  };
  return { ...list, dispatchList, listRequestRef, refreshStats, filterCounts };
}
