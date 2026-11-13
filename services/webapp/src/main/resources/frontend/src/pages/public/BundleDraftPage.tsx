import { useState } from 'react';
import { ArrowLeft } from 'lucide-react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { createOwnBundle } from '../../api/account';
import { fetchBundle } from '../../api/bundles';
import { useAuth } from '../../auth/AuthContext';
import { isCatalogAppSelectable } from '../../catalogSelection';
import { AsyncContent } from '../../components/AsyncContent';
import { BundleDownloadButton } from '../../components/BundleDownloadButton';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { lazyNamed } from '../../routing/lazyNamed';
import { useTranslation } from '../../services/i18n';
import type { CatalogApp, OperatingSystem } from '../../types/catalog';
import { bundleSlug } from '../../utils/bundleSlug';
import { apiMessage } from '../account/accountSupport';

const BundleAppPicker = lazyNamed(() => import('../../components/BundleAppPicker'), 'BundleAppPicker');

export function BundleDraftPage() {
  const t = useTranslation();
  const { account } = useAuth();
  const { slug } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const scope = `${slug ?? ''}:${params}:${account?.id ?? 'anonymous'}`;
  const [edited, setEdited] = useState<{ scope: string; apps: CatalogApp[]; name: string }>();
  const [locked, setLocked] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const resource = useAsyncResource(async () => {
    const bundle = await fetchBundle(slug!);
    if (params.has('clone') && bundle.visibility === 'private') throw new Error('bundle_not_public');
    return { apps: bundle.apps, name: t('bundle.draft.copyName', { name: bundle.name }).slice(0, 160),
      description: bundle.description ?? '', tags: bundle.tags, canSave: bundle.visibility !== 'private' };
  }, { scope, enabled: Boolean(slug) });
  const draft = resource.data;
  const current = edited?.scope === scope ? edited : undefined;
  const apps = current?.apps ?? draft?.apps ?? [];
  const name = current?.name ?? draft?.name ?? '';
  const availability = (['windows', 'linux', 'macos'] as OperatingSystem[]).flatMap((os) => {
    const compatible = apps.filter((app) => isCatalogAppSelectable(app) && app.operatingSystems.includes(os));
    return compatible.length ? [{ operatingSystem: os, downloadableAppCount: compatible.length, previewApps: compatible.slice(0, 5) }] : [];
  });

  function change(nextApps: CatalogApp[], nextName = name) {
    setEdited({ scope, apps: nextApps, name: nextName });
  }

  async function save() {
    if (saving || !name.trim() || !apps.length || !draft?.canSave) return;
    setSaving(true); setError(null);
    try {
      const saved = await createOwnBundle({ name: name.trim(), slug: bundleSlug(name), description: draft.description,
        tags: draft.tags, appIds: apps.map((app) => app.id) });
      await navigate(`/dashboard/bundles/${saved.id}/edit`);
    } catch (cause) { setError(apiMessage(t, cause, 'account.bundles.saveFailed')); }
    finally { setSaving(false); }
  }

  return <main className="content-page bundle-draft-page">
    <header className="bundle-detail-header bundle-draft-header">
      <div className="bundle-draft-heading">
        <h1>{t('bundle.draft.customize')}</h1>
        <p>{t('bundle.draft.temporary')}</p>
      </div>
      {draft ? <div className="bundle-detail-actions bundle-draft-download">
        <BundleDownloadButton key={scope} bundleId="draft" bundleName={name} appCount={apps.length}
          appIds={apps.map((app) => app.id)} operatingSystems={availability.map((item) => item.operatingSystem)}
          platformAvailability={availability} onSelectionLockedChange={setLocked} />
      </div> : null}
    </header>
    <AsyncContent {...resource} hasData={Boolean(draft)} onRetry={resource.refresh} errorMessage={t('bundle.loadError')}>{null}</AsyncContent>
    {draft ? <>
      {!apps.length ? <p>{t('bundle.draft.empty')}</p> : null}
      <fieldset className="async-editor-fields" disabled={locked || saving}>
        <BundleAppPicker apps={apps} onChange={(next) => change(next)} />
      </fieldset>
      {error ? <p className="error-banner" role="alert">{error}</p> : null}
      {account?.role === 'USER' && draft.canSave ? <div className="bundle-draft-actions">
        <form className="bundle-fields bundle-draft-save" onSubmit={(event) => { event.preventDefault(); void save(); }}>
          <label>{t('account.bundles.name')}<input value={name} maxLength={160} required
            disabled={saving} onChange={(event) => change(apps, event.target.value)} /></label>
          <button className="secondary-button" type="submit" disabled={saving || !apps.length}>{t('bundle.draft.save')}</button>
        </form>
      </div> : null}
    </> : null}
    <p className="bundle-draft-back"><Link className="secondary-button" to={`/bundles/${encodeURIComponent(slug!)}`}>
      <ArrowLeft size={18} aria-hidden="true" />{t('bundle.draft.back')}
    </Link></p>
  </main>;
}
