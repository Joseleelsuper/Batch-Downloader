import {
  ArrowLeft,
  CheckCircle2,
  FileDown,
  Loader2,
  PackageCheck,
  Plus,
  Save,
  Search,
  Trash2,
  Wand2,
} from 'lucide-react';
import {
  FormEvent,
  KeyboardEvent,
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
} from 'react';
import {
  applyManualInstallerInspection,
  applyWebsiteAppDiscovery,
  createManualInstallerInspection,
  createWebsiteAppDiscovery,
  deleteAdminApp,
  deleteAllAdminApps,
  exportAdminAppsCsv,
  fetchAdminApps,
  fetchAbsenceVerificationSummary,
  fetchCurrentManualInstallerInspection,
  fetchManualInstallerInspection,
  fetchWebsiteAppDiscovery,
  generateAdminDescription,
  patchAdminApp,
} from '../../api/adminApps';
import { fetchAppDetails, fetchCatalogStats } from '../../api/catalogApps';
import { ApiRequestError } from '../../api/http';
import { createScraperRun } from '../../api/scraperAdmin';
import { AppStatusBadge } from '../../components/AppStatusBadge';
import { Pagination } from '../../components/Pagination';
import { usePollingTask } from '../../hooks/usePollingTask';
import { useTranslation } from '../../services/i18n';
import { useAdminAppsActivity } from './useAdminAppsActivity';
import type {
  AdminAppFilter,
  AppDetails,
  CatalogApp,
  CatalogStats,
  ManualInstallerInspection,
  InstallerAbsenceVerificationSummary,
  OperatingSystem,
  WebsiteAppDiscovery,
} from '../../types/catalog';
import {
  AdminAppIcon,
  AdminEditorIcon,
  clickableHttpUrl,
  DiscoveryStatus,
  editorPayload,
  EditorField,
  EditorTextarea,
  EMPTY_FORM,
  EMPTY_WEBSITE_INSTALLER_URLS,
  errorMessage,
  filterLabel,
  formFromApp,
  formFromSuggestions,
  InspectionFeedback,
  InspectionStatus,
  InstallerEvidence,
  isUnresolved,
  isUnresolvedFilter,
  manualInspectionInstallers,
  suggestionProvenance,
  validateHttpsUrl,
  validateManualUrls,
  validateOptionalWebsiteInstallerUrls,
  warningLabel,
  WebsiteDiscoveryFeedback,
  WebsiteInstallerEvidence,
  type EditorForm,
} from './AdminAppsSupport';

const PAGE_SIZE = 12;
const INSPECTION_POLL_MS = 1200;
const WEBSITE_DISCOVERY_STORAGE_KEY = 'batch-downloader.admin.website-discovery.v1';
type DetailState = 'empty' | 'loading' | 'ready' | 'error';
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

const FILTERS: AdminAppFilter[] = [
  'unresolved',
  'review',
  'missing',
  'available',
  'all',
];

