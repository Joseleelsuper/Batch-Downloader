import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { useEffect, useState } from 'react';
import { requestJson } from '../../api/http';
import { useTranslation } from '../../services/i18n';
import type { AppDetails } from '../../types/catalog';
import '../../downloads/linux-installer.css';

interface ProfileVersion { version: number; status: 'draft' | 'approved'; profile: Record<string, unknown> }
interface DependencyVersion { version: number; dependencies: string[] }

export function LinuxInstallProfiles({ app }: Readonly<{ app: AppDetails }>) {
  const t = useTranslation();
  const [open, setOpen] = useState(false);
  const options = (app.downloadOptions ?? []).filter((option) => option.operatingSystem === 'linux');
  if (!options.length) return null;
  return (
    <details className="linux-recipe-editor" onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>{t('linux.admin.title')}</summary>
      {open ? <LinuxProfileForm key={app.id} appId={app.id} options={options} /> : null}
    </details>
  );
}

function LinuxProfileForm({ appId, options }: Readonly<{
  appId: string; options: NonNullable<AppDetails['downloadOptions']>;
}>) {
  const t = useTranslation();
  const [source, setSource] = useState(options[0].id);
  const [loadedProfile, setLoadedProfile] = useState<{ source: string; profile: ProfileVersion }>();
  const [loadedDependencies, setLoadedDependencies] = useState<DependencyVersion>();
  const [profileText, setProfileText] = useState('');
  const [dependenciesText, setDependenciesText] = useState('');
  const [status, setStatus] = useState<'draft' | 'approved'>('draft');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const base = '/api/v1/admin/apps/' + encodeURIComponent(appId) + '/linux';
  const profilePath = base + '/sources/' + encodeURIComponent(source) + '/profile';
  const profileResource = useAsyncResource((signal) => requestJson<ProfileVersion>(profilePath, { signal }), { scope: source });
  const dependenciesResource = useAsyncResource((signal) => requestJson<DependencyVersion>(base + '/dependencies', { signal }));
  const ready = loadedProfile?.source === source;

  useEffect(() => {
    const profile = profileResource.data;
    if (!profile) return;
    setLoadedProfile({ source, profile });
    setProfileText(JSON.stringify(profile.profile, null, 2));
    setStatus(profile.status);
    setMessage(null);
  }, [profileResource.data, source]);
  useEffect(() => {
    const dependencies = dependenciesResource.data;
    if (!dependencies) return;
    setLoadedDependencies(dependencies);
    setDependenciesText(dependencies.dependencies.join('\n'));
  }, [dependenciesResource.data]);

  async function saveProfile() {
    if (!loadedProfile || !ready || busy) return;
    setBusy(true);
    try {
      const profile: unknown = JSON.parse(profileText);
      const result = await requestJson<ProfileVersion>(profilePath, {
        method: 'PUT', body: JSON.stringify({ expectedVersion: loadedProfile.profile.version, status, profile }),
      });
      setLoadedProfile({ source, profile: result });
      setMessage(t('linux.admin.saved'));
    } catch { setMessage(t('linux.admin.error')); }
    finally { setBusy(false); }
  }

  async function saveDependencies() {
    if (!loadedDependencies || busy) return;
    setBusy(true);
    try {
      const dependencies = [...new Set(dependenciesText.split(/\s+/).filter(Boolean))];
      const result = await requestJson<DependencyVersion>(base + '/dependencies', {
        method: 'PUT', body: JSON.stringify({ expectedVersion: loadedDependencies.version, dependencies }),
      });
      setLoadedDependencies(result);
      setMessage(t('linux.admin.saved'));
    } catch { setMessage(t('linux.admin.error')); }
    finally { setBusy(false); }
  }

  return (
    <section aria-busy={busy}>
      <p>{t('linux.admin.description')}</p>
      <label htmlFor="linux-recipe-source">{t('linux.admin.source')}</label>
      <select id="linux-recipe-source" value={source} disabled={busy}
        onChange={(event) => setSource(event.target.value)}>
        {options.map((option) => <option key={option.id} value={option.id}>{option.filename ?? option.id}</option>)}
      </select>
      <AsyncContent {...profileResource} hasData={ready} onRetry={profileResource.refresh} errorMessage={t('linux.admin.error')}>
      <label htmlFor="linux-recipe-status">{t('linux.admin.status')}</label>
      <select id="linux-recipe-status" value={status} disabled={!ready || busy}
        onChange={(event) => setStatus(event.target.value as 'draft' | 'approved')}>
        <option value="draft">{t('linux.admin.draft')}</option>
        <option value="approved">{t('linux.admin.approved')}</option>
      </select>
      <label htmlFor="linux-recipe-json">{t('linux.admin.profile')}</label>
      <textarea id="linux-recipe-json" spellCheck={false} value={profileText} maxLength={100000}
        disabled={!ready || busy} onChange={(event) => setProfileText(event.target.value)} />
      <button type="button" className="secondary-button" disabled={!ready || busy}
        onClick={() => void saveProfile()}>{t('linux.admin.saveProfile')}</button>
      </AsyncContent>
      <AsyncContent {...dependenciesResource} hasData={loadedDependencies !== undefined} onRetry={dependenciesResource.refresh} errorMessage={t('linux.admin.error')}>
      <label htmlFor="linux-recipe-dependencies">{t('linux.admin.dependencies')}</label>
      <textarea id="linux-recipe-dependencies" value={dependenciesText} maxLength={3700}
        disabled={!loadedDependencies || busy} onChange={(event) => setDependenciesText(event.target.value)} />
      <button type="button" className="secondary-button" disabled={!loadedDependencies || busy}
        onClick={() => void saveDependencies()}>{t('linux.admin.saveDependencies')}</button>
      </AsyncContent>
      {message ? <output aria-live="polite">{message}</output> : null}
    </section>
  );
}
