import {
  Boxes,
  Clock3, LogOut, PackagePlus, UserRound
} from 'lucide-react';
import {
  useState
} from 'react';
import {
  Link,
  NavLink, useNavigate
} from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { MobileAppHeader, ResponsivePanel } from '../../components/ResponsivePanel';
import { useTranslation } from '../../services/i18n';

import { RouteContent } from '../../routing/ContentBoundary';

export function AccountLayout() {
  const t = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  async function signOut() {
    if (await auth.signOut()) void navigate('/login', { replace: true });
  }
  return <div className="account-shell">
    <MobileAppHeader controls="account-navigation" open={menuOpen} onClick={() => setMenuOpen(true)} />
    <ResponsivePanel id="account-navigation" title={t('account.nav.dashboard')} open={menuOpen}
      onClose={() => setMenuOpen(false)} desktopClassName="sidebar-shell">
    <aside className="account-nav admin-sidebar">
      <Link className="brand admin-brand" to="/">
        <img className="brand-icon" src="/assets/icon.ico" alt="" aria-hidden="true" />
        <span className="brand-title">{t('app.title')}</span>
      </Link>
      <nav>
        <NavLink to="/dashboard" end><Clock3 size={18} />{t('account.nav.dashboard')}</NavLink>
        <NavLink to="/dashboard/bundles"><Boxes size={18} />{t('account.nav.bundles')}</NavLink>
        <NavLink to="/dashboard/software-requests"><PackagePlus size={18} />{t('software.requests.title')}</NavLink>
        <NavLink to="/profile"><UserRound size={18} />{t('account.nav.profile')}</NavLink>
      </nav>
      <button type="button" onClick={signOut}><LogOut size={18} />{t('nav.logout')}</button>
    </aside>
    </ResponsivePanel>
    <main className="account-content"><RouteContent /></main>
  </div>;
}
