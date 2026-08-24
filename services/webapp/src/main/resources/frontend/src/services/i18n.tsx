import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
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
import legal from '@batch-locales/es/legal.json';
import login from '@batch-locales/es/login.json';
import errorPage from '@batch-locales/es/error.json';
import register from '@batch-locales/es/register.json';
import resetPassword from '@batch-locales/es/reset-password.json';
import shared from '@batch-locales/es/shared.json';
import verifyEmail from '@batch-locales/es/verify-email.json';

const bundledMessages = {
  ...shared,
  ...home,
  ...legal,
  ...errorPage,
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
} as const;

export type TranslationKey = keyof typeof bundledMessages;
export type TranslationParams = Readonly<Record<string, string | number>>;
export type Translator = (key: TranslationKey | string, params?: TranslationParams) => string;

type TranslationCatalog = Readonly<Record<TranslationKey, string>>;
type LocaleFetcher = typeof fetch;

interface StoredLocale {
  etag?: string;
  messages: Partial<Record<TranslationKey, string>>;
}

interface LocaleState {
  catalog: TranslationCatalog;
  etag?: string;
}

interface I18nProviderProps {
  children: ReactNode;
  fetcher?: LocaleFetcher;
}

const STORAGE_KEY = 'batch-downloader.locale.es.v1';
const REQUEST_TIMEOUT_MS = 1_800;
const BUNDLED_CATALOG: TranslationCatalog = Object.freeze({ ...bundledMessages });
const BUNDLED_KEYS = new Set<string>(Object.keys(BUNDLED_CATALOG));
const DEFAULT_FETCHER: LocaleFetcher = (...args) => fetch(...args);

function parseMessages(value: unknown): Partial<Record<TranslationKey, string>> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;

  const parsed: Partial<Record<TranslationKey, string>> = {};
  for (const [key, message] of Object.entries(value)) {
    if (!BUNDLED_KEYS.has(key) || typeof message !== 'string') return null;
    parsed[key as TranslationKey] = message;
  }
  return parsed;
}

function mergedCatalog(messages?: Partial<Record<TranslationKey, string>>): TranslationCatalog {
  return Object.freeze({ ...BUNDLED_CATALOG, ...messages });
}

function readStoredLocale(): LocaleState {
  if (typeof window === 'undefined') return { catalog: BUNDLED_CATALOG };

  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (!stored) return { catalog: BUNDLED_CATALOG };
    const candidate = JSON.parse(stored) as { etag?: unknown; messages?: unknown };
    const messages = parseMessages(candidate.messages);
    if (!messages) return { catalog: BUNDLED_CATALOG };
    return {
      catalog: mergedCatalog(messages),
      etag: typeof candidate.etag === 'string' ? candidate.etag : undefined,
    };
  } catch {
    return { catalog: BUNDLED_CATALOG };
  }
}

function persistLocale(locale: StoredLocale): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(locale));
  } catch {
    // El almacenamiento es una optimización; nunca debe impedir el arranque.
  }
}

function formatMessage(
  catalog: TranslationCatalog,
  key: TranslationKey | string,
  params?: TranslationParams,
): string {
  const text = catalog[key as TranslationKey] ?? key;
  if (!params) return text;
  return text.replace(/\{(\w+)}/g, (_, name: string) => String(params[name] ?? `{${name}}`));
}

/** Traduce sin React usando exclusivamente el catálogo empaquetado e inmutable. */
export const t: Translator = (key, params) => formatMessage(BUNDLED_CATALOG, key, params);

const I18nContext = createContext<Translator>(t);

/**
 * Mantiene el catálogo activo dentro del árbol React, aplica el caché local de
 * forma síncrona y revalida en segundo plano sin bloquear el primer renderizado.
 */
export function I18nProvider({ children, fetcher = DEFAULT_FETCHER }: Readonly<I18nProviderProps>) {
  const [locale, setLocale] = useState<LocaleState>(readStoredLocale);
  const revalidationEtag = useRef(locale.etag);

  useEffect(() => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

    async function refreshLocale() {
      try {
        const response = await fetcher('/api/v1/locales/es', {
          credentials: 'include',
          headers: revalidationEtag.current
            ? { 'If-None-Match': revalidationEtag.current }
            : undefined,
          signal: controller.signal,
        });
        if (response.status === 304) return;
        if (!response.ok) throw new Error(`locale_request_failed_${response.status}`);

        const messages = parseMessages(await response.json() as unknown);
        if (!messages) throw new Error('invalid_locale_catalog');

        const nextLocale: LocaleState = {
          catalog: mergedCatalog(messages),
          etag: response.headers.get('ETag') ?? undefined,
        };
        if (controller.signal.aborted) return;
        revalidationEtag.current = nextLocale.etag;
        setLocale(nextLocale);
        persistLocale({ etag: nextLocale.etag, messages });
      } catch {
        // Red, timeout o respuesta inválida: conservar el catálogo vigente.
      } finally {
        window.clearTimeout(timeout);
      }
    }

    void refreshLocale();
    return () => {
      controller.abort();
      window.clearTimeout(timeout);
    };
  }, [fetcher]);

  const translator = useMemo<Translator>(
    () => (key, params) => formatMessage(locale.catalog, key, params),
    [locale.catalog],
  );

  return <I18nContext.Provider value={translator}>{children}</I18nContext.Provider>;
}

export function useTranslation(): Translator {
  return useContext(I18nContext);
}
