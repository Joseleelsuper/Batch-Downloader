import { AsyncContent } from '../../components/AsyncContent';
import { DeferredSection } from '../../components/DeferredSection';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { Boxes, Plus, Save, Trash2 } from 'lucide-react';
import { type FormEvent, useEffect, useRef, useState } from 'react';
import {
  createAdminBundle,
  deleteAdminBundle,
  fetchBundle,
  fetchBundles,
  updateAdminBundle,
} from '../../api/bundles';
import { lazyNamed } from '../../routing/lazyNamed';
import { BundleTagPicker } from '../../components/BundleTagPicker';
import { useTranslation } from '../../services/i18n';
import type {
  BundleDetails,
  BundleSummary,
  CatalogApp
} from '../../types/catalog';
import { bundleSlug } from '../../utils/bundleSlug';
const BundleAppPicker = lazyNamed(() => import('../../components/BundleAppPicker'), 'BundleAppPicker');

export function AdminBundlesPage() {
  const t = useTranslation();
  const bundlesResource = useAsyncResource((signal) => fetchBundles({ type: 'official', pageSize: 30 }, signal));
  const official = bundlesResource.data?.data ?? [];
  const detailRequest = useRef(0);
  const [pendingBundle, setPendingBundle] = useState<BundleSummary | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [saving, setSaving] = useState(false);
  const [selected, setSelected] = useState<BundleDetails | null>(null);
  const [inactiveRemovalAccepted, setInactiveRemovalAccepted] = useState(false);
  const [form, setForm] = useState({ name: '', slug: '', description: '', tags: [] as string[] });
  const [slugEdited, setSlugEdited] = useState(false);
  const [bundleApps, setBundleApps] = useState<CatalogApp[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const inactiveAppCount = selected?.inactiveAppCount ?? 0;

  useEffect(() => () => { detailRequest.current += 1; }, []);

  function resetBundleEditor() {
    detailRequest.current += 1;
    setPendingBundle(null);
    setLoadingDetail(false);
    setDetailError(false);
    setSelected(null);
    setInactiveRemovalAccepted(false);
    setForm({ name: '', slug: '', description: '', tags: [] });
    setSlugEdited(false);
    setBundleApps([]);
    setMessage(null);
  }

  async function selectBundle(bundle: BundleSummary) {
    const requestId = ++detailRequest.current;
    setPendingBundle(bundle);
    setLoadingDetail(true);
    setDetailError(false);
    try {
      const details = await fetchBundle(bundle.id);
      if (requestId !== detailRequest.current) return;
      setSelected(details);
      setInactiveRemovalAccepted(false);
      setForm({ name: details.name, slug: details.slug, description: details.description ?? '', tags: details.tags });
      setSlugEdited(true);
      setBundleApps(details.apps);
      setMessage(null);
    } catch {
      if (requestId === detailRequest.current) setDetailError(true);
    } finally {
      if (requestId === detailRequest.current) setLoadingDetail(false);
    }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loadingDetail || detailError || saving || (inactiveAppCount > 0 && !inactiveRemovalAccepted)) return;
    setSaving(true);
    setMessage(null);
    try {
      const payload = {
        name: form.name,
        slug: bundleSlug(form.slug || form.name),
        description: form.description,
        type: 'official',
        visibility: 'official',
        tags: form.tags,
        appIds: bundleApps.map((app) => app.id),
      };
      const saved = selected
        ? await updateAdminBundle(selected.id, payload)
        : await createAdminBundle(payload);
      setSelected(saved);
      setInactiveRemovalAccepted(false);
      setBundleApps(saved.apps);
      setForm({
        name: saved.name,
        slug: saved.slug,
        description: saved.description ?? '',
        tags: saved.tags,
      });
      setSlugEdited(true);
      bundlesResource.refresh();
      setMessage(t('admin.message.bundleSaved'));
    } catch {
      setMessage(t('admin.message.saveBundleError'));
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!selected || loadingDetail || detailError || saving
      || !window.confirm(t('admin.bundle.deleteConfirm', { name: selected.name }))) return;
    setSaving(true);
    setMessage(null);
    try {
      await deleteAdminBundle(selected.id);
      resetBundleEditor();
      bundlesResource.refresh();
      setMessage(t('admin.bundle.deleted'));
    } catch {
      setMessage(t('admin.bundle.deleteFailed'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="admin-panel">
      <div>
        <div className="admin-section-heading">
          <h2>{t('admin.bundle.official')}</h2>
          <button className="secondary-button compact-button" type="button" disabled={saving} onClick={resetBundleEditor}>
            <Plus size={17} />
            {t('admin.bundle.new')}
          </button>
        </div>
        <AsyncContent {...bundlesResource} hasData={bundlesResource.data !== undefined} onRetry={bundlesResource.refresh}>
        <div className="bundle-grid admin-bundles">
          {official.map((bundle) => (
            <button
              className={`bundle-card admin-bundle-button ${selected?.id === bundle.id ? 'admin-list-active' : ''}`}
              type="button"
              key={bundle.id}
              disabled={saving}
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
        {!official.length ? <p className="empty-state">{t('admin.table.empty')}</p> : null}
        </AsyncContent>
      </div>
      <form className="bundle-admin-editor" onSubmit={save} aria-busy={loadingDetail}>
        <AsyncContent loading={loadingDetail} error={detailError} hasData={false}
          onRetry={() => { if (pendingBundle) void selectBundle(pendingBundle); }}>{null}</AsyncContent>
        <fieldset className="async-editor-fields" disabled={loadingDetail || detailError || saving}>
        <div className="editor-header">
          <div>
            <h3>{selected ? selected.name : t('admin.bundle.editor')}</h3>
          </div>
          {selected ? <small>{selected.id}</small> : null}
        </div>
        <div className="admin-card bundle-fields bundle-editor-fields">
          <label>{t('admin.field.name')}<input required maxLength={160} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value, slug: slugEdited ? form.slug : bundleSlug(e.target.value) })} /></label>
          <label>{t('account.bundles.slug')}<input maxLength={180} value={form.slug} onChange={(e) => {
            setForm({ ...form, slug: e.target.value }); setSlugEdited(Boolean(e.target.value));
          }} onBlur={() => setForm((current) => ({ ...current, slug: bundleSlug(current.slug || current.name) }))} /></label>
          <label className="bundle-description-field">{t('admin.field.description')}<textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
          <BundleTagPicker tags={form.tags} onChange={(tags) => setForm((current) => ({ ...current, tags }))} administrator />
        </div>
        {inactiveAppCount > 0 ? <div className="admin-card bundle-inactive-warning">
          <p id="inactive-bundle-apps-warning" role="alert">{t('admin.bundle.inactiveWarning', { count: inactiveAppCount })}</p>
          <label><input type="checkbox" checked={inactiveRemovalAccepted}
            aria-describedby="inactive-bundle-apps-warning" onChange={(event) => setInactiveRemovalAccepted(event.target.checked)} />
            {t('admin.bundle.acceptInactiveRemoval')}
          </label>
        </div> : null}
        <DeferredSection><BundleAppPicker apps={bundleApps} onChange={setBundleApps} administrator /></DeferredSection>
        {message ? <span className="form-message" role="status">{message}</span> : null}
        <footer className="bundle-editor-footer"><div className="editor-actions">
        <button className="primary-button bundle-save-button" type="submit" disabled={inactiveAppCount > 0 && !inactiveRemovalAccepted}>
          <Save size={17} />
          {selected ? t('common.saveChanges') : t('admin.bundle.create')}
        </button>
        {selected ? <button className="danger-button" type="button" onClick={() => void remove()}>
          <Trash2 size={17} />{t('admin.bundle.delete')}
        </button> : null}
        </div></footer>
        </fieldset>
      </form>
    </section>
  );
}
