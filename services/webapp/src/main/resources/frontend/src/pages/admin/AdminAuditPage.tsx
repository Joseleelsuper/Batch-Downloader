import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { fetchAdminAudit } from '../../api/adminMeta';
import { AdminTable } from '../../components/admin/AdminTable';
import { useTranslation } from '../../services/i18n';
import { formatDate } from '../../utils/date';

export function AdminAuditPage() {
  const t = useTranslation();
  const resource = useAsyncResource(() => fetchAdminAudit());
  const items = resource.data ?? [];
  return (
    <section className="admin-panel">
      <h2>{t('admin.audit.title')}</h2>
      <AsyncContent {...resource} hasData={resource.data !== undefined} onRetry={resource.refresh}>
      <AdminTable title={t('admin.audit.recentActions')} rows={items.map((item) => [item.actor, item.action, item.targetId || '-', formatDate(item.createdAt)])} />
      </AsyncContent>
    </section>
  );
}
