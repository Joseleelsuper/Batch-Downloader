import { requestJson } from './http';
import type { AuthUser } from '../types/catalog';
import type {
  AccountDashboard,
  DownloadHistoryPage,
  OwnBundleDetails,
  OwnBundleInput,
  OwnBundlePage,
  OwnBundlesDeletionResult,
} from '../types/account';

export async function requestMagicLink(email: string, locale = 'es'): Promise<void> {
  await requestJson<void>('/api/v1/auth/magic-link/request', {
    method: 'POST', body: JSON.stringify({ email, locale }),
  });
}

export function confirmMagicLink(token: string): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/v1/auth/magic-link/confirm', {
    method: 'POST', body: JSON.stringify({ token }),
  });
}

export function adminLogin(username: string, password: string): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/v1/admin/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password }),
  });
}

export async function logout(): Promise<void> {
  await requestJson<void>('/api/v1/auth/logout', { method: 'POST' });
}

export async function adminLogout(): Promise<void> {
  await requestJson<void>('/api/v1/admin/auth/logout', { method: 'POST' });
}

export async function me(): Promise<AuthUser | null> {
  const identity = await requestJson<AuthUser | undefined>('/api/v1/auth/me');
  return identity ?? null;
}

export function fetchProfile(): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/v1/users/me');
}

export function updateProfile(username: string): Promise<AuthUser> {
  return requestJson<AuthUser>('/api/v1/users/me', {
    method: 'PATCH', body: JSON.stringify({ username }),
  });
}

export async function deleteOwnAccount(): Promise<void> {
  await requestJson<void>('/api/v1/users/me', { method: 'DELETE' });
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

export function fetchBundleCustomTags(): Promise<string[]> {
  return requestJson<string[]>('/api/v1/users/me/bundles/custom-tags');
}

export function createBundleCustomTag(tag: string): Promise<{ tag: string }> {
  return requestJson<{ tag: string }>('/api/v1/users/me/bundles/custom-tags', {
    method: 'POST', body: JSON.stringify({ tag }),
  });
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

export function deleteOwnBundles(
  visibility: 'public' | 'private',
): Promise<OwnBundlesDeletionResult> {
  return requestJson<OwnBundlesDeletionResult>(
    `/api/v1/users/me/bundles?visibility=${visibility}`,
    { method: 'DELETE' },
  );
}
