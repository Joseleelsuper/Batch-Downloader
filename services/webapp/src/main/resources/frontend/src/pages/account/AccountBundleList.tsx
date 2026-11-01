import {
  Boxes
} from 'lucide-react';
import {
  Link
} from 'react-router-dom';
import { useTranslation } from '../../services/i18n';
import type { OwnBundleSummary } from '../../types/account';

export function BundleList({ items }: { items: OwnBundleSummary[] }) {
  const t = useTranslation();
  if (!items.length) return <p className="empty-state">{t('account.bundles.empty')}</p>;
  return <ul className="account-list">{items.map((bundle) => <li key={bundle.id}>
    <Boxes size={20} /><span><Link to={`/dashboard/bundles/${bundle.id}/edit`}>{bundle.name}</Link><small>{bundle.appCount} · {bundle.visibility}</small></span>
  </li>)}</ul>;
}
