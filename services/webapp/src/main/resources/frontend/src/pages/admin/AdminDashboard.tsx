import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { fetchCatalogStats } from '../../api/catalogApps';
import { fetchAdminCurrentRun } from '../../api/scraperAdmin';
import { useLocale, useTranslation } from '../../services/i18n';

export function AdminDashboard() {
  const t = useTranslation();
  const statsResource = useAsyncResource((signal) => fetchCatalogStats(signal));
  const currentResource = useAsyncResource(() => fetchAdminCurrentRun());
  const stats = statsResource.data;
  const current = currentResource.data;

  return (
    <section className="admin-panel">
      <h2>{t('admin.dashboard.title')}</h2>
      <AsyncContent {...statsResource} hasData={stats !== undefined} onRetry={statsResource.refresh}>
      <div className="metric-grid">
        <Metric label={t('admin.layout.apps')} value={stats?.total ?? 0} />
        <Metric label={t('catalog.filter.available')} value={stats?.filters.available ?? 0} />
        <Metric label={t('catalog.filter.review')} value={stats?.filters.review ?? 0} />
        <Metric label={t('catalog.filter.missing')} value={stats?.filters.missing ?? 0} />
      </div>
      </AsyncContent>
      <div className="admin-card">
        <h3>{t('admin.dashboard.currentScraper')}</h3>
        <AsyncContent {...currentResource} hasData={current !== undefined} onRetry={currentResource.refresh}>
        <p>{current?.currentAppName || current?.status || t('admin.dashboard.noCurrentRun')}</p>
        <small>{current?.currentPhase || '-'}</small>
        </AsyncContent>
      </div>
    </section>
  );
}
function Metric({ label, value }: { label: string; value: number }) {
  const locale = useLocale();
  return (
    <div className="metric-card">
      <span>{label}</span>
      <strong>{value.toLocaleString(locale)}</strong>
    </div>
  );
}
