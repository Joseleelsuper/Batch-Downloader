import { BundleActivity } from '../../components/BundleActivity';
import { useAuth } from '../../auth/AuthContext';
import { ChevronDown } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { fetchBundle } from '../../api/bundles';
import { fetchAppDetails } from '../../api/catalogApps';
import { isCatalogAppSelectable } from '../../catalogSelection';
import { AppDetailExpander } from '../../components/AppDetailExpander';
import { AppDetailsPanel } from '../../components/AppDetailsPanel';
import { AppMiniIcon } from '../../components/AppMiniIcon';
import { BundleDownloadButton } from '../../components/BundleDownloadButton';
import { DownloadButton } from '../../components/DownloadButton';
import { OperatingSystemList } from '../../components/OperatingSystemIcons';
import { useTranslation } from '../../services/i18n';
import type { AppDetails, BundleDetails, OperatingSystem } from '../../types/catalog';

export function BundleDetailPage() {
  const { account } = useAuth();
  const t = useTranslation();
  const { slug } = useParams();
  const [bundle, setBundle] = useState<BundleDetails | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expandedAppId, setExpandedAppId] = useState<string | null>(null);
  const [details, setDetails] = useState<AppDetails | null>(null);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [detailsError, setDetailsError] = useState(false);
  const [operatingSystem, setOperatingSystem] = useState<OperatingSystem | null>(null);
  const [sourcesBySystem, setSourcesBySystem] = useState<Partial<Record<OperatingSystem, Record<string, string>>>>({});
  const [selectionLocked, setSelectionLocked] = useState(false);

  useEffect(() => {
    if (!slug) return;
    let active = true;
    setBundle(null);
    setError(null);
    fetchBundle(slug)
      .then((result) => { if (active) setBundle(result); })
      .catch(() => { if (active) setError(t('bundle.loadError')); });
    return () => { active = false; };
  }, [slug, t, account?.id]);

  useEffect(() => {
    setExpandedAppId(null);
    setOperatingSystem(null);
    setSourcesBySystem({});
    setSelectionLocked(false);
  }, [slug]);

  useEffect(() => {
    if (!expandedAppId) return;
    const controller = new AbortController();
    setDetails(null);
    setDetailsError(false);
    setLoadingDetails(true);
    fetchAppDetails(expandedAppId, controller.signal)
      .then(setDetails)
      .catch(() => { if (!controller.signal.aborted) setDetailsError(true); })
      .finally(() => { if (!controller.signal.aborted) setLoadingDetails(false); });
    return () => controller.abort();
  }, [expandedAppId]);

  if (error) return <main className="content-page"><p className="error-banner">{error}</p></main>;
  if (!bundle) return <main className="content-page"><p className="loading-label">{t('bundle.loading')}</p></main>;

  const availableSystems = bundle.platformAvailability.length
    ? bundle.platformAvailability.map((item) => item.operatingSystem)
    : bundle.operatingSystems;
  const selectedSystem = operatingSystem && availableSystems.includes(operatingSystem)
    ? operatingSystem : availableSystems[0] ?? null;
  const selectedSources = selectedSystem ? sourcesBySystem[selectedSystem] ?? {} : {};

  function selectSource(appId: string, sourceRef: string | null) {
    if (!selectedSystem) return;
    setSourcesBySystem((current) => {
      const sources = { ...current[selectedSystem] };
      if (sourceRef) sources[appId] = sourceRef;
      else delete sources[appId];
      return { ...current, [selectedSystem]: sources };
    });
  }

  return (
    <main className="content-page">
      <section className="bundle-detail-header">
        <div>
          <h1>{bundle.name}</h1>
          <p>{bundle.description || t('bundle.fallbackDescription')}</p>
          <div className="tag-list">
            {bundle.tags.map((tag) => (
              <span className="tag-chip" key={tag}>
                {tag}
              </span>
            ))}
          </div>
        </div>
        <div className="bundle-detail-actions">
          <span>{t('bundle.appsCount', { count: bundle.appCount })}</span>
          <BundleActivity key={`${bundle.id}:${account?.id ?? "anonymous"}`} bundle={bundle} />
          <BundleDownloadButton
            key={bundle.id}
            bundleId={bundle.id}
            bundleName={bundle.name}
            appCount={bundle.appCount}
            operatingSystems={bundle.operatingSystems}
            platformAvailability={bundle.platformAvailability}
            selectedOperatingSystem={selectedSystem}
            onOperatingSystemChange={setOperatingSystem}
            onSelectionLockedChange={setSelectionLocked}
            sourceSelections={selectedSources}
          />
        </div>
      </section>
      <div className="bundle-app-list">
        {bundle.apps.map((app) => {
          const expanded = expandedAppId === app.id;
          const detailsId = `bundle-app-details-${app.id}`;
          return <div className={`bundle-app-entry ${expanded ? 'app-detail-row-open' : ''}`} key={app.id}>
            <div className="bundle-app-row">
              <AppMiniIcon app={app} />
              <div>
                <strong>
                  <button
                    className="bundle-app-details-toggle"
                    type="button"
                    aria-expanded={expanded}
                    aria-controls={detailsId}
                    onClick={() => setExpandedAppId(expanded ? null : app.id)}
                  >{app.name}</button>
                </strong>
                <small>{app.publisher || '-'}</small>
              </div>
              <OperatingSystemList operatingSystems={app.operatingSystems} />
              <DownloadButton appId={app.id} appName={app.name}
                sourceRef={selectedSources[app.id]} operatingSystem={selectedSystem ?? undefined}
                disabled={!isCatalogAppSelectable(app) || Boolean(selectedSystem && !app.operatingSystems.includes(selectedSystem))} />
              <button className="details-toggle" type="button" aria-expanded={expanded}
                aria-controls={detailsId}
                aria-label={t(expanded ? 'catalog.details.close' : 'catalog.details.open', { name: app.name })}
                onClick={() => setExpandedAppId(expanded ? null : app.id)}>
                <ChevronDown size={20} />
              </button>
            </div>
            <AppDetailExpander expanded={expanded} id={detailsId}>
              {detailsError ? <p className="error-banner">{t('catalog.error.detail')}</p> : (
                <AppDetailsPanel app={details?.id === app.id ? details : null}
                  loading={expanded && loadingDetails} operatingSystem={selectedSystem ?? undefined}
                  selectedSourceRef={selectedSources[app.id] ?? null}
                  onSourceSelect={(sourceRef) => selectSource(app.id, sourceRef)}
                  selectionDisabled={selectionLocked}
                  extraActions={selectedSources[app.id] ? <button className="secondary-button" type="button"
                    disabled={selectionLocked} onClick={() => selectSource(app.id, null)}>{t('bundle.resetInstaller')}</button> : null} />
              )}
            </AppDetailExpander>
          </div>;
        })}
      </div>
    </main>
  );
}
