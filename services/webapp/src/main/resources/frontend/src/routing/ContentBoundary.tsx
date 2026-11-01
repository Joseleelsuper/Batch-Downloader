import { Component, Suspense, type ReactNode } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { LoadingPlaceholder } from '../components/AsyncContent';
import { useTranslation } from '../services/i18n';

class CodeErrorBoundary extends Component<{ children: ReactNode; fallback: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() { return this.state.failed ? this.props.fallback : this.props.children; }
}

export function ContentBoundary({ children }: Readonly<{ children: ReactNode }>) {
  const t = useTranslation();
  return <CodeErrorBoundary fallback={<div className="error-banner" role="alert">
    <p>{t('common.loadError')}</p>
    <button className="secondary-button" type="button" onClick={() => window.location.reload()}>{t('common.reload')}</button>
  </div>}>
    <Suspense fallback={<LoadingPlaceholder />}>{children}</Suspense>
  </CodeErrorBoundary>;
}

/** El layout permanece montado mientras cambia o se descarga su pantalla. */
export function RouteContent() {
  const { pathname } = useLocation();
  const { account } = useAuth();
  const privatePage = /^\/(?:dashboard|profile|admin)(?:\/|$)/.test(pathname) && pathname !== '/admin/login';
  const page = pathname.startsWith('/catalog/app/') ? '/catalog' : pathname;
  return <ContentBoundary key={`${page}:${privatePage ? account?.id ?? '' : ''}`}><Outlet /></ContentBoundary>;
}
