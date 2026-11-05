import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { createOwnBundle } from '../../api/account';
import { fetchBundle } from '../../api/bundles';
import { fetchApps } from '../../api/catalogApps';
import { useAuth } from '../../auth/AuthContext';
import { parseCatalogFilters } from '../../catalogFilters';
import { isCatalogAppSelectable } from '../../catalogSelection';
import { AsyncContent } from '../../components/AsyncContent';
import { BundleDownloadButton } from '../../components/BundleDownloadButton';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { lazyNamed } from '../../routing/lazyNamed';
import { semanticBundleProposal } from '../../semanticBundleProposal';
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
  const filters = parseCatalogFilters(params);
  const scope = `${slug ?? ''}:${params}:${account?.id ?? 'anonymous'}`;
  const [edited, setEdited] = useState<{ scope: string; apps: CatalogApp[]; name: string }>();
  const [locked, setLocked] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const resource = useAsyncResource(async (signal) => {
    if (slug) {
      const bundle = await fetchBundle(slug);
      if (params.has('clone') && bundle.visibility === 'private') throw new Error('bundle_not_public');
      return { apps: bundle.apps, name: t('bundle.draft.copyName', { name: bundle.name }).slice(0, 160),
        description: bundle.description ?? '', tags: bundle.tags, semantic: false, degraded: false,
        canSave: bundle.visibility !== 'private' };
    }
    const response = await fetchApps({ ...filters, filter: 'available', sort: 'relevance',
      searchMode: 'semantic', page: 1, pageSize: 12 }, signal);
    return { apps: semanticBundleProposal(response), name: filters.query.slice(0, 160),
      description: '', tags: [], semantic: true, degraded: response.appliedMode !== 'semantic', canSave: true };
  }, { scope, enabled: Boolean(slug || filters.query.length >= 3) });
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

  return <main className="content-page">
    <h1>{t(slug ? 'bundle.draft.customize' : 'bundle.draft.propose')}</h1>
    <p>{t('bundle.draft.temporary')}</p>
    <AsyncContent {...resource} hasData={Boolean(draft)} onRetry={resource.refresh} errorMessage={t('bundle.loadError')}>{null}</AsyncContent>
    {draft ? <>
      {draft.semantic ? <p className="semantic-notice" role="status">{t(draft.degraded ? 'bundle.draft.unavailable' : 'bundle.draft.explanation', { query: filters.query })}</p> : null}
      {draft.semantic && !draft.degraded && draft.apps.length ? <details>
        <summary>{t('bundle.draft.why')}</summary>
        <ul>{draft.apps.map((app) => <li key={app.id}>
          <Link to={`/catalog/app/${encodeURIComponent(app.id)}`}>{app.name}</Link>: {' '}
          {app.description?.slice(0, 400) || t('bundle.draft.noDescription')}
          {app.tags.length ? ` (${app.tags.join(', ')})` : ''}
        </li>)}</ul>
      </details> : null}
      {!apps.length ? <p>{t('bundle.draft.empty')}</p> : null}
      <fieldset className="async-editor-fields" disabled={locked || saving}>
        <BundleAppPicker apps={apps} onChange={(next) => change(next)} />
      </fieldset>
      {error ? <p className="error-banner" role="alert">{error}</p> : null}
      {account?.role === 'USER' && draft.canSave ? <form className="account-card" onSubmit={(event) => { event.preventDefault(); void save(); }}>
        <label>{t('account.bundles.name')}<input value={name} maxLength={160} required
          disabled={saving} onChange={(event) => change(apps, event.target.value)} /></label>
        <button className="secondary-button" type="submit" disabled={saving || !apps.length}>{t('bundle.draft.save')}</button>
      </form> : null}
      <BundleDownloadButton key={scope} bundleId="draft" bundleName={name} appCount={apps.length}
        appIds={apps.map((app) => app.id)} operatingSystems={availability.map((item) => item.operatingSystem)}
        platformAvailability={availability} onSelectionLockedChange={setLocked} />
    </> : null}
    <p><Link to={slug ? `/bundles/${encodeURIComponent(slug)}` : `/catalog?${params}`}>{t('bundle.draft.back')}</Link></p>
  </main>;
}
