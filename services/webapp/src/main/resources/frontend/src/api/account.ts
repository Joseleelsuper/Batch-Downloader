import { requestJson } from './catalog';
import type { AuthUser } from '../types/catalog';
import type {
  AccountDashboard,
  DownloadHistoryPage,
  OwnBundleDetails,
  OwnBundleInput,
  OwnBundlePage,
} from '../types/account';

export function registerAccount(email: string, password: string): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function confirmEmail(token: string): Promise<void> {
  await requestJson<void>('/api/v1/auth/email-verification/confirm', {
    method: 'POST', body: JSON.stringify({ token }),
  });
}

export async function resendVerification(email: string): Promise<void> {
  await requestJson<void>('/api/v1/auth/email-verification/resend', {
    method: 'POST', body: JSON.stringify({ email }),
  });
}

export async function requestPasswordReset(email: string): Promise<void> {
  await requestJson<void>('/api/v1/auth/password-reset/request', {
    method: 'POST', body: JSON.stringify({ email }),
  });
}

export async function resetPassword(token: string, password: string): Promise<void> {
  await requestJson<void>('/api/v1/auth/password-reset/confirm', {
    method: 'POST', body: JSON.stringify({ token, password }),
  });
}

export function fetchProfile(): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/v1/users/me');
}

export function updateProfile(username: string): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/v1/users/me', {
    method: 'PATCH', body: JSON.stringify({ username }),
  });
}

export function fetchDashboard(): Promise<AccountDashboard> {
  return requestJson<AccountDashboard>('/api/v1/users/me/dashboard');
}

export function fetchDownloads(page = 1, pageSize = 20): Promise<DownloadHistoryPage> {
  return requestJson<DownloadHistoryPage>(
    `/api/v1/users/me/downloads?page=${page}&pageSize=${pageSize}`,
  );
}

export function fetchOwnBundles(page = 1, pageSize = 20): Promise<OwnBundlePage> {
  return requestJson<OwnBundlePage>(
    `/api/v1/users/me/bundles?page=${page}&pageSize=${pageSize}`,
  );
}

export function fetchOwnBundle(id: string): Promise<OwnBundleDetails> {
  return requestJson<OwnBundleDetails>(`/api/v1/users/me/bundles/${encodeURIComponent(id)}`);
}

export function createOwnBundle(input: OwnBundleInput): Promise<OwnBundleDetails> {
  return requestJson<OwnBundleDetails>('/api/v1/users/me/bundles', {
    method: 'POST', body: JSON.stringify(input),
  });
}

export function updateOwnBundle(
  id: string,
  input: OwnBundleInput & { visibility: 'private' | 'public'; expectedVersion: number },
): Promise<OwnBundleDetails> {
  return requestJson<OwnBundleDetails>(`/api/v1/users/me/bundles/${encodeURIComponent(id)}`, {
    method: 'PATCH', body: JSON.stringify(input),
  });
}

export async function deleteOwnBundle(id: string): Promise<void> {
  await requestJson<void>(`/api/v1/users/me/bundles/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
