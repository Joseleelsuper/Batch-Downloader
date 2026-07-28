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
  useRef,
  useState,
} from 'react';
import {
  ApiRequestError,
  applyManualInstallerInspection,
  applyWebsiteAppDiscovery,
  createManualInstallerInspection,
  createWebsiteAppDiscovery,
  deleteAdminApp,
  deleteAllAdminApps,
  exportAdminAppsCsv,
  fetchAdminApps,
  fetchAppDetails,
  fetchCatalogStats,
  fetchCurrentManualInstallerInspection,
  fetchManualInstallerInspection,
  fetchWebsiteAppDiscovery,
  generateAdminDescription,
  patchAdminApp,
} from '../../api/catalog';
import { AppStatusBadge } from '../../components/AppStatusBadge';
import { Pagination } from '../../components/Pagination';
import { t } from '../../services/i18n';
import type {
  AdminAppFilter,
  AppDetails,
  CatalogApp,
  CatalogStats,
  ManualFieldSuggestion,
  ManualInstallerInspection,
  ManualInstallerSuggestions,
  ManualSuggestionSource,
  OperatingSystem,
  WebsiteAppDiscovery,
} from '../../types/catalog';

const PAGE_SIZE = 12;
const INSPECTION_POLL_MS = 1200;
const WEBSITE_DISCOVERY_STORAGE_KEY = 'batch-downloader.admin.website-discovery.v1';
const EMPTY_FORM = {
  name: '',
  publisher: '',
  officialUrl: '',
  latestVersion: '',
  description: '',
  longDescription: '',
  iconUrl: '',
};
const EMPTY_WEBSITE_INSTALLER_URLS: Record<OperatingSystem, string> = {
  windows: '',
  macos: '',
  linux: '',
};

type EditorForm = typeof EMPTY_FORM;
type DetailState = 'empty' | 'loading' | 'ready' | 'error';
type ListState = 'loading' | 'ready' | 'error';

const FILTERS: AdminAppFilter[] = [
  'unresolved',
  'review',
  'missing',
  'available',
  'all',
];

