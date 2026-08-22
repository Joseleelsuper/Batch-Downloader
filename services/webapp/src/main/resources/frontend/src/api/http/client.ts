import { addCsrfHeader, invalidateCsrfToken, isUnsafeMethod } from './csrf';
import { responseError } from './errors';
import { apiFetch, type ApiRequestInit } from './transport';

/** Compone credenciales, CSRF, reintento único y mapeo de errores para JSON. */
export async function requestJson<T>(path: string, init?: ApiRequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase();
  const headers = new Headers(init?.headers);
  if (init?.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  await addCsrfHeader(method, headers);
  let response = await apiFetch(path, { ...init, headers });
  const forbiddenCode = response.status === 403
    ? await response.clone().json().then((body: { code?: string }) => body.code).catch(() => undefined)
    : undefined;
  if (response.status === 403 && forbiddenCode === 'forbidden' && isUnsafeMethod(method)) {
    invalidateCsrfToken();
    const retryHeaders = await addCsrfHeader(method, new Headers(headers), true);
    response = await apiFetch(path, { ...init, headers: retryHeaders });
  }
  if (!response.ok) throw await responseError(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return null as T;
  return JSON.parse(text) as T;
}
