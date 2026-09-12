import {
  KeyboardEvent,
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useRef
} from 'react';
import {
  fetchCurrentManualInstallerInspection
} from '../../api/adminApps';
import { fetchAppDetails } from '../../api/catalogApps';
import { ApiRequestError } from '../../api/http';
import { useTranslation } from '../../services/i18n';
import type {
  AppDetails,
  CatalogApp,
  ManualInstallerInspection,
  OperatingSystem,
  WebsiteAppDiscovery
} from '../../types/catalog';
import {
  EMPTY_FORM,
  EMPTY_WEBSITE_INSTALLER_URLS,
  errorMessage,
  formFromApp,
  isUnresolved,
  type EditorForm
} from './AdminAppsSupport';
import { useAdminAppsActivity } from './useAdminAppsActivity';

import type { SetStateAction } from 'react';
export const INSPECTION_POLL_MS = 1200;
export const WEBSITE_DISCOVERY_STORAGE_KEY = 'batch-downloader.admin.website-discovery.v1';
type DetailState = 'empty' | 'loading' | 'ready' | 'error';
interface EditorState {
  selected: AppDetails | null;
  detailState: DetailState;
  detailOpen: boolean;
  creating: boolean;
  form: EditorForm;
  inspection: ManualInstallerInspection | null;
  websiteDiscovery: WebsiteAppDiscovery | null;
  websiteUrl: string;
  websiteInstallerUrls: typeof EMPTY_WEBSITE_INSTALLER_URLS;
  manualInstallerUrls: typeof EMPTY_WEBSITE_INSTALLER_URLS;
  sourcePageUrl: string;
  operatingSystem: OperatingSystem | '';
}
const INITIAL_EDITOR: EditorState = {
  selected: null,
  detailState: 'empty',
  detailOpen: false,
  creating: false,
  form: EMPTY_FORM,
  inspection: null,
  websiteDiscovery: null,
  websiteUrl: '',
  websiteInstallerUrls: EMPTY_WEBSITE_INSTALLER_URLS,
  manualInstallerUrls: EMPTY_WEBSITE_INSTALLER_URLS,
  sourcePageUrl: '',
  operatingSystem: '',
};
type EditorAction =
  | { type: 'update'; update: (state: EditorState) => EditorState }
  | { type: 'beginLoad' }
  | { type: 'clearSelection' }
  | { type: 'create'; websiteUrl: string }
  | { type: 'loaded'; app: AppDetails; inspection: ManualInstallerInspection | null };

