import { ArrowDown, ArrowUp, Boxes, Plus, Save, X } from 'lucide-react';
import { type FormEvent, useEffect, useMemo, useState } from 'react';
import { fetchAdminApps } from '../../api/adminApps';
import { fetchAdminAudit, fetchAdminRequests } from '../../api/adminMeta';
import {
  createAdminBundle,
  fetchBundle,
  fetchBundles,
  updateAdminBundle,
} from '../../api/bundles';
import { fetchCatalogStats } from '../../api/catalogApps';
import { fetchAdminCurrentRun } from '../../api/scraperAdmin';
import { AppMiniIcon } from '../../components/AppMiniIcon';
import { AdminTable } from '../../components/admin/AdminTable';
import { useTranslation } from '../../services/i18n';
import type {
  AuditItem,
  BundleDetails,
  BundleSummary,
  CatalogApp,
  CatalogStats,
  ScraperRunSummary,
  SoftwareRequestItem,
} from '../../types/catalog';
import { formatDate } from '../../utils/date';

export function AdminDashboard() {
  const t = useTranslation();
  const [stats, setStats] = useState<CatalogStats | null>(null);
  const [current, setCurrent] = useState<ScraperRunSummary | null>(null);

  useEffect(() => {
    fetchCatalogStats().then(setStats).catch(() => setStats(null));
    fetchAdminCurrentRun().then(setCurrent).catch(() => setCurrent(null));
  }, []);

  return (
    <section className="admin-panel">
      <h2>{t('admin.dashboard.title')}</h2>
      <div className="metric-grid">
        <Metric label={t('admin.layout.apps')} value={stats?.total ?? 0} />
        <Metric label={t('catalog.filter.available')} value={stats?.filters.available ?? 0} />
        <Metric label={t('catalog.filter.review')} value={stats?.filters.review ?? 0} />
        <Metric label={t('catalog.filter.missing')} value={stats?.filters.missing ?? 0} />
      </div>
      <div className="admin-card">
        <h3>{t('admin.dashboard.currentScraper')}</h3>
        <p>{current?.currentAppName || current?.status || t('admin.dashboard.noCurrentRun')}</p>
        <small>{current?.currentPhase || '-'}</small>
      </div>
    </section>
  );
}
function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="metric-card">
      <span>{label}</span>
      <strong>{value.toLocaleString('es-ES')}</strong>
    </div>
  );
}

