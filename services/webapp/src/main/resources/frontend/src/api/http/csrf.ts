import { apiFetch } from './transport';

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

let cachedCsrfToken: string | undefined;
let csrfRequest: Promise<string | undefined> | undefined;

function cookieValue(name: string): string | undefined {
  return document.cookie
    .split(';')
    .map((value) => value.trim())
    .find((value) => value.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

async function ensureCsrfToken(forceRefresh = false): Promise<string | undefined> {
  if (!forceRefresh && cachedCsrfToken) return cachedCsrfToken;
  if (csrfRequest) return csrfRequest;
  csrfRequest = (async () => {
    const response = await apiFetch('/api/v1/auth/csrf');
    if (response.ok) {
      const body = await response.json().catch(() => null) as { token?: string } | null;
      if (body?.token) {
        cachedCsrfToken = body.token;
        return cachedCsrfToken;
      }
    }
    const token = cookieValue('XSRF-TOKEN');
    cachedCsrfToken = token ? decodeURIComponent(token) : undefined;
    return cachedCsrfToken;
  })();
  try {
    return await csrfRequest;
  } finally {
    csrfRequest = undefined;
  }
}

export function isUnsafeMethod(method: string): boolean {
  return UNSAFE_METHODS.has(method.toUpperCase());
}

export async function addCsrfHeader(
  method: string,
  headers: Headers,
  forceRefresh = false,
): Promise<Headers> {
  if (!isUnsafeMethod(method)) return headers;
  const token = await ensureCsrfToken(forceRefresh);
  if (token) headers.set('X-XSRF-TOKEN', token);
  return headers;
}

export function invalidateCsrfToken(): void {
  cachedCsrfToken = undefined;
}
