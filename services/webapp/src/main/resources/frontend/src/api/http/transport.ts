export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export interface ApiRequestInit extends RequestInit {
  /** Límite opcional; si no se indica se conserva el comportamiento nativo de fetch. */
  timeoutMs?: number;
}

/** Aplica URL base, credenciales y cancelación temporal al transporte del navegador. */
export async function apiFetch(path: string, init: ApiRequestInit = {}): Promise<Response> {
  const { timeoutMs, signal, ...requestInit } = init;
  if (timeoutMs === undefined) {
    return fetch(`${API_BASE}${path}`, {
      credentials: 'include',
      ...requestInit,
      signal,
    });
  }

  const controller = new AbortController();
  const forwardAbort = () => controller.abort(signal?.reason);
  if (signal?.aborted) forwardAbort();
  else signal?.addEventListener('abort', forwardAbort, { once: true });
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(`${API_BASE}${path}`, {
      credentials: 'include',
      ...requestInit,
      signal: controller.signal,
    });
  } finally {
    window.clearTimeout(timer);
    signal?.removeEventListener('abort', forwardAbort);
  }
}
