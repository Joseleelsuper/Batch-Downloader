import accountBundles from '@batch-locales/es/account-bundles.json';
import accountDashboard from '@batch-locales/es/account-dashboard.json';
import accountLayout from '@batch-locales/es/account-layout.json';
import accountProfile from '@batch-locales/es/account-profile.json';
import adminApps from '@batch-locales/es/admin-apps.json';
import adminAudit from '@batch-locales/es/admin-audit.json';
import adminBundles from '@batch-locales/es/admin-bundles.json';
import adminDashboard from '@batch-locales/es/admin-dashboard.json';
import adminLayout from '@batch-locales/es/admin-layout.json';
import adminRequests from '@batch-locales/es/admin-requests.json';
import adminScraper from '@batch-locales/es/admin-scraper.json';
import adminSemantic from '@batch-locales/es/admin-semantic.json';
import adminShared from '@batch-locales/es/admin-shared.json';
import authenticationShared from '@batch-locales/es/authentication-shared.json';
import bundleDetail from '@batch-locales/es/bundle-detail.json';
import catalogPage from '@batch-locales/es/catalog.json';
import downloads from '@batch-locales/es/downloads.json';
import facetDirectory from '@batch-locales/es/facet-directory.json';
import forgotPassword from '@batch-locales/es/forgot-password.json';
import home from '@batch-locales/es/home.json';
import login from '@batch-locales/es/login.json';
import register from '@batch-locales/es/register.json';
import resetPassword from '@batch-locales/es/reset-password.json';
import shared from '@batch-locales/es/shared.json';
import verifyEmail from '@batch-locales/es/verify-email.json';

const es = {
  ...shared,
  ...home,
  ...catalogPage,
  ...facetDirectory,
  ...bundleDetail,
  ...downloads,
  ...authenticationShared,
  ...login,
  ...register,
  ...verifyEmail,
  ...forgotPassword,
  ...resetPassword,
  ...accountLayout,
  ...accountDashboard,
  ...accountBundles,
  ...accountProfile,
  ...adminLayout,
  ...adminDashboard,
  ...adminApps,
  ...adminBundles,
  ...adminScraper,
  ...adminSemantic,
  ...adminRequests,
  ...adminAudit,
  ...adminShared,
};

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