export function AdminAppsPage() {
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<AdminAppFilter>('unresolved');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZE);
  const [total, setTotal] = useState(0);
  const [apps, setApps] = useState<CatalogApp[]>([]);
  const [stats, setStats] = useState<CatalogStats | null>(null);
  const [listState, setListState] = useState<ListState>('loading');
  const [reloadToken, setReloadToken] = useState(0);

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

  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [inspecting, setInspecting] = useState(false);
  const [discoveringWebsite, setDiscoveringWebsite] = useState(false);
  const [applying, setApplying] = useState(false);
  const [generatingDescription, setGeneratingDescription] = useState(false);
  const [deletingSelected, setDeletingSelected] = useState(false);
  const [exportingCsv, setExportingCsv] = useState(false);
  const [deletingAll, setDeletingAll] = useState(false);
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
      setQuery(queryInput.trim());
      setPage(1);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [queryInput]);

  const refreshStats = useCallback(() => {
    fetchCatalogStats()
      .then(setStats)
      .catch(() => setStats(null));
  }, []);

  useEffect(() => {
    refreshStats();
  }, [refreshStats, reloadToken]);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++listRequestRef.current;
    setListState('loading');
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
        setApps(response.data);
        setTotal(response.total);
        setListState('ready');
        const pages = Math.max(1, Math.ceil(response.total / pageSize));
        if (page > pages) setPage(pages);
      })
      .catch((requestError) => {
        if (controller.signal.aborted || requestId !== listRequestRef.current) return;
        setApps([]);
        setTotal(0);
        setListState('error');
        setError(errorMessage(requestError, 'admin.apps.error.load'));
      });
    return () => controller.abort();
  }, [filter, page, pageSize, query, reloadToken]);

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
      setError(errorMessage(requestError, 'admin.apps.error.details'));
    }
  }, [recoverInspection]);

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

  useEffect(() => {
    if (!inspection || !selected || !['queued', 'running'].includes(inspection.status)) {
      return;
    }
    const controller = new AbortController();
    let timer: number | undefined;
    let cancelled = false;
    const poll = async () => {
      try {
        const next = await fetchManualInstallerInspection(
          selected.id,
          inspection.id,
          controller.signal,
        );
        if (cancelled || next.appId !== selected.id) return;
        setInspection(next);
        setError(null);
        if (next.status === 'queued' || next.status === 'running') {
          timer = window.setTimeout(poll, INSPECTION_POLL_MS);
        }
      } catch (requestError) {
        if (controller.signal.aborted || cancelled) return;
        setError(errorMessage(requestError, 'admin.apps.error.inspectionProgress'));
        timer = window.setTimeout(poll, INSPECTION_POLL_MS);
      }
    };
    timer = window.setTimeout(poll, INSPECTION_POLL_MS);
    return () => {
      cancelled = true;
      controller.abort();
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [inspection?.id, inspection?.status, selected?.id]);

  useEffect(() => {
    if (
      !creating
      || !websiteDiscovery
      || !['queued', 'running'].includes(websiteDiscovery.status)
    ) {
      return;
    }
    const controller = new AbortController();
    let timer: number | undefined;
    let cancelled = false;
    const poll = async () => {
      try {
        const next = await fetchWebsiteAppDiscovery(
          websiteDiscovery.id,
          controller.signal,
        );
        if (cancelled) return;
        setWebsiteDiscovery(next);
        setError(null);
        if (next.status === 'queued' || next.status === 'running') {
          timer = window.setTimeout(poll, INSPECTION_POLL_MS);
        }
      } catch (requestError) {
        if (controller.signal.aborted || cancelled) return;
        setError(errorMessage(requestError, 'admin.apps.website.error.progress'));
        timer = window.setTimeout(poll, INSPECTION_POLL_MS);
      }
    };
    timer = window.setTimeout(poll, INSPECTION_POLL_MS);
    return () => {
      cancelled = true;
      controller.abort();
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [creating, websiteDiscovery?.id, websiteDiscovery?.status]);

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
  }, [inspection]);

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
  }, [websiteDiscovery, websiteUrl]);

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
    let nextIndex = currentIndex;
    if (event.key === 'ArrowDown') nextIndex = Math.min(apps.length - 1, currentIndex + 1);
    else if (event.key === 'ArrowUp') nextIndex = Math.max(0, currentIndex - 1);
    else if (event.key === 'Home') nextIndex = 0;
    else if (event.key === 'End') nextIndex = apps.length - 1;
    else return;

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
      const warningSummary = websiteResult?.warnings.map(warningLabel).join(' ');
      setMessage([
        t('admin.message.appSaved'),
        websiteResult
          ? t('admin.apps.website.createdInstallers', {
              count: websiteResult.installerCount,
            })
          : '',
        warningSummary,
      ].filter(Boolean).join(' '));
      setReloadToken((value) => value + 1);
    } catch (requestError) {
      setError(errorMessage(requestError, 'admin.message.saveAppError'));
    } finally {
      setSaving(false);
    }
  }

  async function startWebsiteDiscovery() {
    if (discoveringWebsite || saving) return;
    const validationError = validateHttpsUrl(
      websiteUrl,
      t('admin.apps.website.officialUrl'),
    ) || validateOptionalWebsiteInstallerUrls(websiteInstallerUrls);
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
      setError(errorMessage(requestError, 'admin.apps.website.error.create'));
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
      setError(errorMessage(requestError, 'admin.apps.error.inspectionCreate'));
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
      const warningSummary = applied.warnings.map(warningLabel).join(' ');
      const successMessage = [
        t('admin.apps.publish.success', { name: selected.name }),
        warningSummary,
      ].filter(Boolean).join(' ');
      const selectedIndex = apps.findIndex((app) => app.id === selected.id);
      const remaining = apps.filter((app) => app.id !== selected.id);
      setApps(remaining);
      setTotal((value) => Math.max(0, value - (isUnresolvedFilter(filter) ? 1 : 0)));
      setInspection(null);
      setSelected(null);
      setDetailState('empty');
      setDetailOpen(false);
      setReloadToken((value) => value + 1);
      refreshStats();
      if (isUnresolvedFilter(filter) && remaining.length > 0) {
        const next = remaining[Math.min(Math.max(selectedIndex, 0), remaining.length - 1)];
        await openApp(next);
      } else {
        window.requestAnimationFrame(() => searchInputRef.current?.focus());
      }
      setMessage(successMessage);
    } catch (requestError) {
      setError(errorMessage(requestError, 'admin.apps.error.apply'));
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
      setError(errorMessage(requestError, 'admin.message.generateDescriptionError'));
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
      setReloadToken((value) => value + 1);
    } catch (requestError) {
      setError(errorMessage(requestError, 'admin.message.deleteAppError'));
    } finally {
      setDeletingSelected(false);
    }
  }

  async function exportCsv() {
    if (exportingCsv) return;
    setExportingCsv(true);
    setError(null);
    try {
      await exportAdminAppsCsv();
    } catch (requestError) {
      setError(errorMessage(requestError, 'admin.message.exportCsvError'));
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
      setApps([]);
      setSelected(null);
      setDetailState('empty');
      setDangerConfirm('');
      setMessage(t('admin.message.allAppsDeleted', { count: result.deleted }));
      setReloadToken((value) => value + 1);
    } catch (requestError) {
      setError(errorMessage(requestError, 'admin.message.deleteAllAppsError'));
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
                  setFilter(value);
                  setPage(1);
                  setApps([]);
                  setTotal(0);
                  setListState('loading');
                  setSelected(null);
                  setCreating(false);
                  setWebsiteDiscovery(null);
                  setDetailState('empty');
                  setDetailOpen(false);
                }}
              >
                <span>{filterLabel(value)}</span>
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
              onChange={(event) => setQueryInput(event.target.value)}
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
                <button type="button" className="secondary-button" onClick={() => setReloadToken((value) => value + 1)}>
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
            onPageChange={setPage}
            onPageSizeChange={(size) => {
              setPageSize(size);
              setPage(1);
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

function EditorField({
  id,
  label,
  value,
  onChange,
  provenance,
  type = 'text',
  required = false,
  disabled = false,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  provenance?: ManualSuggestionSource;
  type?: 'text' | 'url';
  required?: boolean;
  disabled?: boolean;
}) {
  return (
    <label className="admin-app-field" htmlFor={id}>
      <span>
        {label}
        {provenance ? <ProvenanceBadge source={provenance} /> : null}
      </span>
      <input
        id={id}
        type={type}
        required={required}
        disabled={disabled}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function EditorTextarea({
  id,
  label,
  value,
  onChange,
  provenance,
  rows = 4,
  disabled = false,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  provenance?: ManualSuggestionSource;
  rows?: number;
  disabled?: boolean;
}) {
  return (
    <label className="admin-app-field" htmlFor={id}>
      <span>
        {label}
        {provenance ? <ProvenanceBadge source={provenance} /> : null}
      </span>
      <textarea
        id={id}
        rows={rows}
        disabled={disabled}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function ProvenanceBadge({ source }: { source: ManualSuggestionSource }) {
  return (
    <small className={`provenance-badge provenance-${source}`}>
      {t(`admin.apps.provenance.${source}` as const)}
    </small>
  );
}

function InspectionStatus({ inspection }: { inspection: ManualInstallerInspection }) {
  const busy = inspection.status === 'queued' || inspection.status === 'running';
  return (
    <span className={`inspection-status inspection-status-${inspection.status}`} role="status">
      {busy ? <Loader2 className="spin" size={14} /> : null}
      {inspection.status === 'ready' ? <CheckCircle2 size={14} /> : null}
      {t(`admin.apps.inspection.status.${inspection.status}` as const)}
    </span>
  );
}

function DiscoveryStatus({ discovery }: { discovery: WebsiteAppDiscovery }) {
  const busy = discovery.status === 'queued' || discovery.status === 'running';
  return (
    <span className={`inspection-status inspection-status-${discovery.status}`} role="status">
      {busy ? <Loader2 className="spin" size={14} /> : null}
      {discovery.status === 'ready' ? <CheckCircle2 size={14} /> : null}
      {t(`admin.apps.website.status.${discovery.status}` as const)}
    </span>
  );
}

function WebsiteDiscoveryFeedback({
  discovery,
}: {
  discovery: WebsiteAppDiscovery;
}) {
  if (discovery.status === 'queued' || discovery.status === 'running') {
    return (
      <div className="inspection-progress" role="status" aria-live="polite">
        <Loader2 className="spin" size={18} />
        <div>
          <strong>{t('admin.apps.website.processing')}</strong>
          <span>{phaseLabel(discovery.phase)}</span>
        </div>
      </div>
    );
  }
  if (discovery.status === 'failed' || discovery.status === 'expired') {
    return (
      <div className="inspection-failure" role="alert">
        <strong>{t('admin.apps.website.failedTitle')}</strong>
        <span>{inspectionErrorLabel(discovery.errorCode)}</span>
      </div>
    );
  }
  if (discovery.warnings.length > 0) {
    return (
      <div className="inspection-warnings" role="status">
        <strong>{t('admin.apps.inspection.warnings')}</strong>
        <ul>
          {discovery.warnings.map((warning) => (
            <li key={warning}>{warningLabel(warning)}</li>
          ))}
        </ul>
      </div>
    );
  }
  return null;
}

function WebsiteInstallerEvidence({
  discovery,
}: {
  discovery: WebsiteAppDiscovery;
}) {
  return (
    <section
      className="website-installer-evidence"
      aria-labelledby="website-installers-title"
    >
      <div>
        <h5 id="website-installers-title">
          {t('admin.apps.website.installersTitle')}
        </h5>
        <span>
          {t('admin.apps.website.installersCount', {
            count: discovery.installers.length,
          })}
        </span>
      </div>
      {discovery.installers.length > 0 ? (
        <ul>
          {discovery.installers.map((installer) => (
            <li key={installer.id}>
              <strong>
                {installer.filename || t('admin.apps.website.installerFallback')}
              </strong>
              <span>
                {[
                  installer.operatingSystem,
                  installer.architecture,
                  installer.version,
                  formatBytes(installer.sizeBytes),
                  installer.finalDomain,
                ].filter(Boolean).join(' · ')}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p>{t('admin.apps.website.noInstallers')}</p>
      )}
      {discovery.ai?.status === 'ready' ? (
        <p className="ai-provenance">
          <Wand2 size={15} />
          {t('admin.apps.evidence.ai', {
            provider: discovery.ai.provider || t('common.notAvailable'),
            model: discovery.ai.model || t('common.notAvailable'),
          })}
        </p>
      ) : null}
    </section>
  );
}

function InspectionFeedback({ inspection }: { inspection: ManualInstallerInspection }) {
  if (inspection.status === 'queued' || inspection.status === 'running') {
    return (
      <div className="inspection-progress" role="status" aria-live="polite">
        <Loader2 className="spin" size={18} />
        <div>
          <strong>{t('admin.apps.inspection.processing')}</strong>
          <span>{phaseLabel(inspection.phase)}</span>
        </div>
      </div>
    );
  }
  if (inspection.status === 'failed' || inspection.status === 'expired') {
    return (
      <div className="inspection-failure" role="alert">
        <strong>{t('admin.apps.inspection.failedTitle')}</strong>
        <span>{inspectionErrorLabel(inspection.errorCode)}</span>
      </div>
    );
  }
  if (inspection.warnings.length > 0) {
    return (
      <div className="inspection-warnings" role="status">
        <strong>{t('admin.apps.inspection.warnings')}</strong>
        <ul>
          {inspection.warnings.map((warning) => <li key={warning}>{warningLabel(warning)}</li>)}
        </ul>
      </div>
    );
  }
  return null;
}

function InstallerEvidence({ inspection }: { inspection: ManualInstallerInspection }) {
  const installers = manualInspectionInstallers(inspection);
  return (
    <section className="installer-evidence" aria-labelledby="installer-evidence-title">
      <div>
        <h5 id="installer-evidence-title">{t('admin.apps.evidence.title')}</h5>
        <span>{t('admin.apps.evidence.validatedCount', { count: installers.length })}</span>
      </div>
      <div className="manual-installer-evidence-list">
        {installers.map((installer, index) => {
          const facts = [
            [t('admin.apps.evidence.file'), installer.filename || t('common.notAvailable')],
            [t('admin.apps.evidence.type'), installer.extension?.toUpperCase() || t('common.notAvailable')],
            [t('admin.apps.evidence.mime'), installer.contentType || t('common.notAvailable')],
            [t('admin.apps.evidence.size'), formatBytes(installer.sizeBytes)],
            [t('admin.apps.evidence.domain'), installer.finalDomain || t('common.notAvailable')],
            [t('admin.apps.evidence.version'), installer.version || t('common.notAvailable')],
            [t('admin.apps.evidence.architecture'), installer.architecture],
            [
              t('admin.apps.evidence.platform'),
              installer.operatingSystem || t('admin.apps.evidence.choosePlatform'),
            ],
          ];
          return (
            <article key={`${installer.operatingSystem || 'neutral'}-${installer.filename || index}`}>
              <strong>
                {installer.operatingSystem
                  ? operatingSystemLabel(installer.operatingSystem)
                  : t('admin.apps.evidence.neutralInstaller')}
              </strong>
              <dl>
                {facts.map(([label, value]) => (
                  <div key={label}>
                    <dt>{label}</dt>
                    <dd>{value || t('common.notAvailable')}</dd>
                  </div>
                ))}
              </dl>
            </article>
          );
        })}
      </div>
      {inspection.ai?.status === 'ready' ? (
        <p className="ai-provenance">
          <Wand2 size={15} />
          {t('admin.apps.evidence.ai', {
            provider: inspection.ai.provider || t('common.notAvailable'),
            model: inspection.ai.model || t('common.notAvailable'),
          })}
        </p>
      ) : null}
    </section>
  );
}

function AdminAppIcon({ app }: { app: CatalogApp }) {
  return (
    <span className="admin-app-row-icon" aria-hidden="true">
      {app.iconUrl ? (
        <img className="app-mini-icon" src={app.iconUrl} alt="" loading="lazy" />
      ) : (
        <span className="app-mini-icon app-mini-icon-fallback">
          {app.name.slice(0, 1).toUpperCase()}
        </span>
      )}
    </span>
  );
}

function AdminEditorIcon({ form }: { form: EditorForm }) {
  return form.iconUrl ? (
    <img className="admin-editor-icon" src={form.iconUrl} alt="" />
  ) : (
    <span className="admin-editor-icon admin-editor-icon-fallback" aria-hidden="true">
      {(form.name || '?').slice(0, 1).toUpperCase()}
    </span>
  );
}

function formFromApp(app: AppDetails): EditorForm {
  return {
    name: app.name ?? '',
    publisher: app.publisher ?? '',
    officialUrl: app.officialUrl ?? '',
    latestVersion: app.latestVersion ?? '',
    description: app.description ?? '',
    longDescription: app.longDescription ?? '',
    iconUrl: app.iconUrl ?? '',
  };
}

function formFromSuggestions(
  current: EditorForm,
  suggestions: ManualInstallerSuggestions,
): EditorForm {
  return {
    name: suggestions.name.value ?? current.name,
    publisher: suggestions.publisher.value ?? current.publisher,
    officialUrl: suggestions.officialUrl.value ?? current.officialUrl,
    latestVersion: suggestions.latestVersion.value ?? current.latestVersion,
    description: suggestions.description.value ?? current.description,
    longDescription: suggestions.longDescription.value ?? current.longDescription,
    iconUrl: suggestions.iconUrl.value ?? current.iconUrl,
  };
}

function suggestionProvenance(
  suggestion: ManualFieldSuggestion | null | undefined,
  currentValue: string,
): ManualSuggestionSource | undefined {
  if (!suggestion) return undefined;
  return (suggestion.value ?? '').trim() === currentValue.trim()
    ? suggestion.source
    : 'manual';
}

function editorPayload(form: EditorForm) {
  return {
    name: form.name.trim(),
    publisher: nullable(form.publisher),
    officialUrl: nullable(form.officialUrl),
    latestVersion: nullable(form.latestVersion),
    description: nullable(form.description),
    longDescription: nullable(form.longDescription),
    iconUrl: nullable(form.iconUrl),
  };
}

function nullable(value: string): string | null {
  return value.trim() || null;
}

function isUnresolved(app: AppDetails): boolean {
  return !app.downloadable
    && ['requires_manual_review', 'missing', 'broken'].includes(app.resolutionStatus);
}

function isUnresolvedFilter(value: AdminAppFilter): boolean {
  return value === 'unresolved' || value === 'review' || value === 'missing';
}

function filterLabel(filter: AdminAppFilter): string {
  return filter === 'unresolved'
    ? t('admin.apps.filter.unresolved')
    : t(`catalog.filter.${filter}` as const);
}

function manualInspectionInstallers(
  inspection: ManualInstallerInspection,
): ManualInstallerInspection['installers'] {
  if (inspection.installers?.length) return inspection.installers;
  return inspection.installer ? [inspection.installer] : [];
}

function operatingSystemLabel(value: OperatingSystem): string {
  if (value === 'macos') return 'macOS';
  if (value === 'linux') return 'Linux';
  return 'Windows';
}

function validateManualUrls(
  installerUrls: Record<OperatingSystem, string>,
  sourcePageUrl: string,
): string | null {
  const sourcePageError = validateHttpsUrl(
    sourcePageUrl,
    t('admin.apps.manual.sourcePageUrl'),
  );
  if (sourcePageError) return sourcePageError;

  const configured = (['windows', 'macos', 'linux'] as OperatingSystem[])
    .filter((operatingSystem) => installerUrls[operatingSystem].trim());
  if (configured.length === 0) {
    return t('admin.apps.validation.atLeastOneInstaller');
  }
  for (const operatingSystem of configured) {
    const validationError = validateHttpsUrl(
      installerUrls[operatingSystem],
      t(`admin.apps.manual.${operatingSystem}InstallerUrl` as const),
    );
    if (validationError) return validationError;
  }
  return null;
}

function validateHttpsUrl(value: string, label: string): string | null {
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password) {
      return t('admin.apps.validation.https', { field: label });
    }
  } catch {
    return t('admin.apps.validation.https', { field: label });
  }
  return null;
}

function validateOptionalWebsiteInstallerUrls(
  values: Record<OperatingSystem, string>,
): string | null {
  for (const operatingSystem of ['windows', 'macos', 'linux'] as OperatingSystem[]) {
    const value = values[operatingSystem].trim();
    if (!value) continue;
    const label = t(`admin.apps.website.${operatingSystem}InstallerUrl` as const);
    const validationError = validateHttpsUrl(value, label);
    if (validationError) return validationError;
  }
  return null;
}

function errorMessage(error: unknown, fallbackKey: string): string {
  if (!(error instanceof ApiRequestError)) return t(fallbackKey as never);
  const knownKey = `admin.apps.error.code.${error.code}`;
  const translated = t(knownKey as never);
  return translated === knownKey ? t(fallbackKey as never) : translated;
}

function phaseLabel(phase: string): string {
  const key = `admin.apps.inspection.phase.${phase}`;
  const translated = t(key as never);
  return translated === key ? phase.replace(/_/g, ' ') : translated;
}

function inspectionErrorLabel(code?: string | null): string {
  if (!code) return t('admin.apps.inspection.failedGeneric');
  const key = `admin.apps.error.code.${code}`;
  const translated = t(key as never);
  return translated === key ? t('admin.apps.inspection.failedGeneric') : translated;
}

function warningLabel(code: string): string {
  if (code.startsWith('ai:')) return t('admin.apps.warning.ai');
  if (code.startsWith('icon:')) return t('admin.apps.warning.icon');
  if (code === 'installers:not_found') return t('admin.apps.warning.installersNotFound');
  if (code.startsWith('installers:')) return t('admin.apps.warning.installersChanged');
  if (code.startsWith('official_url:query_removed_after_')) {
    return t('admin.apps.warning.officialUrlQueryFallback');
  }
  if (code.startsWith('official_url:')) return t('admin.apps.warning.officialUrl');
  if (code.startsWith('source_page:')) return t('admin.apps.warning.sourcePage');
  if (code.startsWith('retry:')) return t('admin.apps.warning.retry');
  return code;
}

function formatBytes(value?: number | null): string {
  if (!value) return t('common.notAvailable');
  const units = ['B', 'KB', 'MB', 'GB'];
  let amount = value;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024;
    unit += 1;
  }
  return `${amount.toLocaleString('es-ES', { maximumFractionDigits: 1 })} ${units[unit]}`;
}
