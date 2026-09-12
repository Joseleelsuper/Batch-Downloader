import {
  CheckCircle2,
  Loader2,
  Wand2
} from 'lucide-react';
import { useTranslation } from '../../services/i18n';
import {
  DiscoveryStatus,
  WebsiteDiscoveryFeedback,
  WebsiteInstallerEvidence
} from './AdminAppsSupport';
import { InstallerUrlFields } from './InstallerUrlFields';
import { useAdminAppsActivity } from './useAdminAppsActivity';

import { useAdminAppEditor } from './useAdminAppEditor';
import { useWebsiteDiscovery } from './useWebsiteDiscovery';
/** Permite descubrir una aplicación desde su sitio oficial y revisar sus instaladores. */
export function WebsiteDiscoveryPanel({ editor, activity, discoveryActions }: { editor: ReturnType<typeof useAdminAppEditor>; activity: ReturnType<typeof useAdminAppsActivity>; discoveryActions: ReturnType<typeof useWebsiteDiscovery>; }) {
  const t = useTranslation();
  const { websiteDiscovery, websiteUrl, websiteInstallerUrls, setEditor } = editor;
  const { discoveringWebsite } = activity;
  const { startWebsiteDiscovery, discoverWebsiteOnEnter } = discoveryActions;
  const pending = discoveringWebsite || websiteDiscovery?.status === 'queued' || websiteDiscovery?.status === 'running';
  return (
    <section
      className="manual-installer-panel website-discovery-panel"
      aria-labelledby="website-discovery-title"
      aria-busy={
        pending
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
              onChange={(event) => setEditor('websiteUrl', event.target.value)}
              onKeyDown={discoverWebsiteOnEnter}
              placeholder="https://example.com"
              autoComplete="url"
              maxLength={2048}
              aria-describedby="website-official-url-help"
              disabled={
                pending
              }
            />
            <button
              type="button"
              className="primary-button"
              onClick={() => void startWebsiteDiscovery()}
              disabled={
                pending
              }
            >
              {pending
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
          <InstallerUrlFields mode="website" values={websiteInstallerUrls}
            onChange={(value) => setEditor('websiteInstallerUrls', value)} disabled={pending} />
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
  );
}
