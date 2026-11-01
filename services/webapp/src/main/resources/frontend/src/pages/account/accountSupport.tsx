import { ApiRequestError } from '../../api/http';
import { type Translator } from '../../services/i18n';

export const MAXIMUM_EMAIL_LENGTH = 254;
const MAXIMUM_PASSWORD_UTF8_BYTES = 72;

export function apiMessage(t: Translator, error: unknown, fallbackKey: string): string {
  if (error instanceof ApiRequestError) {
    const key = `account.error.${error.code}`;
    const translated = t(key);
    if (translated !== key) return translated;
  }
  return t(fallbackKey);
}

export function emailIssue(t: Translator, email: string): string | null {
  if (!email.trim() || !/^[^\s@]+@[^\s@]+$/.test(email)) return t('account.email.invalid');
  if (email.length > MAXIMUM_EMAIL_LENGTH) return t('account.email.tooLong');
  return null;
}

function passwordBytes(password: string): number {
  return new TextEncoder().encode(password).length;
}

export function loginPasswordIssue(t: Translator, password: string): string | null {
  if (!password) return t('account.password.required');
  if (passwordBytes(password) > MAXIMUM_PASSWORD_UTF8_BYTES) return t('account.password.tooLong');
  return null;
}

export function AuthCard({ children }: Readonly<{ children: React.ReactNode }>) {
  return <main className="login-page"><section className="login-card auth-card">{children}</section></main>;
}

export function destinationFrom(state: unknown, fallback: string): string {
  if (!state || typeof state !== 'object') return fallback;
  const from = (state as { from?: unknown }).from;
  if (!from || typeof from !== 'object') return fallback;
  const pathname = (from as { pathname?: unknown }).pathname;
  const search = (from as { search?: unknown }).search;
  if (typeof pathname !== 'string' || !pathname.startsWith('/') || pathname.startsWith('//')) return fallback;
  return pathname + (typeof search === 'string' ? search : '');
}
