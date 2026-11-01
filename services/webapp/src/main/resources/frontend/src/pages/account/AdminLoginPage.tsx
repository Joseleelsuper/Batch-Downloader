import {
  type FormEvent, useState
} from 'react';
import {
  Link, useLocation,
  useNavigate
} from 'react-router-dom';
import {
  adminLogin
} from '../../api/account';
import { useAuth } from '../../auth/AuthContext';
import { useTranslation } from '../../services/i18n';

import { apiMessage, loginPasswordIssue, AuthCard, destinationFrom } from './accountSupport';

export function AdminLoginPage() {
  const t = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const destination = destinationFrom(location.state, '/admin');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    const validationError = loginPasswordIssue(t, password);
    if (validationError) {
      setError(validationError);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const account = await adminLogin(username, password);
      auth.setAuthenticated(account);
      await navigate(destination, { replace: true });
    } catch (cause) {
      setError(apiMessage(t, cause, 'login.invalid'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard>
      <h2>{t('account.adminLogin.title')}</h2>
      <form className="auth-form" onSubmit={submit} noValidate>
        <label>{t('login.username')}
          <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required />
        </label>
        <label>{t('login.password')}
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required />
        </label>
        {error ? <p className="error-banner">{error}</p> : null}
        <div className="auth-actions">
          <button className="primary-button" type="submit" disabled={submitting}>{submitting ? t('account.sending') : t('login.submit')}</button>
          <Link className="auth-switch-link" to="/login" aria-label={t('account.userLogin.link')} title={t('account.userLogin.link')}>
            <span className="material-symbols-outlined" aria-hidden="true">person</span>
          </Link>
        </div>
      </form>
    </AuthCard>
  );
}