/** Abre un borrador limpio o confirma conjuntamente los datos y la inspección de una aplicación. */
function editorReducer(state: EditorState, action: EditorAction): EditorState {
  switch (action.type) {
    case 'update': return action.update(state);
    case 'beginLoad': return { ...INITIAL_EDITOR, selected: state.selected, form: state.form, detailOpen: true, detailState: 'loading' };
    case 'clearSelection': return { ...state, selected: null, creating: false, inspection: null, websiteDiscovery: null, detailOpen: false, detailState: 'empty' };
    case 'create': return { ...INITIAL_EDITOR, creating: true, detailOpen: true, detailState: 'ready', websiteUrl: action.websiteUrl };
    case 'loaded': return { ...state, selected: action.app, form: formFromApp(action.app), inspection: action.inspection, detailState: 'ready' };
  }
}
/** Carga la selección, conserva su inspección y coordina el foco del editor y del listado. */
export function useAdminAppEditor(apps: CatalogApp[], activity: ReturnType<typeof useAdminAppsActivity>) {
  const t = useTranslation();
  const { setMessage, setError, setOperation } = activity;
  const [editor, dispatchEditor] = useReducer(editorReducer, INITIAL_EDITOR);
  const setEditor = useCallback(<K extends keyof EditorState>(key: K, value: SetStateAction<EditorState[K]>) => {
    dispatchEditor({
      type: 'update', update: (current) => ({
        ...current,
        [key]: typeof value === 'function' ? (value as (previous: EditorState[K]) => EditorState[K])(current[key]) : value,
      })
    });
  }, []);
  const { selected, detailState, creating, inspection, websiteDiscovery } = editor;
  const detailRequestRef = useRef(0);
  const detailControllerRef = useRef<AbortController | null>(null);
  const hydratedInspectionRef = useRef<string | null>(null);
  const hydratedWebsiteDiscoveryRef = useRef<string | null>(null);
  const websiteRecoveryRequestRef = useRef(0);
  const detailHeadingRef = useRef<HTMLHeadingElement>(null);
  const appListRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const detailTriggerRef = useRef<HTMLButtonElement | null>(null);
  const detailAppRef = useRef<CatalogApp | null>(null);

  useEffect(() => () => {
    detailControllerRef.current?.abort();
    detailRequestRef.current += 1;
    websiteRecoveryRequestRef.current += 1;
  }, []);

  const recoverInspection = useCallback(async (
    app: AppDetails,
    signal: AbortSignal,
  ): Promise<ManualInstallerInspection | null> => {
    if (!isUnresolved(app)) return null;
    try {
      return await fetchCurrentManualInstallerInspection(app.id, signal);
    } catch (requestError) {
      if (requestError instanceof ApiRequestError && requestError.status === 404) {
        return null;
      }
      throw requestError;
    }
  }, []);

  const openApp = useCallback(async (app: CatalogApp) => {
    const requestId = ++detailRequestRef.current;
    detailControllerRef.current?.abort();
    const controller = new AbortController();
    detailControllerRef.current = controller;
    websiteRecoveryRequestRef.current += 1;
    detailAppRef.current = app;
    dispatchEditor({ type: 'beginLoad' });
    setOperation('discoveringWebsite', false);
    setMessage(null);
    setError(null);
    hydratedInspectionRef.current = null;
    hydratedWebsiteDiscoveryRef.current = null;
    try {
      const details = await fetchAppDetails(app.id, controller.signal);
      const currentInspection = await recoverInspection(details, controller.signal);
      if (requestId !== detailRequestRef.current) return;
      dispatchEditor({ type: 'loaded', app: details, inspection: currentInspection });
      if (window.innerWidth < 981) {
        window.requestAnimationFrame(() => detailHeadingRef.current?.focus());
      }
    } catch (requestError) {
      if (controller.signal.aborted || requestId !== detailRequestRef.current) return;
      setEditor('selected', null);
      setEditor('detailState', 'error');
      setError(errorMessage(t, requestError, 'admin.apps.error.details'));
    }
  }, [recoverInspection, setEditor, setError, setMessage, setOperation, t]);

  useEffect(() => {
    if (
      apps.length === 0
      || selected
      || creating
      || detailState === 'loading'
      || window.innerWidth < 981
    ) {
      return;
    }
    void openApp(apps[0]);
  }, [apps, creating, detailState, openApp, selected]);

  const selectedListId = selected?.id ?? null;
  const provenance = useMemo(
    () => creating && websiteDiscovery?.status === 'ready'
      ? websiteDiscovery.suggestions
      : inspection?.status === 'ready'
        ? inspection.suggestions
        : null,
    [creating, inspection, websiteDiscovery],
  );
  const inspectionLocksOrdinaryWrite = Boolean(
    selected
    && isUnresolved(selected)
    && inspection
    && ['queued', 'running', 'ready'].includes(inspection.status),
  );
  const previewPending = inspection?.status === 'queued'
    || inspection?.status === 'running'
    || websiteDiscovery?.status === 'queued'
    || websiteDiscovery?.status === 'running';

  function startNewApp() {
    detailRequestRef.current += 1;
    websiteRecoveryRequestRef.current += 1;
    window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
    dispatchEditor({ type: 'create', websiteUrl: '' });
    setMessage(null);
    setError(null);
    setOperation('discoveringWebsite', false);
    hydratedWebsiteDiscoveryRef.current = null;
    window.requestAnimationFrame(() => detailHeadingRef.current?.focus());
  }

  function closeMobileDetail() {
    setEditor('detailOpen', false);
    window.requestAnimationFrame(() => {
      if (detailTriggerRef.current?.isConnected) {
        detailTriggerRef.current.focus();
      } else {
        searchInputRef.current?.focus();
      }
    });
  }

  function moveListSelection(
    event: KeyboardEvent<HTMLButtonElement>,
    currentIndex: number,
  ) {
    const nextIndex = event.key === 'ArrowDown'
      ? Math.min(apps.length - 1, currentIndex + 1)
      : event.key === 'ArrowUp'
        ? Math.max(0, currentIndex - 1)
        : event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? apps.length - 1
            : null;
    if (nextIndex === null) return;

    event.preventDefault();
    const rows = appListRef.current?.querySelectorAll<HTMLButtonElement>('.admin-app-row');
    detailTriggerRef.current = rows?.[nextIndex] ?? null;
    rows?.[nextIndex]?.focus();
    if (nextIndex !== currentIndex) void openApp(apps[nextIndex]);
  }

  return { ...editor, setEditor, dispatchEditor, detailRequestRef, hydratedInspectionRef, hydratedWebsiteDiscoveryRef, websiteRecoveryRequestRef, detailHeadingRef, appListRef, searchInputRef, detailTriggerRef, detailAppRef, openApp, startNewApp, closeMobileDetail, moveListSelection, selectedListId, provenance, inspectionLocksOrdinaryWrite, previewPending };
}
