import { Download, ShieldCheck, Star } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
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
  const [signInOpen, setSignInOpen] = useState(false);
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
      <button type="button" className={`bundle-star-button ${state.starred ? 'bundle-star-active' : ''}`}
        aria-pressed={Boolean(state.starred)} disabled={pending || status === 'checking'} aria-busy={pending}
        aria-label={t(state.starred ? 'bundle.star.remove' : 'bundle.star.add', { name: bundle.name })}
        onClick={() => { if (account) void toggleStar(); else setSignInOpen(true); }}>
        <Star size={18} aria-hidden="true" fill={state.starred ? 'currentColor' : 'none'} />
        <span aria-live="polite">{state.starCount.toLocaleString(locale)}</span>
      </button>
      <span className="bundle-download-count" aria-label={t('bundle.downloads', { count: state.downloadCount ?? 0 })}>
        <Download size={16} aria-hidden="true" />{(state.downloadCount ?? 0).toLocaleString(locale)}
      </span>
    </div>
    {state.hasAdminStar ? <span className="bundle-admin-star"><ShieldCheck size={16} aria-hidden="true" />{t('bundle.star.admin')}</span> : null}
    {signInOpen ? <StarSignInDialog onClose={() => setSignInOpen(false)} /> : null}
    {error ? <small className="error-banner" role="alert">{t('bundle.star.error')}</small> : null}
  </div>;
}

function StarSignInDialog({ onClose }: { onClose: () => void }) {
  const t = useTranslation();
  const location = useLocation();
  const id = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    const trigger = document.activeElement;
    dialog?.showModal();
    return () => {
      dialog?.close();
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus({ preventScroll: true });
    };
  }, []);

  return <dialog ref={dialogRef} className="star-signin-dialog" aria-labelledby={`${id}-title`}
    aria-describedby={`${id}-description`} onCancel={(event) => { event.preventDefault(); onClose(); }}>
    <h3 id={`${id}-title`}>{t('bundle.star.signIn')}</h3>
    <p id={`${id}-description`}>{t('bundle.star.signInDescription')}</p>
    <div className="star-signin-actions">
      <button type="button" className="secondary-button" onClick={onClose}>{t('common.cancel')}</button>
      <Link className="primary-button" to="/login" state={{ from: location }}>{t('bundle.star.signInAction')}</Link>
    </div>
  </dialog>;
}
