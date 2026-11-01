import { AsyncContent } from '../../components/AsyncContent';
import { useAsyncResource } from '../../hooks/useAsyncResource';
import { useAuth } from '../../auth/AuthContext';
import {
  FolderPlus
} from 'lucide-react';
import {
  Link
} from 'react-router-dom';
import {
  fetchOwnBundles
} from '../../api/account';
import { useTranslation } from '../../services/i18n';

import { apiMessage } from './accountSupport';
import { BundleList } from './AccountBundleList';

export function AccountBundlesPage() {
  const t = useTranslation();
  const { account } = useAuth();
  const resource = useAsyncResource(() => fetchOwnBundles(), { scope: account?.id });
  return <section className="account-page"><header><div><h2>{t('account.bundles.title')}</h2><p>{t('account.bundles.subtitle')}</p></div>
    <Link className="primary-button" to="/dashboard/bundles/new"><FolderPlus size={18} />{t('account.bundles.new')}</Link></header>
    <AsyncContent {...resource} hasData={resource.data !== undefined} onRetry={resource.refresh}
      errorMessage={resource.error ? apiMessage(t, resource.error, 'account.bundles.failed') : undefined}>
      <BundleList items={resource.data?.data ?? []} />
    </AsyncContent>
  </section>;
}
