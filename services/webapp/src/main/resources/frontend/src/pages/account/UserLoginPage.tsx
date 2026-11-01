import {
  type FormEvent,
  useEffect, useState
} from 'react';
import {
  Link, useLocation,
  useNavigate
} from 'react-router-dom';
import {
  confirmMagicLink, requestMagicLink
} from '../../api/account';
import { useAuth } from '../../auth/AuthContext';
import { useLocale, useTranslation } from '../../services/i18n';

import { apiMessage, emailIssue, AuthCard, destinationFrom, MAXIMUM_EMAIL_LENGTH } from './accountSupport';

export function UserLoginPage() {
  const t = useTranslation();
  const locale = useLocale();
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [token] = useState(() => new URLSearchParams(location.hash.slice(1)).get('token'));
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [emailInvalid, setEmailInvalid] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const destination = destinationFrom(location.state, '/dashboard');

  useEffect(() => {
    if (!location.hash) return;
    void navigate('/login', { replace: true, state: location.state });
  }, [location.hash, location.state, navigate]);

  async function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const account = await confirmMagicLink(token);
      auth.setAuthenticated(account);
      await navigate(destination, { replace: true });
    } catch (cause) {
      setError(apiMessage(t, cause, 'account.magic.failed'));
    } finally { setSubmitting(false); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    const validationError = emailIssue(t, email);
    setEmailInvalid(Boolean(validationError));
    if (validationError) {
      setError(validationError);
      return;
    }
    setSubmitting(true);
    setError(null);
    setMessage(null);
    try {
      await requestMagicLink(email, locale);
      setMessage(t('account.magic.sent'));
    } catch (cause) {
      setError(apiMessage(t, cause, 'account.magic.failed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard>
      <h2>{t('account.magic.title')}</h2>
      {token ? <form className="auth-form" onSubmit={confirm}>
        <p>{t('account.magic.confirmNotice')}</p>
        {error ? <p className="error-banner" role="alert">{error}</p> : null}
        <button className="primary-button" type="submit" disabled={submitting}>
          {submitting ? t('account.sending') : t('account.magic.confirm')}
        </button>
      </form> : <form className="auth-form" onSubmit={submit} noValidate>
        <label>{t('account.email')}
          <input type="email" maxLength={MAXIMUM_EMAIL_LENGTH} value={email}
            onChange={(event) => { setEmail(event.target.value); setError(null); setEmailInvalid(false); }}
            autoComplete="email" required aria-invalid={emailInvalid || undefined}
            aria-describedby={error && !token ? 'login-email-error' : undefined} />
        </label>
        {error ? <p id="login-email-error" className="error-banner" role="alert">{error}</p> : null}
        {message ? <p className="form-message"><output>{message}</output></p> : null}
        <div className="auth-actions">
          <button className="primary-button" type="submit" disabled={submitting}>{submitting ? t('account.sending') : t('account.magic.submit')}</button>
          <Link className="auth-switch-link" to="/admin/login" aria-label={t('account.adminLogin.link')} title={t('account.adminLogin.link')}>
            <span className="material-symbols-outlined" aria-hidden="true">admin_panel_settings</span>
          </Link>
        </div>
      </form>}
      <p className="auth-legal-notice">{t('account.magic.privacyNotice')} <Link to="/privacy">{t('account.magic.privacyLink')}</Link> · <Link to="/terms">{t('account.magic.termsLink')}</Link></p>
    </AuthCard>
  );
}
