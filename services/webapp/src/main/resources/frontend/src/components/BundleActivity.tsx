import { Download, ShieldCheck, Star } from 'lucide-react';
import { useState } from 'react';
import { setBundleStar } from '../api/bundles';
import { useAuth } from '../auth/AuthContext';
import { useLocale, useTranslation } from '../services/i18n';
import type { BundleSummary, BundleStarState } from '../types/catalog';

type ActivityBundle = Pick<BundleSummary, 'id' | 'name' | 'visibility' | 'starCount' | 'starred' | 'hasAdminStar' | 'downloadCount'>;

/** Un voto por cuenta: el servidor confirma el estado antes de cambiarlo en pantalla. */
export function BundleActivity({ bundle, onChange }: { bundle: ActivityBundle; onChange?: () => void }) {
  const { account, status } = useAuth();
  const t = useTranslation();
  const locale = useLocale();
  const [result, setResult] = useState<{ baseline: ActivityBundle; state: BundleStarState } | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(false);
  const state = result?.baseline === bundle ? result.state : bundle;
  if (!['public', 'official'].includes(bundle.visibility)) return null;

  async function toggleStar() {
    if (pending || !account) return;
    setPending(true);
    setError(false);
    try {
      const next = await setBundleStar(bundle.id, !state.starred);
      setResult({ baseline: bundle, state: next });
      onChange?.();
    } catch { setError(true); }
    finally { setPending(false); }
  }

  return <div className="bundle-activity">
    <div className="bundle-metrics">
      {account ? <button type="button" className={`bundle-star-button ${state.starred ? 'bundle-star-active' : ''}`}
        aria-pressed={Boolean(state.starred)} disabled={pending || status === 'checking'} aria-busy={pending}
        aria-label={t(state.starred ? 'bundle.star.remove' : 'bundle.star.add', { name: bundle.name })}
        onClick={() => { void toggleStar(); }}>
        <Star size={18} aria-hidden="true" fill={state.starred ? 'currentColor' : 'none'} />
        <span aria-live="polite">{state.starCount.toLocaleString(locale)}</span>
      </button> : <span className="bundle-star-count" aria-label={t('bundle.star.count', { count: state.starCount })}>
        <Star size={18} aria-hidden="true" />{state.starCount.toLocaleString(locale)}
      </span>}
      <span className="bundle-download-count" aria-label={t('bundle.downloads', { count: state.downloadCount ?? 0 })}>
        <Download size={16} aria-hidden="true" />{(state.downloadCount ?? 0).toLocaleString(locale)}
      </span>
    </div>
    {state.hasAdminStar ? <span className="bundle-admin-star"><ShieldCheck size={16} aria-hidden="true" />{t('bundle.star.admin')}</span> : null}
    {!account && status !== 'checking' ? <small className="bundle-star-hint">{t('bundle.star.signIn')}</small> : null}
    {error ? <small className="error-banner" role="alert">{t('bundle.star.error')}</small> : null}
  </div>;
}
