import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { DeferredSection } from '../../components/DeferredSection';
import {
  ExternalLink,
  Save,
  Trash2
} from 'lucide-react';
import {
  type FormEvent,
  useEffect, useState
} from 'react';
import {
  Link,
  useNavigate,
  useParams
} from 'react-router-dom';
import {
  createOwnBundle, deleteOwnBundle, fetchOwnBundle, updateOwnBundle
} from '../../api/account';
import { lazyNamed } from '../../routing/lazyNamed';
import { BundleTagPicker } from '../../components/BundleTagPicker';
import { useTranslation } from '../../services/i18n';
import type { OwnBundleDetails, OwnBundleInput } from '../../types/account';
import type { CatalogApp } from '../../types/catalog';
import { bundleSlug } from '../../utils/bundleSlug';
const BundleAppPicker = lazyNamed(() => import('../../components/BundleAppPicker'), 'BundleAppPicker');

import { apiMessage } from './accountSupport';

export function BundleEditorPage() {
  const t = useTranslation();
  const { id } = useParams();
  const editing = Boolean(id);
  const navigate = useNavigate();
  const [bundle, setBundle] = useState<OwnBundleDetails | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(editing);
  const [tags, setTags] = useState<string[]>([]);
  const [visibility, setVisibility] = useState<'private' | 'public'>('private');
  const [selected, setSelected] = useState<CatalogApp[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const resource = useAsyncResource(() => fetchOwnBundle(id!), { scope: id, enabled: editing });
  const ready = !editing || bundle?.id === id;
  useEffect(() => {
    const value = resource.data;
    if (!value) return;
    setBundle(value); setName(value.name); setDescription(value.description ?? '');
    setSlug(value.slug); setSlugEdited(true); setTags(value.tags); setVisibility(value.visibility);
    setSelected(value.apps);
  }, [resource.data]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting || !ready) return;
    setSubmitting(true); setError(null);
    const input: OwnBundleInput = {
      name, description, slug: bundleSlug(slug), tags,
      appIds: selected.map((app) => app.id),
    };
    try {
      const saved = editing && id && bundle
        ? await updateOwnBundle(id, { ...input, visibility, expectedVersion: bundle.version })
        : await createOwnBundle(input);
      await navigate(`/dashboard/bundles/${saved.id}/edit`, { replace: true });
      setBundle(saved);
    } catch (cause) {
      setError(apiMessage(t, cause, 'account.bundles.saveFailed'));
    } finally {
      setSubmitting(false);
    }
  }

  async function remove() {
    if (!id || !window.confirm(t('account.bundles.deleteConfirm'))) return;
    setSubmitting(true);
    try { await deleteOwnBundle(id); await navigate('/dashboard/bundles', { replace: true }); }
    catch (cause) { setError(apiMessage(t, cause, 'account.bundles.deleteFailed')); setSubmitting(false); }
  }

  return <section className="account-page bundle-editor-page"><header><h2>{editing ? t('account.bundles.edit') : t('account.bundles.create')}</h2></header>
    <AsyncContent {...resource} hasData={false} onRetry={resource.refresh}
      errorMessage={resource.error ? apiMessage(t, resource.error, 'account.bundles.failed') : undefined}>{null}</AsyncContent>
    <form className="bundle-account-editor" onSubmit={submit} aria-busy={!ready}>
      <fieldset className="async-editor-fields" disabled={!ready || submitting}>
      <div className="account-card bundle-fields bundle-editor-fields">
        <label>{t('account.bundles.name')}<input value={name} onChange={(event) => {
          setName(event.target.value);
          if (!slugEdited) setSlug(bundleSlug(event.target.value));
        }} required maxLength={160} /></label>
        <label>{t('account.bundles.slug')}<input value={slug} onChange={(event) => {
          setSlug(event.target.value); setSlugEdited(Boolean(event.target.value));
        }} onBlur={() => setSlug(bundleSlug(slug || name))} maxLength={180} /></label>
        <label className="bundle-description-field">{t('account.bundles.description')}<textarea value={description} onChange={(event) => setDescription(event.target.value)} maxLength={4000} /></label>
        {ready ? <BundleTagPicker tags={tags} onChange={setTags} /> : null}
        {editing ? <label>{t('account.bundles.visibility')}<select value={visibility} onChange={(event) => setVisibility(event.target.value as 'private' | 'public')}><option value="private">{t('account.private')}</option><option value="public">{t('account.public')}</option></select></label> : null}
        {visibility === 'public' ? <p className="form-message bundle-publication-notice">{t('account.bundles.publicationNotice')}</p> : null}
        {ready && bundle?.visibility === 'public' ? <div className="bundle-description-field bundle-public-link">
          <label>{t('account.bundles.publicLink')}
            <input readOnly type="url" value={`${window.location.origin}/bundles/${encodeURIComponent(bundle.id)}`} />
          </label>
          <Link className="secondary-button compact-button" to={`/bundles/${encodeURIComponent(bundle.id)}`}>
            <ExternalLink size={17} aria-hidden="true" />{t('account.bundles.openPublic')}
          </Link>
        </div> : null}
      </div>
      {ready ? <DeferredSection><BundleAppPicker apps={selected} onChange={setSelected} /></DeferredSection> : null}
      <footer className="bundle-editor-footer">
        {error ? <p className="error-banner" role="alert">{error}</p> : null}
        <div className="editor-actions"><button className="primary-button" type="submit" disabled={submitting}><Save size={18} />{t('account.save')}</button>
          {editing ? <button className="danger-button" type="button" onClick={remove} disabled={submitting}><Trash2 size={18} />{t('account.delete')}</button> : null}</div>
      </footer>
      </fieldset>
    </form>
  </section>;
}
