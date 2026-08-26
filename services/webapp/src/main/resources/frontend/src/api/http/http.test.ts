import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { requestJson } from './client';
import { addCsrfHeader, invalidateCsrfToken, isUnsafeMethod } from './csrf';
import { ApiRequestError, responseError } from './errors';
import { apiFetch } from './transport';

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { 'Content-Type': 'application/json', ...init.headers },
  });
}

describe('HTTP client', () => {
  beforeEach(() => {
    invalidateCsrfToken();
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('lee JSON, respuestas vacías y 204 sin aplicar CSRF a GET', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ ok: true }))
      .mockResolvedValueOnce(new Response('', { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestJson<{ ok: boolean }>('/one')).resolves.toEqual({ ok: true });
    await expect(requestJson<null>('/empty')).resolves.toBeNull();
    await expect(requestJson<void>('/none')).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('añade tipo JSON y token CSRF a métodos mutables', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-1' }))
      .mockResolvedValueOnce(jsonResponse({ saved: true }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestJson('/save', { method: 'post', body: '{}' })).resolves.toEqual({ saved: true });
    const request = fetchMock.mock.calls[1]?.[1] as RequestInit;
    const headers = new Headers(request.headers);
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-1');
    expect(isUnsafeMethod('PATCH')).toBe(true);
    expect(isUnsafeMethod('get')).toBe(false);
  });

  it('respeta FormData y recupera el token desde una cookie codificada', async () => {
    document.cookie = 'XSRF-TOKEN=cookie%20token; Path=/';
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(jsonResponse({ uploaded: true }));
    vi.stubGlobal('fetch', fetchMock);
    const form = new FormData();
    form.set('file', 'value');

    await requestJson('/upload', { method: 'POST', body: form });
    const headers = new Headers((fetchMock.mock.calls[1]?.[1] as RequestInit).headers);
    expect(headers.has('Content-Type')).toBe(false);
    expect(headers.get('X-XSRF-TOKEN')).toBe('cookie token');
  });

  it('refresca una sola vez el CSRF ante forbidden y conserva los demás 403', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'old' }))
      .mockResolvedValueOnce(jsonResponse({ code: 'forbidden' }, { status: 403 }))
      .mockResolvedValueOnce(jsonResponse({ token: 'new' }))
      .mockResolvedValueOnce(jsonResponse({ saved: true }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestJson('/save', { method: 'DELETE' })).resolves.toEqual({ saved: true });
    expect(new Headers((fetchMock.mock.calls[3]?.[1] as RequestInit).headers)
      .get('X-XSRF-TOKEN')).toBe('new');

    invalidateCsrfToken();
    fetchMock.mockReset();
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 'token' }))
      .mockResolvedValueOnce(jsonResponse({ code: 'access_denied' }, { status: 403 }));
    await expect(requestJson('/save', { method: 'POST' })).rejects.toMatchObject({
      status: 403,
      code: 'access_denied',
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('normaliza errores Core, FastAPI y respuestas sin JSON', async () => {
    const core = await responseError(jsonResponse(
      { code: 'invalid', details: { field: 'name' } },
      { status: 422, headers: { 'Retry-After': '3' } },
    ));
    expect(core).toBeInstanceOf(ApiRequestError);
    expect(core).toMatchObject({ status: 422, code: 'invalid', retryAfter: '3', details: { field: 'name' } });

    await expect(responseError(jsonResponse(
      { detail: { code: 'fastapi_error' } },
      { status: 400 },
    ))).resolves.toMatchObject({ code: 'fastapi_error', details: {} });
    await expect(responseError(new Response('not-json', { status: 502 })))
      .resolves.toMatchObject({ code: 'request_failed_502' });
  });

  it('reutiliza una solicitud CSRF concurrente', async () => {
    let resolveToken: ((value: Response) => void) | undefined;
    const tokenResponse = new Promise<Response>((resolve) => { resolveToken = resolve; });
    const fetchMock = vi.fn().mockReturnValue(tokenResponse);
    vi.stubGlobal('fetch', fetchMock);
    const first = addCsrfHeader('POST', new Headers());
    const second = addCsrfHeader('PUT', new Headers());
    expect(fetchMock).toHaveBeenCalledTimes(1);
    resolveToken?.(jsonResponse({ token: 'shared' }));
    await expect(first).resolves.toSatisfy((headers: Headers) => headers.get('X-XSRF-TOKEN') === 'shared');
    await expect(second).resolves.toSatisfy((headers: Headers) => headers.get('X-XSRF-TOKEN') === 'shared');
  });

  it('propaga abortos externos y elimina el temporizador', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_path: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(init.signal?.reason ?? new DOMException('Aborted', 'AbortError')));
    }));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();
    const pending = apiFetch('/slow', { timeoutMs: 5000, signal: controller.signal });
    controller.abort(new DOMException('External', 'AbortError'));
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(vi.getTimerCount()).toBe(0);
  });

  it('aborta por timeout y conserva el transporte nativo sin límite', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_path: string, init?: RequestInit) => new Promise<Response>((resolve, reject) => {
      if (!init?.signal) {
        resolve(jsonResponse({ native: true }));
        return;
      }
      init.signal.addEventListener('abort', () => reject(new DOMException('Timeout', 'AbortError')));
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiFetch('/native')).resolves.toBeInstanceOf(Response);
    const pending = apiFetch('/timeout', { timeoutMs: 10 });
    const rejection = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    await vi.advanceTimersByTimeAsync(10);
    await rejection;
  });
});
