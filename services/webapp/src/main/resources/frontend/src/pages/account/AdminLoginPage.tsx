import { useActionState, useState } from 'react';
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
  const destination = destinationFrom(location.state, '/admin');
  const [error, submit, submitting] = useActionState(async (_error: string | null, fields: FormData) => {
    const submittedPassword = String(fields.get('password') ?? '');
    const validationError = loginPasswordIssue(t, submittedPassword);
    if (validationError) return validationError;
    try {
      const account = await adminLogin(String(fields.get('username') ?? ''), submittedPassword);
      auth.setAuthenticated(account);
      await navigate(destination, { replace: true });
      return null;
    } catch (cause) {
      return apiMessage(t, cause, 'login.invalid');
    }
  }, null);

  return (
    <AuthCard>
      <h2>{t('account.adminLogin.title')}</h2>
      <form className="auth-form" action={submit} noValidate>
        <label>{t('login.username')}
          <input name="username" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required />
        </label>
        <label>{t('login.password')}
          <input name="password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required />
        </label>
        {!submitting && error ? <p className="error-banner">{error}</p> : null}
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
