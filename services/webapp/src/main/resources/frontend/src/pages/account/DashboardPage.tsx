import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { useAuth } from '../../auth/AuthContext';
import {
  Boxes, Download,
  ExternalLink, LockKeyhole
} from 'lucide-react';
import {
  fetchDashboard
} from '../../api/account';
import { useLocale, useTranslation } from '../../services/i18n';
import type { AccountDashboard } from '../../types/account';

import { apiMessage } from './accountSupport';
import { BundleList } from './AccountBundleList';

export function DashboardPage() {
  const t = useTranslation();
  const { account } = useAuth();
  const resource = useAsyncResource(() => fetchDashboard(), { scope: account?.id });
  const dashboard = resource.data;
  return <section className="account-page">
    <header><div><h2>{t('account.dashboard.title', { username: dashboard?.account.username ?? account?.username ?? '' })}</h2><p>{t('account.dashboard.subtitle')}</p></div></header>
    <AsyncContent {...resource} hasData={dashboard !== undefined} onRetry={resource.refresh}
      errorMessage={resource.error ? apiMessage(t, resource.error, 'account.dashboard.failed') : undefined}>
    {dashboard ? <><div className="account-stats">
      <Stat label={t('account.dashboard.bundles')} value={dashboard.counts.bundles} icon={<Boxes />} />
      <Stat label={t('account.dashboard.public')} value={dashboard.counts.publicBundles} icon={<ExternalLink />} />
      <Stat label={t('account.dashboard.private')} value={dashboard.counts.privateBundles} icon={<LockKeyhole />} />
      <Stat label={t('account.dashboard.downloads')} value={dashboard.counts.downloads} icon={<Download />} />
    </div>
    <div className="account-grid">
      <section className="account-card"><h3>{t('account.dashboard.recentDownloads')}</h3><HistoryList items={dashboard.recentDownloads} /></section>
      <section className="account-card"><h3>{t('account.dashboard.recentBundles')}</h3><BundleList items={dashboard.recentBundles} /></section>
    </div>
    </> : null}
    </AsyncContent>
  </section>;
}

function Stat({ label, value, icon }: { label: string; value: number; icon: React.ReactNode }) {
  return <article className="account-stat"><span>{icon}</span><strong>{value}</strong><small>{label}</small></article>;
}

function HistoryList({ items }: { items: AccountDashboard['recentDownloads'] }) {
  const t = useTranslation();
  const locale = useLocale();
  if (!items.length) return <p className="empty-state">{t('account.downloads.empty')}</p>;
  return <ul className="account-list">{items.map((item) => <li key={`${item.jobId}-${item.appId}`}>
    {item.iconUrl ? <img src={item.iconUrl} alt="" loading="lazy" decoding="async" /> : <Download size={20} />}
    <span><strong>{item.appName}</strong><small>{new Date(item.downloadedAt).toLocaleString(locale)}</small></span>
  </li>)}</ul>;
}
