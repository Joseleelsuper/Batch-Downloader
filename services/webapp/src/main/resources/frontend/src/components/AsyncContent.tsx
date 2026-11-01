import type { ReactNode } from 'react';
import { useTranslation } from '../services/i18n';

export function LoadingPlaceholder({ className = '' }: Readonly<{ className?: string }>) {
  const t = useTranslation();
  return <div className={`loading-placeholder ${className}`} role="status">
    <span className="sr-only">{t('common.loading')}</span>
  </div>;
}

export function AsyncContent({ loading, error, hasData, onRetry, children, errorMessage, placeholder }: Readonly<{
  loading: boolean;
  error?: unknown;
  hasData: boolean;
  onRetry: () => void;
  children: ReactNode;
  errorMessage?: string;
  placeholder?: ReactNode;
}>) {
  const t = useTranslation();
  if (!hasData && !loading && !error) return null;
  return <div className="async-content" aria-busy={loading}>
    {error ? <div className="error-banner" role="alert">
      <p>{errorMessage ?? t('common.loadError')}</p>
      <button className="secondary-button compact-button" type="button" onClick={onRetry}>{t('common.retry')}</button>
    </div> : null}
    {hasData ? <>{children}{loading ? <span className="sr-only" role="status">{t('common.loading')}</span> : null}</>
      : loading ? placeholder ?? <LoadingPlaceholder /> : null}
  </div>;
}