export function AdminAppsPage() {
  const t = useTranslation();
  const [list, dispatchList] = useReducer(listReducer, INITIAL_LIST);
  const {
    queryInput,
    query,
    filter,
    page,
    pageSize,
    total,
    apps,
    stats,
    absenceSummary,
    state: listState,
    reloadToken,
  } = list;

  const [selected, setSelected] = useState<AppDetails | null>(null);
  const [detailState, setDetailState] = useState<DetailState>('empty');
  const [detailOpen, setDetailOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<EditorForm>(EMPTY_FORM);
  const [inspection, setInspection] = useState<ManualInstallerInspection | null>(null);
  const [websiteDiscovery, setWebsiteDiscovery] = useState<WebsiteAppDiscovery | null>(null);
  const [websiteUrl, setWebsiteUrl] = useState('');
  const [websiteInstallerUrls, setWebsiteInstallerUrls] = useState(
    EMPTY_WEBSITE_INSTALLER_URLS,
  );
  const [manualInstallerUrls, setManualInstallerUrls] = useState(
    EMPTY_WEBSITE_INSTALLER_URLS,
  );
  const [sourcePageUrl, setSourcePageUrl] = useState('');
  const [operatingSystem, setOperatingSystem] = useState<OperatingSystem | ''>('');

  const {
    message,
    error,
    saving,
    inspecting,
    discoveringWebsite,
    applying,
    generatingDescription,
    deletingSelected,
    exportingCsv,
    deletingAll,
    retryingSelected,
    setMessage,
    setError,
    setSaving,
    setInspecting,
    setDiscoveringWebsite,
    setApplying,
    setGeneratingDescription,
    setDeletingSelected,
    setExportingCsv,
    setDeletingAll,
    setRetryingSelected,
  } = useAdminAppsActivity();
  const [dangerConfirm, setDangerConfirm] = useState('');

  const listRequestRef = useRef(0);
  const detailRequestRef = useRef(0);
  const hydratedInspectionRef = useRef<string | null>(null);
  const hydratedWebsiteDiscoveryRef = useRef<string | null>(null);
  const websiteRecoveryRequestRef = useRef(0);
  const detailHeadingRef = useRef<HTMLHeadingElement>(null);
  const dangerDialogRef = useRef<HTMLDialogElement>(null);
  const appListRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const detailTriggerRef = useRef<HTMLButtonElement | null>(null);
  const detailAppRef = useRef<CatalogApp | null>(null);

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

  useEffect(() => {
    let recovery: { id?: string; officialUrl?: string } | null = null;
    try {
      const stored = window.sessionStorage.getItem(WEBSITE_DISCOVERY_STORAGE_KEY);
      recovery = stored
        ? JSON.parse(stored) as { id?: string; officialUrl?: string }
        : null;
    } catch {
      window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
    }
    if (!recovery?.id) return;

    const controller = new AbortController();
    const requestId = ++websiteRecoveryRequestRef.current;
    detailRequestRef.current += 1;
    setSelected(null);
    setCreating(true);
    setDetailOpen(true);
    setDetailState('ready');
    setForm(EMPTY_FORM);
    setInspection(null);
    setWebsiteDiscovery(null);
    setWebsiteUrl(recovery.officialUrl || '');
    setWebsiteInstallerUrls(EMPTY_WEBSITE_INSTALLER_URLS);
    setManualInstallerUrls(EMPTY_WEBSITE_INSTALLER_URLS);
    setSourcePageUrl('');
    setOperatingSystem('');
    setDiscoveringWebsite(true);
    hydratedWebsiteDiscoveryRef.current = null;

    void fetchWebsiteAppDiscovery(recovery.id, controller.signal)
      .then((recovered) => {
        if (
          controller.signal.aborted
          || requestId !== websiteRecoveryRequestRef.current
        ) {
          return;
        }
        if (['applied', 'expired'].includes(recovered.status)) {
          window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
          setCreating(false);
          setDetailOpen(false);
          setDetailState('empty');
          return;
        }
        setWebsiteDiscovery(recovered);
      })
      .catch(() => {
        if (
          controller.signal.aborted
          || requestId !== websiteRecoveryRequestRef.current
        ) {
          return;
        }
        window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
        setCreating(false);
        setDetailOpen(false);
        setDetailState('empty');
      })
      .finally(() => {
        if (requestId === websiteRecoveryRequestRef.current) {
          setDiscoveringWebsite(false);
        }
      });

    return () => controller.abort();
  }, [setDiscoveringWebsite]);

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
    const controller = new AbortController();
    detailAppRef.current = app;
    setCreating(false);
    setDetailOpen(true);
    setDetailState('loading');
    setMessage(null);
    setError(null);
    setInspection(null);
    setWebsiteDiscovery(null);
    setWebsiteUrl('');
    setWebsiteInstallerUrls(EMPTY_WEBSITE_INSTALLER_URLS);
    hydratedInspectionRef.current = null;
    hydratedWebsiteDiscoveryRef.current = null;
    setManualInstallerUrls(EMPTY_WEBSITE_INSTALLER_URLS);
    setSourcePageUrl('');
    setOperatingSystem('');
    try {
      const details = await fetchAppDetails(app.id, controller.signal);
      const currentInspection = await recoverInspection(details, controller.signal);
      if (requestId !== detailRequestRef.current) return;
      setSelected(details);
      setForm(formFromApp(details));
      setInspection(currentInspection);
      setDetailState('ready');
      if (window.innerWidth < 981) {
        window.requestAnimationFrame(() => detailHeadingRef.current?.focus());
      }
    } catch (requestError) {
      if (controller.signal.aborted || requestId !== detailRequestRef.current) return;
      setSelected(null);
      setDetailState('error');
      setError(errorMessage(t, requestError, 'admin.apps.error.details'));
    }
  }, [recoverInspection, setError, setMessage, t]);

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

  const inspectionPolling = Boolean(
    inspection
    && selected
    && ['queued', 'running'].includes(inspection.status),
  );
  usePollingTask({
    enabled: inspectionPolling,
    intervalMs: INSPECTION_POLL_MS,
    pollKey: selected && inspection ? `${selected.id}:${inspection.id}` : null,
    task: async (signal) => {
      if (!selected || !inspection) return false;
      const appId = selected.id;
      const next = await fetchManualInstallerInspection(appId, inspection.id, signal);
      if (signal.aborted || next.appId !== appId) return false;
      setInspection(next);
      setError(null);
      return next.status === 'queued' || next.status === 'running';
    },
    onError: (requestError) => {
      setError(errorMessage(t, requestError, 'admin.apps.error.inspectionProgress'));
    },
  });

  const websiteDiscoveryPolling = Boolean(
    creating
    && websiteDiscovery
    && ['queued', 'running'].includes(websiteDiscovery.status),
  );
  usePollingTask({
    enabled: websiteDiscoveryPolling,
    intervalMs: INSPECTION_POLL_MS,
    pollKey: websiteDiscovery?.id ?? null,
    task: async (signal) => {
      if (!websiteDiscovery) return false;
      const next = await fetchWebsiteAppDiscovery(websiteDiscovery.id, signal);
      if (signal.aborted) return false;
      setWebsiteDiscovery(next);
      setError(null);
      return next.status === 'queued' || next.status === 'running';
    },
    onError: (requestError) => {
      setError(errorMessage(t, requestError, 'admin.apps.website.error.progress'));
    },
  });

  useEffect(() => {
    if (
      inspection?.status !== 'ready'
      || !inspection.suggestions
      || hydratedInspectionRef.current === inspection.id
    ) {
      return;
    }
    hydratedInspectionRef.current = inspection.id;
    setForm((current) => formFromSuggestions(current, inspection.suggestions!));
    const readyInstallers = manualInspectionInstallers(inspection);
    const detectedOperatingSystem = readyInstallers.length === 1
      ? readyInstallers[0].operatingSystem
      : null;
    if (detectedOperatingSystem) {
      setOperatingSystem(detectedOperatingSystem);
    }
    setMessage(t('admin.apps.inspection.ready'));
  }, [inspection, setMessage, t]);

  useEffect(() => {
    if (
      websiteDiscovery?.status !== 'ready'
      || !websiteDiscovery.suggestions
      || hydratedWebsiteDiscoveryRef.current === websiteDiscovery.id
    ) {
      return;
    }
    hydratedWebsiteDiscoveryRef.current = websiteDiscovery.id;
    setForm((current) => formFromSuggestions(current, websiteDiscovery.suggestions!));
    setWebsiteUrl(
      websiteDiscovery.suggestions.officialUrl.value
      || websiteUrl,
    );
    setMessage(t('admin.apps.website.ready'));
  }, [setMessage, t, websiteDiscovery, websiteUrl]);

  const selectedListId = selected?.id ?? null;
  const unresolvedCount = (stats?.filters.review ?? 0) + (stats?.filters.missing ?? 0);
  const filterCounts: Record<AdminAppFilter, number> = {
    unresolved: unresolvedCount,
    all: stats?.filters.all ?? stats?.total ?? 0,
    available: stats?.filters.available ?? 0,
    review: stats?.filters.review ?? 0,
    missing: stats?.filters.missing ?? 0,
  };
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
    setSelected(null);
    setCreating(true);
    setDetailOpen(true);
    setDetailState('ready');
    setForm(EMPTY_FORM);
    setInspection(null);
    setWebsiteDiscovery(null);
    setWebsiteUrl('');
    setWebsiteInstallerUrls(EMPTY_WEBSITE_INSTALLER_URLS);
    setManualInstallerUrls(EMPTY_WEBSITE_INSTALLER_URLS);
    setSourcePageUrl('');
    setOperatingSystem('');
    setMessage(null);
    setError(null);
    setDiscoveringWebsite(false);
    hydratedWebsiteDiscoveryRef.current = null;
    window.requestAnimationFrame(() => detailHeadingRef.current?.focus());
  }

  function closeMobileDetail() {
    setDetailOpen(false);
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

  async function saveApp(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving || applying || inspectionLocksOrdinaryWrite) return;
    if (creating && websiteDiscovery?.status !== 'ready') {
      setError(t('admin.apps.website.validation.analyzeFirst'));
      return;
    }
    if (!form.name.trim()) {
      setError(t('admin.app.validation.nameRequired'));
      return;
    }
    setSaving(true);
    setMessage(null);
    setError(null);
    try {
      const payload = editorPayload(form);
      const websiteResult = creating && websiteDiscovery
        ? await applyWebsiteAppDiscovery(websiteDiscovery.id, {
            ...payload,
            officialUrl: form.officialUrl.trim(),
          })
        : null;
      const saved = selected
        ? await patchAdminApp(selected.id, payload)
        : websiteResult!.application;
      setSelected(saved);
      setCreating(false);
      setWebsiteDiscovery(null);
      window.sessionStorage.removeItem(WEBSITE_DISCOVERY_STORAGE_KEY);
      setForm(formFromApp(saved));
      const warningSummary = websiteResult?.warnings.map((warning) => warningLabel(t, warning)).join(' ');
      setMessage([
        t('admin.message.appSaved'),
        websiteResult
          ? t('admin.apps.website.createdInstallers', {
              count: websiteResult.installerCount,
            })
          : '',
        warningSummary,
      ].filter(Boolean).join(' '));
      dispatchList({ type: 'reload' });
    } catch (requestError) {
      setError(errorMessage(t, requestError, 'admin.message.saveAppError'));
    } finally {
      setSaving(false);
    }
  }

  async function startWebsiteDiscovery() {
    if (discoveringWebsite || saving) return;
    const validationError = validateHttpsUrl(
      t,
      websiteUrl,
      t('admin.apps.website.officialUrl'),
    ) || validateOptionalWebsiteInstallerUrls(t, websiteInstallerUrls);
    if (validationError) {
      setError(validationError);
      return;
    }
    const requestId = ++websiteRecoveryRequestRef.current;
    setDiscoveringWebsite(true);
    setMessage(null);
    setError(null);
    setWebsiteDiscovery(null);
    hydratedWebsiteDiscoveryRef.current = null;
    try {
      const discovery = await createWebsiteAppDiscovery({
        officialUrl: websiteUrl.trim(),
        installerUrls: {
          windows: websiteInstallerUrls.windows.trim() || null,
          macos: websiteInstallerUrls.macos.trim() || null,
          linux: websiteInstallerUrls.linux.trim() || null,
        },
      });
      if (requestId !== websiteRecoveryRequestRef.current) return;
      setWebsiteDiscovery(discovery);
      window.sessionStorage.setItem(
        WEBSITE_DISCOVERY_STORAGE_KEY,
        JSON.stringify({
          id: discovery.id,
          officialUrl: websiteUrl.trim(),
        }),
      );
      setMessage(t('admin.apps.website.queued'));
    } catch (requestError) {
      if (requestId !== websiteRecoveryRequestRef.current) return;
      setError(errorMessage(t, requestError, 'admin.apps.website.error.create'));
    } finally {
      if (requestId === websiteRecoveryRequestRef.current) {
        setDiscoveringWebsite(false);
      }
    }
  }

  function discoverWebsiteOnEnter(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    void startWebsiteDiscovery();
  }

  async function startInspection() {
    if (!selected || inspecting || applying) return;
    const validationError = validateManualUrls(
      t,
      manualInstallerUrls,
      sourcePageUrl,
    );
    if (validationError) {
      setError(validationError);
      return;
    }
    setInspecting(true);
    setMessage(null);
    setError(null);
    try {
      const createdInspection = await createManualInstallerInspection(selected.id, {
        installerUrls: {
          windows: manualInstallerUrls.windows.trim() || null,
          macos: manualInstallerUrls.macos.trim() || null,
          linux: manualInstallerUrls.linux.trim() || null,
        },
        sourcePageUrl: sourcePageUrl.trim(),
      });
      setInspection(createdInspection);
      hydratedInspectionRef.current = null;
      setMessage(t('admin.apps.inspection.queued'));
    } catch (requestError) {
      setError(errorMessage(t, requestError, 'admin.apps.error.inspectionCreate'));
    } finally {
      setInspecting(false);
    }
  }

  function inspectOnEnter(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    void startInspection();
  }

  async function publishInspection() {
    if (!selected || inspection?.status !== 'ready' || applying || saving) return;
    if (manualInspectionInstallers(inspection).some((installer) => installer.platformRequired)
      && !operatingSystem) {
      setError(t('admin.apps.validation.platformRequired'));
      return;
    }
    setApplying(true);
    setMessage(null);
    setError(null);
    try {
      const applied = await applyManualInstallerInspection(selected.id, inspection.id, {
        expectedAppVersion: inspection.expectedAppVersion,
        ...editorPayload(form),
        operatingSystem: operatingSystem || null,
      });
      const warningSummary = applied.warnings.map((warning) => warningLabel(t, warning)).join(' ');
      const successMessage = [
        t('admin.apps.publish.success', { name: selected.name }),
        warningSummary,
      ].filter(Boolean).join(' ');
      const selectedIndex = apps.findIndex((app) => app.id === selected.id);
      const remaining = apps.filter((app) => app.id !== selected.id);
      dispatchList({ type: 'removeUnresolved', remaining, filter });
      setInspection(null);
      setSelected(null);
      setDetailState('empty');
      setDetailOpen(false);
      dispatchList({ type: 'reload' });
      refreshStats();
      if (isUnresolvedFilter(filter) && remaining.length > 0) {
        const next = remaining[Math.min(Math.max(selectedIndex, 0), remaining.length - 1)];
        await openApp(next);
      } else {
        window.requestAnimationFrame(() => searchInputRef.current?.focus());
      }
      setMessage(successMessage);
    } catch (requestError) {
      setError(errorMessage(t, requestError, 'admin.apps.error.apply'));
    } finally {
      setApplying(false);
    }
  }

  async function queueDescription() {
    if (
      !selected
      || saving
      || applying
      || generatingDescription
      || inspectionLocksOrdinaryWrite
    ) return;
    setGeneratingDescription(true);
    setMessage(t('admin.message.descriptionGenerating'));
    setError(null);
    try {
      await generateAdminDescription(selected.id);
      setMessage(t('admin.message.descriptionQueued'));
    } catch (requestError) {
      setError(errorMessage(t, requestError, 'admin.message.generateDescriptionError'));
    } finally {
      setGeneratingDescription(false);
    }
  }

  async function removeSelectedApp() {
    if (!selected || saving || applying || deletingSelected || inspectionLocksOrdinaryWrite) return;
    if (!window.confirm(t('admin.app.confirm.deleteOne', { name: selected.name }))) return;
    setDeletingSelected(true);
    try {
      await deleteAdminApp(selected.id);
      setSelected(null);
      setDetailState('empty');
      setDetailOpen(false);
      setMessage(t('admin.message.appDeleted'));
      dispatchList({ type: 'reload' });
    } catch (requestError) {
      setError(errorMessage(t, requestError, 'admin.message.deleteAppError'));
    } finally {
      setDeletingSelected(false);
    }
  }

  async function retrySelectedApp() {
    if (!selected || retryingSelected) return;
    setRetryingSelected(true);
    setMessage(null);
    setError(null);
    try {
      const request = await createScraperRun('selected', [selected.id]);
      setMessage(t('admin.apps.selectedRunQueued', { requestId: request.requestId }));
    } catch (requestError) {
      setError(errorMessage(t, requestError, 'admin.message.sendCommandError'));
    } finally {
      setRetryingSelected(false);
    }
  }

  async function exportCsv() {
    if (exportingCsv) return;
    setExportingCsv(true);
    setError(null);
    try {
      await exportAdminAppsCsv();
    } catch (requestError) {
      setError(errorMessage(t, requestError, 'admin.message.exportCsvError'));
    } finally {
      setExportingCsv(false);
    }
  }

  function openDangerDialog() {
    setDangerConfirm('');
    dangerDialogRef.current?.showModal();
  }

  async function removeAllApps() {
    if (deletingAll || dangerConfirm !== 'DELETE_ALL') return;
    setDeletingAll(true);
    setError(null);
    try {
      const result = await deleteAllAdminApps();
      dangerDialogRef.current?.close();
      dispatchList({ type: 'patch', value: { apps: [] } });
      setSelected(null);
      setDetailState('empty');
      setDangerConfirm('');
      setMessage(t('admin.message.allAppsDeleted', { count: result.deleted }));
      dispatchList({ type: 'reload' });
    } catch (requestError) {
      setError(errorMessage(t, requestError, 'admin.message.deleteAllAppsError'));
    } finally {
      setDeletingAll(false);
    }
  }

  return (
    <section className={`admin-apps-page ${detailOpen ? 'admin-apps-detail-open' : ''}`}>
      <header className="admin-apps-toolbar">
        <div>
          <h2>{t('admin.app.title')}</h2>
          <p>{t('admin.apps.subtitle')}</p>
        </div>
        <div className="button-row">
          <button
            className="secondary-button compact-button"
            type="button"
            onClick={() => void exportCsv()}
            disabled={exportingCsv}
          >
            {exportingCsv ? <Loader2 className="spin" size={17} /> : <FileDown size={17} />}
            {exportingCsv ? t('admin.app.exportingCsv') : t('admin.app.exportCsv')}
          </button>
          <button
            className="secondary-button compact-button"
            type="button"
            onClick={(event) => {
              detailTriggerRef.current = event.currentTarget;
              startNewApp();
            }}
          >
            <Plus size={17} />
            {t('admin.app.new')}
          </button>
          <button className="danger-button compact-button" type="button" onClick={openDangerDialog}>
            <Trash2 size={17} />
            {t('admin.apps.danger.open')}
          </button>
        </div>
      </header>

      {message ? <div className="admin-apps-notice" role="status">{message}</div> : null}
      {error ? <div className="admin-apps-error" role="alert">{error}</div> : null}
      {absenceSummary ? (
        <div className="admin-apps-notice" role="status">
          {t('admin.apps.absenceSummary', {
            active: absenceSummary.active,
            missing: absenceSummary.missing,
            unjustified: absenceSummary.missingWithoutActiveEvidence,
          })}
        </div>
      ) : null}

      <div className="admin-apps-workbench">
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
                  setSelected(null);
                  setCreating(false);
                  setWebsiteDiscovery(null);
                  setDetailState('empty');
                  setDetailOpen(false);
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

        <section
          className="admin-apps-detail"
          aria-label={t('admin.apps.editor.label')}
          aria-busy={detailState === 'loading'}
        >
          {detailState === 'empty' ? (
            <div className="admin-app-detail-empty">
              <PackageCheck size={30} />
              <h3>{t('admin.apps.editor.emptyTitle')}</h3>
              <p>{t('admin.apps.editor.emptyDescription')}</p>
            </div>
          ) : null}
          {detailState === 'loading' ? (
            <div className="admin-app-detail-empty" role="status">
              <button
                className="admin-app-back"
                type="button"
                onClick={closeMobileDetail}
              >
                <ArrowLeft size={18} />
                {t('common.back')}
              </button>
              <Loader2 className="spin" size={28} />
              <p>{t('admin.apps.details.loading')}</p>
            </div>
          ) : null}
          {detailState === 'error' ? (
            <div className="admin-app-detail-empty" role="alert">
              <button
                className="admin-app-back"
                type="button"
                onClick={closeMobileDetail}
              >
                <ArrowLeft size={18} />
                {t('common.back')}
              </button>
              <h3>{t('admin.apps.details.errorTitle')}</h3>
              <p>{t('admin.apps.error.details')}</p>
              <button
                type="button"
                className="secondary-button"
                onClick={() => {
                  if (detailAppRef.current) void openApp(detailAppRef.current);
                }}
              >
                {t('common.retry')}
              </button>
            </div>
          ) : null}
          {detailState === 'ready' ? (
            <form
              className="admin-app-editor"
              aria-busy={saving || applying || generatingDescription || deletingSelected}
              onSubmit={saveApp}
            >
              <div className="admin-app-editor-header">
                <button
                  className="admin-app-back"
                  type="button"
                  onClick={closeMobileDetail}
                >
                  <ArrowLeft size={18} />
                  {t('common.back')}
                </button>
                <div className="admin-app-editor-title">
                  <AdminEditorIcon form={form} />
                  <div>
                    <span>{creating ? t('admin.app.newApp') : t('admin.app.editing')}</span>
                    <h3 ref={detailHeadingRef} tabIndex={-1}>
                      {creating ? t('admin.app.titleCreate') : selected?.name}
                    </h3>
                  </div>
                </div>
                {selected ? <small title={selected.id}>{selected.id}</small> : null}
                {selected && isUnresolved(selected) ? (
                  <button
                    type="button"
                    className="secondary-button compact-button"
                    disabled={retryingSelected}
                    onClick={() => void retrySelectedApp()}
                  >
                    {retryingSelected
                      ? <Loader2 className="spin" size={16} />
                      : <Search size={16} />}
                    {t('admin.apps.retrySelected')}
                  </button>
                ) : null}
              </div>

              {creating ? (
                <section
                  className="manual-installer-panel website-discovery-panel"
                  aria-labelledby="website-discovery-title"
                  aria-busy={
                    discoveringWebsite
                    || websiteDiscovery?.status === 'queued'
                    || websiteDiscovery?.status === 'running'
                  }
                >
                  <div className="manual-installer-heading">
                    <div>
                      <span>{t('admin.apps.website.kicker')}</span>
                      <h4 id="website-discovery-title">{t('admin.apps.website.title')}</h4>
                    </div>
                    {websiteDiscovery
                      ? <DiscoveryStatus discovery={websiteDiscovery} />
                      : null}
                  </div>
                  <p>{t('admin.apps.website.description')}</p>
                  {websiteDiscovery?.status !== 'ready' ? (
                    <div className="website-discovery-form">
                      <label htmlFor="website-official-url">
                        {t('admin.apps.website.officialUrl')}
                      </label>
                      <div className="website-official-row">
                        <input
                          id="website-official-url"
                          type="url"
                          inputMode="url"
                          value={websiteUrl}
                          onChange={(event) => setWebsiteUrl(event.target.value)}
                          onKeyDown={discoverWebsiteOnEnter}
                          placeholder="https://example.com"
                          autoComplete="url"
                          maxLength={2048}
                          aria-describedby="website-official-url-help"
                          disabled={
                            discoveringWebsite
                            || websiteDiscovery?.status === 'queued'
                            || websiteDiscovery?.status === 'running'
                          }
                        />
                        <button
                          type="button"
                          className="primary-button"
                          onClick={() => void startWebsiteDiscovery()}
                          disabled={
                            discoveringWebsite
                            || websiteDiscovery?.status === 'queued'
                            || websiteDiscovery?.status === 'running'
                          }
                        >
                          {discoveringWebsite
                            || websiteDiscovery?.status === 'queued'
                            || websiteDiscovery?.status === 'running'
                            ? <Loader2 className="spin" size={17} />
                            : <Wand2 size={17} />}
                          {websiteDiscovery?.status === 'failed'
                            || websiteDiscovery?.status === 'expired'
                            ? t('admin.apps.website.analyzeAgain')
                            : t('admin.apps.website.analyze')}
                        </button>
                      </div>
                      <small id="website-official-url-help">
                        {t('admin.apps.website.officialUrlHelp')}
                      </small>
                      <fieldset className="website-platform-urls">
                        <legend>{t('admin.apps.website.optionalInstallers')}</legend>
                        <p>{t('admin.apps.website.optionalInstallersHelp')}</p>
                        <div>
                          {(['windows', 'macos', 'linux'] as OperatingSystem[]).map(
                            (platform) => (
                              <label key={platform} htmlFor={`website-${platform}-url`}>
                                <span>
                                  {t(`admin.apps.website.${platform}InstallerUrl` as const)}
                                </span>
                                <input
                                  id={`website-${platform}-url`}
                                  type="url"
                                  inputMode="url"
                                  maxLength={2048}
                                  value={websiteInstallerUrls[platform]}
                                  onChange={(event) => setWebsiteInstallerUrls((current) => ({
                                    ...current,
                                    [platform]: event.target.value,
                                  }))}
                                  placeholder="https://downloads.example.com/installer"
                                  autoComplete="url"
                                  aria-describedby="website-installer-urls-help"
                                  disabled={
                                    discoveringWebsite
                                    || websiteDiscovery?.status === 'queued'
                                    || websiteDiscovery?.status === 'running'
                                  }
                                />
                              </label>
                            ),
                          )}
                        </div>
                        <small id="website-installer-urls-help">
                          {t('admin.apps.website.installerUrlHelp')}
                        </small>
                      </fieldset>
                    </div>
                  ) : null}
                  {websiteDiscovery
                    ? <WebsiteDiscoveryFeedback discovery={websiteDiscovery} />
                    : (
                      <div className="website-discovery-expectation">
                        <CheckCircle2 size={17} aria-hidden="true" />
                        <span>{t('admin.apps.website.expectation')}</span>
                      </div>
                    )}
                  {websiteDiscovery?.status === 'ready' ? (
                    <WebsiteInstallerEvidence discovery={websiteDiscovery} />
                  ) : null}
                </section>
              ) : null}

              {selected && isUnresolved(selected) ? (
                <section className="manual-installer-panel" aria-labelledby="manual-installer-title">
                  <div className="manual-installer-heading">
                    <div>
                      <span>{t('admin.apps.manual.kicker')}</span>
                      <h4 id="manual-installer-title">{t('admin.apps.manual.title')}</h4>
                    </div>
                    {inspection ? <InspectionStatus inspection={inspection} /> : null}
                  </div>
                  <p>{t('admin.apps.manual.description')}</p>
                  <div className="manual-installer-steps" aria-label={t('admin.apps.manual.stepsLabel')}>
                    <span className={inspection ? 'is-complete' : 'is-current'}>
                      <strong>1</strong>{t('admin.apps.manual.step.urls')}
                    </span>
                    <span className={inspection?.status === 'ready' ? 'is-complete' : inspection ? 'is-current' : ''}>
                      <strong>2</strong>{t('admin.apps.manual.step.preview')}
                    </span>
                    <span className={inspection?.status === 'ready' ? 'is-current' : ''}>
                      <strong>3</strong>{t('admin.apps.manual.step.publish')}
                    </span>
                  </div>
                  <div
                    className="manual-installer-form"
                    aria-busy={inspecting || inspection?.status === 'queued' || inspection?.status === 'running'}
                  >
                    <label className="manual-source-page-field" htmlFor="source-page-url">
                      <span>{t('admin.apps.manual.sourcePageUrl')}</span>
                      <input
                        id="source-page-url"
                        type="url"
                        inputMode="url"
                        maxLength={2048}
                        value={sourcePageUrl}
                        onChange={(event) => setSourcePageUrl(event.target.value)}
                        onKeyDown={inspectOnEnter}
                        placeholder="https://example.com/download"
                        aria-describedby="manual-source-page-help"
                        disabled={inspecting || applying}
                      />
                      <small id="manual-source-page-help">
                        {t('admin.apps.manual.sourcePageHelp')}
                      </small>
                    </label>
                    <fieldset className="platform-installer-urls">
                      <legend>{t('admin.apps.manual.installerUrls')}</legend>
                      <p>{t('admin.apps.manual.installerUrlsHelp')}</p>
                      <div>
                        {(['windows', 'macos', 'linux'] as OperatingSystem[]).map(
                          (platform) => (
                            <label key={platform} htmlFor={`manual-${platform}-url`}>
                              <span>
                                {t(`admin.apps.manual.${platform}InstallerUrl` as const)}
                              </span>
                              <input
                                id={`manual-${platform}-url`}
                                type="url"
                                inputMode="url"
                                maxLength={2048}
                                value={manualInstallerUrls[platform]}
                                onChange={(event) => setManualInstallerUrls((current) => ({
                                  ...current,
                                  [platform]: event.target.value,
                                }))}
                                onKeyDown={inspectOnEnter}
                                placeholder="https://downloads.example.com/installer"
                                autoComplete="url"
                                aria-describedby="manual-installer-urls-help"
                                disabled={inspecting || applying}
                              />
                            </label>
                          ),
                        )}
                      </div>
                      <small id="manual-installer-urls-help">
                        {t('admin.apps.manual.installerHelp')}
                      </small>
                    </fieldset>
                    <button
                      type="button"
                      className="secondary-button"
                      onClick={() => void startInspection()}
                      disabled={inspecting || applying || inspection?.status === 'queued' || inspection?.status === 'running'}
                    >
                      {inspecting || inspection?.status === 'queued' || inspection?.status === 'running'
                        ? <Loader2 className="spin" size={17} />
                        : <Wand2 size={17} />}
                      {inspection?.status === 'ready'
                        ? t('admin.apps.manual.inspectAgain')
                        : t('admin.apps.manual.inspect')}
                    </button>
                  </div>
                  {inspection ? <InspectionFeedback inspection={inspection} /> : null}
                  {inspection?.status === 'ready' && manualInspectionInstallers(inspection).length > 0 ? (
                    <>
                      <InstallerEvidence inspection={inspection} />
                      {manualInspectionInstallers(inspection).some((installer) => installer.platformRequired) ? (
                        <label className="manual-platform-field" htmlFor="manual-platform">
                          <span>{t('admin.apps.manual.platform')}</span>
                          <select
                            id="manual-platform"
                            value={operatingSystem}
                            onChange={(event) => setOperatingSystem(event.target.value as OperatingSystem)}
                          >
                            <option value="">{t('admin.apps.manual.platformPlaceholder')}</option>
                            <option value="windows">Windows</option>
                            <option value="macos">macOS</option>
                            <option value="linux">Linux</option>
                          </select>
                          <small>{t('admin.apps.manual.platformHelp')}</small>
                        </label>
                      ) : null}
                    </>
                  ) : null}
                </section>
              ) : null}

              {!creating || websiteDiscovery?.status === 'ready' ? (
                <>
              <fieldset className="admin-app-editor-section">
                <legend>{t('admin.app.primaryData')}</legend>
                <div className="admin-app-form-grid">
                  <EditorField
                    id="admin-app-name"
                    label={t('admin.field.name')}
                    value={form.name}
                    required
                    disabled={previewPending}
                    provenance={suggestionProvenance(provenance?.name, form.name)}
                    onChange={(value) => setForm((current) => ({ ...current, name: value }))}
                  />
                  <EditorField
                    id="admin-app-publisher"
                    label={t('admin.field.publisher')}
                    value={form.publisher}
                    disabled={previewPending}
                    provenance={suggestionProvenance(provenance?.publisher, form.publisher)}
                    onChange={(value) => setForm((current) => ({ ...current, publisher: value }))}
                  />
                  <EditorField
                    id="admin-app-official-url"
                    label={t('admin.field.officialUrl')}
                    value={form.officialUrl}
                    type="url"
                    externalHref={clickableHttpUrl(form.officialUrl)}
                    externalLabel={t('admin.apps.openOfficialUrl')}
                    disabled={previewPending}
                    provenance={suggestionProvenance(provenance?.officialUrl, form.officialUrl)}
                    onChange={(value) => setForm((current) => ({ ...current, officialUrl: value }))}
                  />
                  <EditorField
                    id="admin-app-version"
                    label={t('admin.field.latestVersion')}
                    value={form.latestVersion}
                    disabled={previewPending}
                    provenance={suggestionProvenance(provenance?.latestVersion, form.latestVersion)}
                    onChange={(value) => setForm((current) => ({ ...current, latestVersion: value }))}
                  />
                  <EditorField
                    id="admin-app-icon"
                    label={t('admin.apps.field.iconUrl')}
                    value={form.iconUrl}
                    type="url"
                    disabled={previewPending}
                    provenance={suggestionProvenance(provenance?.iconUrl, form.iconUrl)}
                    onChange={(value) => setForm((current) => ({ ...current, iconUrl: value }))}
                  />
                </div>
              </fieldset>

              <fieldset className="admin-app-editor-section">
                <legend>{t('admin.app.form.description')}</legend>
                <EditorTextarea
                  id="admin-app-description"
                  label={t('admin.app.shortDescription')}
                  value={form.description}
                  disabled={previewPending}
                  provenance={suggestionProvenance(provenance?.description, form.description)}
                  onChange={(value) => setForm((current) => ({ ...current, description: value }))}
                />
                <EditorTextarea
                  id="admin-app-long-description"
                  label={t('admin.app.longDescription')}
                  value={form.longDescription}
                  rows={7}
                  disabled={previewPending}
                  provenance={suggestionProvenance(
                    provenance?.longDescription,
                    form.longDescription,
                  )}
                  onChange={(value) => setForm((current) => ({ ...current, longDescription: value }))}
                />
              </fieldset>

              <div className="admin-app-editor-actions">
                {inspectionLocksOrdinaryWrite ? (
                  <p className="admin-app-write-lock" role="status">
                    {t('admin.apps.preview.writeLock')}
                  </p>
                ) : null}
                {inspection?.status === 'ready' && selected && isUnresolved(selected) ? (
                  <button
                    type="button"
                    className="primary-button manual-publish-button"
                    onClick={() => void publishInspection()}
                    disabled={applying || saving}
                  >
                    {applying ? <Loader2 className="spin" size={17} /> : <PackageCheck size={17} />}
                    {applying ? t('admin.apps.publish.applying') : t('admin.apps.publish.action')}
                  </button>
                ) : null}
                <button
                  className="primary-button"
                  type="submit"
                  disabled={saving || applying || inspectionLocksOrdinaryWrite}
                >
                  {saving ? <Loader2 className="spin" size={17} /> : <Save size={17} />}
                  {saving
                    ? t('common.saving')
                    : creating
                      ? t('admin.app.titleCreate')
                      : t('common.saveChanges')}
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  onClick={() => void queueDescription()}
                  disabled={
                    !selected
                    || saving
                    || applying
                    || generatingDescription
                    || inspectionLocksOrdinaryWrite
                  }
                >
                  {generatingDescription
                    ? <Loader2 className="spin" size={17} />
                    : <Wand2 size={17} />}
                  {generatingDescription
                    ? t('admin.message.descriptionGenerating')
                    : t('admin.app.generateDescription')}
                </button>
                <button
                  type="button"
                  className="danger-button"
                  onClick={() => void removeSelectedApp()}
                  disabled={
                    !selected
                    || saving
                    || applying
                    || deletingSelected
                    || inspectionLocksOrdinaryWrite
                  }
                >
                  {deletingSelected
                    ? <Loader2 className="spin" size={17} />
                    : <Trash2 size={17} />}
                  {t('admin.app.deleteOne')}
                </button>
              </div>
                </>
              ) : null}
            </form>
          ) : null}
        </section>
      </div>

      <dialog
        ref={dangerDialogRef}
        className="admin-danger-dialog"
        aria-labelledby="admin-danger-title"
        onClose={() => setDangerConfirm('')}
      >
        <form method="dialog" onSubmit={(event) => event.preventDefault()}>
          <div>
            <h3 id="admin-danger-title">{t('admin.app.danger.title')}</h3>
            <p>{t('admin.app.danger.description')}</p>
          </div>
          <label htmlFor="delete-all-confirmation">
            {t('admin.apps.danger.confirmLabel')}
          </label>
          <input
            id="delete-all-confirmation"
            value={dangerConfirm}
            onChange={(event) => setDangerConfirm(event.target.value)}
            placeholder="DELETE_ALL"
            autoComplete="off"
            disabled={deletingAll}
          />
          <div className="button-row">
            <button
              type="button"
              className="secondary-button"
              onClick={() => dangerDialogRef.current?.close()}
              disabled={deletingAll}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="danger-button"
              onClick={() => void removeAllApps()}
              disabled={deletingAll || dangerConfirm !== 'DELETE_ALL'}
            >
              {deletingAll ? <Loader2 className="spin" size={17} /> : <Trash2 size={17} />}
              {t('admin.app.deleteAll')}
            </button>
          </div>
        </form>
      </dialog>
    </section>
  );
}
