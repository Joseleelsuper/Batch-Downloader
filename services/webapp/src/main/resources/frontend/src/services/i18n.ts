import es from '@batch-locales/es.json';

export type TranslationKey = keyof typeof es;

const STORAGE_KEY = 'batch-downloader.locale.es.v1';
let catalog: Record<string, string> = { ...es };

interface StoredLocale {
  etag?: string;
  messages: Record<string, string>;
}

function validMessages(value: unknown): value is Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  return Object.values(value).every((message) => typeof message === 'string');
}

export async function loadRuntimeLocale(fetcher: typeof fetch = fetch): Promise<void> {
  let cached: StoredLocale | undefined;
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored) {
      const candidate = JSON.parse(stored) as StoredLocale;
      if (validMessages(candidate.messages)) {
        cached = candidate;
        catalog = { ...es, ...candidate.messages };
      }
    }
  } catch {
    // El catálogo empaquetado sigue siendo un fallback completo.
  }

  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 1800);
  try {
    const response = await fetcher('/api/v1/locales/es', {
      credentials: 'include',
      headers: cached?.etag ? { 'If-None-Match': cached.etag } : undefined,
      signal: controller.signal,
    });
    if (response.status === 304) return;
    if (!response.ok) throw new Error(`locale_request_failed_${response.status}`);
    const messages = await response.json() as unknown;
    if (!validMessages(messages)) throw new Error('invalid_locale_catalog');
    catalog = { ...es, ...messages };
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
        etag: response.headers.get('ETag') ?? undefined,
        messages,
      } satisfies StoredLocale));
    } catch {
      // El almacenamiento es una optimización, no un requisito de arranque.
    }
  } catch {
    // Fallo de red o catálogo inválido: conservar el fallback local.
  } finally {
    window.clearTimeout(timeout);
  }
}

export function t(key: TranslationKey | string, params?: Record<string, string | number>): string {
  const text = catalog[key] ?? key;
  if (!params) return text;
  return text.replace(/\{(\w+)}/g, (_, name: string) => String(params[name] ?? `{${name}}`));
}
