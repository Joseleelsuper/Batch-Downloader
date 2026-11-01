import {
  Save,
  Trash2,
  UserRound
} from 'lucide-react';
import {
  type FormEvent,
  useEffect,
  useRef,
  useState,
} from 'react';
import {
  useNavigate
} from 'react-router-dom';
import {
  deleteOwnAccount, deleteOwnBundles, updateProfile
} from '../../api/account';
import { useAuth } from '../../auth/AuthContext';
import { useTranslation } from '../../services/i18n';

import { apiMessage } from './accountSupport';

type ProfileActionDialog =
  | { type: 'delete-bundles'; visibility: 'public' | 'private' }
  | { type: 'delete-account' }
  | { type: 'account-deletion-requested' };

export function ProfilePage() {
  const t = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState(auth.account?.username ?? '');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dangerMessage, setDangerMessage] = useState<string | null>(null);
  const [dangerError, setDangerError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [actionDialog, setActionDialog] = useState<ProfileActionDialog | null>(null);
  const actionDialogRef = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = actionDialogRef.current;
    if (!dialog) return;
    if (actionDialog && !dialog.open) dialog.showModal();
    else if (!actionDialog && dialog.open) dialog.close();
  }, [actionDialog]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true); setMessage(null); setError(null);
    try {
      const changed = await updateProfile(username);
      auth.setAuthenticated(changed);
      setMessage(t('account.profile.saved'));
    } catch (cause) { setError(apiMessage(t, cause, 'account.profile.failed')); }
    finally { setSubmitting(false); }
  }
  function requestBundleDeletion(visibility: 'public' | 'private') {
    if (submitting) return;
    setDangerMessage(null);
    setDangerError(null);
    setActionDialog({ type: 'delete-bundles', visibility });
  }

  function requestAccountDeletion() {
    if (submitting) return;
    setDangerMessage(null);
    setDangerError(null);
    setActionDialog({ type: 'delete-account' });
  }

  async function confirmActionDialog() {
    if (submitting || !actionDialog || actionDialog.type === 'account-deletion-requested') return;
    setSubmitting(true); setDangerMessage(null); setDangerError(null);
    try {
      if (actionDialog.type === 'delete-bundles') {
        const { visibility } = actionDialog;
        const result = await deleteOwnBundles(visibility);
        setDangerMessage(t(result.deleted === 0
          ? `account.profile.${visibility}BundlesEmpty`
          : `account.profile.${visibility}BundlesDeleted`));
        setActionDialog(null);
      } else {
        await deleteOwnAccount();
        setActionDialog({ type: 'account-deletion-requested' });
      }
    } catch (cause) {
      const fallback = actionDialog.type === 'delete-account'
        ? 'account.profile.deleteAccountFailed'
        : 'account.profile.deleteBundlesFailed';
      setDangerError(apiMessage(t, cause, fallback));
      setActionDialog(null);
    } finally { setSubmitting(false); }
  }

  async function finishAccountDeletion() {
    if (submitting) return;
    setSubmitting(true);
    if (await auth.signOut()) await navigate('/login', { replace: true });
    else setSubmitting(false);
  }

  const dialogTitle = actionDialog?.type === 'account-deletion-requested'
    ? t('account.profile.accountDeletionRequestedTitle')
    : t('account.profile.confirmTitle');
  const dialogDescription = actionDialog?.type === 'account-deletion-requested'
    ? t('account.profile.accountDeletionRequested')
    : actionDialog?.type === 'delete-account'
      ? t('account.profile.deleteAccountConfirm')
      : actionDialog?.type === 'delete-bundles'
        ? t(`account.profile.${actionDialog.visibility}BundlesConfirm`)
        : '';

  return <section className="account-page"><header><div><h2>{t('account.profile.title')}</h2><p>{auth.account?.email}</p></div></header>
    <form className="account-card profile-form" onSubmit={submit}><label>{t('login.username')}<input value={username} onChange={(event) => setUsername(event.target.value)} minLength={3} maxLength={40} autoComplete="username" required /></label>
      {error ? <p className="error-banner">{error}</p> : null}{message ? <p className="form-message">{message}</p> : null}
      <button className="primary-button" type="submit" disabled={submitting}><Save size={18} />{t('account.save')}</button></form>
    <section className="account-card profile-danger-zone"><div><h3>{t('account.profile.dangerTitle')}</h3><p>{t('account.profile.dangerDescription')}</p></div>
      {dangerError ? <p className="error-banner">{dangerError}</p> : null}{dangerMessage ? <p className="form-message">{dangerMessage}</p> : null}
      <div className="profile-danger-actions">
        <button className="danger-button" type="button" onClick={() => requestBundleDeletion('public')} disabled={submitting}><Trash2 size={18} />{t('account.profile.deletePublicBundles')}</button>
        <button className="danger-button" type="button" onClick={() => requestBundleDeletion('private')} disabled={submitting}><Trash2 size={18} />{t('account.profile.deletePrivateBundles')}</button>
        <button className="danger-button" type="button" onClick={requestAccountDeletion} disabled={submitting}><UserRound size={18} />{t('account.profile.deleteAccount')}</button>
      </div>
    </section>
    <dialog
      ref={actionDialogRef}
      className="profile-action-dialog"
      aria-labelledby="profile-action-dialog-title"
      aria-describedby="profile-action-dialog-description"
      onCancel={(event) => {
        event.preventDefault();
        if (!submitting && actionDialog?.type !== 'account-deletion-requested') setActionDialog(null);
      }}
    >
      {actionDialog ? <div className="profile-action-dialog-content">
        <h3 id="profile-action-dialog-title">{dialogTitle}</h3>
        <p id="profile-action-dialog-description">{dialogDescription}</p>
        {actionDialog.type === 'account-deletion-requested'
          ? <div className="profile-action-dialog-actions">
            <button className="primary-button" type="button" onClick={() => void finishAccountDeletion()} disabled={submitting}>
              {t('account.profile.continueToLogin')}
            </button>
          </div>
          : <div className="profile-action-dialog-actions">
            <button className="secondary-button" type="button" onClick={() => setActionDialog(null)} disabled={submitting}>
              {t('common.cancel')}
            </button>
            <button className="danger-button" type="button" onClick={() => void confirmActionDialog()} disabled={submitting}>
              <Trash2 size={18} />{t('account.profile.confirmDelete')}
            </button>
          </div>}
      </div> : null}
    </dialog>
  </section>;
}