export function AdminBundlesPage() {
  const t = useTranslation();
  const [official, setOfficial] = useState<BundleSummary[]>([]);
  const [selected, setSelected] = useState<BundleDetails | null>(null);
  const [form, setForm] = useState({ name: '', description: '', tags: '' });
  const [bundleApps, setBundleApps] = useState<CatalogApp[]>([]);
  const [appQuery, setAppQuery] = useState('');
  const [appResults, setAppResults] = useState<CatalogApp[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const availableAppResults = useMemo(() => {
    const selectedAppIds = new Set(bundleApps.map((app) => app.id));
    return appResults.filter((app) => !selectedAppIds.has(app.id));
  }, [appResults, bundleApps]);

  useEffect(() => {
    void loadBundles();
  }, []);

  useEffect(() => {
    let cancelled = false;
    fetchAdminApps({ query: appQuery, filter: 'all', sort: 'updated', page: 1, pageSize: 12 })
      .then((response) => {
        if (!cancelled) setAppResults(response.data);
      })
      .catch(() => {
        if (!cancelled) setAppResults([]);
      });
    return () => {
      cancelled = true;
    };
  }, [appQuery]);

  async function loadBundles() {
    try {
      const response = await fetchBundles({ type: 'official', pageSize: 30 });
      setOfficial(response.data);
    } catch {
      setOfficial([]);
    }
  }

  function resetBundleEditor() {
    setSelected(null);
    setForm({ name: '', description: '', tags: '' });
    setBundleApps([]);
    setMessage(null);
  }

  async function selectBundle(bundle: BundleSummary) {
    const details = await fetchBundle(bundle.id);
    setSelected(details);
    setForm({
      name: details.name,
      description: details.description ?? '',
      tags: details.tags.join(', '),
    });
    setBundleApps(details.apps);
    setMessage(null);
  }

  function addBundleApp(app: CatalogApp) {
    setBundleApps((current) => (current.some((item) => item.id === app.id) ? current : [...current, app]));
  }

  function removeBundleApp(appId: string) {
    setBundleApps((current) => current.filter((app) => app.id !== appId));
  }

  function moveBundleApp(appId: string, direction: -1 | 1) {
    setBundleApps((current) => {
      const index = current.findIndex((app) => app.id === appId);
      const nextIndex = index + direction;
      if (index < 0 || nextIndex < 0 || nextIndex >= current.length) return current;
      const next = [...current];
      [next[index], next[nextIndex]] = [next[nextIndex], next[index]];
      return next;
    });
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    try {
      const payload = {
        name: form.name,
        description: form.description,
        type: 'official',
        visibility: 'official',
        tags: form.tags.split(',').map((tag) => tag.trim()).filter(Boolean),
        appIds: bundleApps.map((app) => app.id),
      };
      const saved = selected
        ? await updateAdminBundle(selected.id, payload)
        : await createAdminBundle(payload);
      setSelected(saved);
      setBundleApps(saved.apps);
      setForm({
        name: saved.name,
        description: saved.description ?? '',
        tags: saved.tags.join(', '),
      });
      await loadBundles();
      setMessage(t('admin.message.bundleSaved'));
    } catch {
      setMessage(t('admin.message.saveBundleError'));
    }
  }

  return (
    <section className="admin-panel two-column-admin">
      <div>
        <div className="admin-section-heading">
          <h2>{t('admin.bundle.official')}</h2>
          <button className="secondary-button compact-button" type="button" onClick={resetBundleEditor}>
            <Plus size={17} />
            {t('admin.bundle.new')}
          </button>
        </div>
        <div className="bundle-grid admin-bundles">
          {official.map((bundle) => (
            <button
              className={`bundle-card admin-bundle-button ${selected?.id === bundle.id ? 'admin-list-active' : ''}`}
              type="button"
              key={bundle.id}
              onClick={() => void selectBundle(bundle)}
            >
              <div className="bundle-card-header">
                <span className="bundle-icon"><Boxes size={22} /></span>
                <div>
                  <h3>{bundle.name}</h3>
                  <small>{t('bundle.appCount', { count: bundle.appCount })}</small>
                </div>
              </div>
              <p>{bundle.description || t('bundle.fallbackDescription')}</p>
            </button>
          ))}
        </div>
      </div>
      <form className="admin-card editor-form" onSubmit={save}>
        <div className="editor-header">
          <div>
            <span>{selected ? t('admin.bundle.editing') : t('admin.bundle.newBundle')}</span>
            <h3>{selected ? selected.name : t('admin.bundle.editor')}</h3>
          </div>
          {selected ? <small>{selected.id}</small> : null}
        </div>
        <label>{t('admin.field.name')}<input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label>{t('admin.field.description')}<textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
        <label>{t('admin.field.tags')}<input value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} placeholder={t('admin.bundle.placeholder.tags')} /></label>
        <div className="bundle-app-editor">
          <h4>{t('admin.bundle.apps')}</h4>
          <div className="bundle-app-selected">
            {bundleApps.length ? bundleApps.map((app, index) => (
              <div className="bundle-app-edit-row" key={app.id}>
                <AppMiniIcon app={app} />
                <span>{app.name}</span>
                <button type="button" onClick={() => moveBundleApp(app.id, -1)} disabled={index === 0} title={t('admin.bundle.moveUp')}>
                  <ArrowUp size={16} />
                </button>
                <button type="button" onClick={() => moveBundleApp(app.id, 1)} disabled={index === bundleApps.length - 1} title={t('admin.bundle.moveDown')}>
                  <ArrowDown size={16} />
                </button>
                <button type="button" onClick={() => removeBundleApp(app.id)} title={t('admin.bundle.removeApp')}>
                  <X size={16} />
                </button>
              </div>
            )) : <p className="empty-state">{t('admin.bundle.addAppsEmpty')}</p>}
          </div>
          <input
            value={appQuery}
            onChange={(event) => setAppQuery(event.target.value)}
            placeholder={t('admin.bundle.searchApps')}
          />
          <div className="app-picker-results">
            {availableAppResults.map((app) => (
              <button type="button" key={app.id} onClick={() => addBundleApp(app)}>
                <AppMiniIcon app={app} />
                <span>{app.name}</span>
                <Plus size={16} />
              </button>
            ))}
          </div>
        </div>
        {message ? <span className="form-message">{message}</span> : null}
        <button className="primary-button" type="submit">
          <Save size={17} />
          {selected ? t('common.saveChanges') : t('admin.bundle.create')}
        </button>
      </form>
    </section>
  );
}

export function AdminRequestsPage() {
  const t = useTranslation();
  const [requests, setRequests] = useState<SoftwareRequestItem[]>([]);
  useEffect(() => {
    fetchAdminRequests().then(setRequests).catch(() => setRequests([]));
  }, []);
  return (
    <section className="admin-panel">
      <h2>{t('admin.request.title')}</h2>
      <AdminTable title={t('admin.request.pending')} rows={requests.map((request) => [request.requestedName, request.officialUrl, request.status])} />
    </section>
  );
}

export function AdminAuditPage() {
  const t = useTranslation();
  const [items, setItems] = useState<AuditItem[]>([]);
  useEffect(() => {
    fetchAdminAudit().then(setItems).catch(() => setItems([]));
  }, []);
  return (
    <section className="admin-panel">
      <h2>{t('admin.audit.title')}</h2>
      <AdminTable title={t('admin.audit.recentActions')} rows={items.map((item) => [item.actor, item.action, item.targetId || '-', formatDate(item.createdAt)])} />
    </section>
  );
}
