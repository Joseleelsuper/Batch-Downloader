import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { apiFetch } from '../api/http';

export interface SeoMetadata {
  title: string;
  description: string;
  canonicalUrl: string;
  imageUrl: string | null;
  imageAlt: string;
  robots: string;
  type: string;
  structuredData: Record<string, unknown> | null;
}

function meta(attribute: 'name' | 'property', key: string, content?: string | null) {
  const selector = `meta[${attribute}="${key}"]`;
  const existing = document.head.querySelector<HTMLMetaElement>(selector);
  if (!content) { existing?.remove(); return; }
  const element = existing ?? document.createElement('meta');
  element.setAttribute(attribute, key);
  element.content = content;
  if (!existing) document.head.append(element);
}

function applyMetadata(value: SeoMetadata) {
  document.title = value.title;
  meta('name', 'description', value.description);
  meta('name', 'robots', value.robots);
  meta('property', 'og:title', value.title);
  meta('property', 'og:description', value.description);
  meta('property', 'og:url', value.canonicalUrl);
  meta('property', 'og:type', value.type);
  meta('property', 'og:site_name', 'Batch Downloader');
  meta('property', 'og:locale', 'es_ES');
  meta('property', 'og:image', value.imageUrl);
  meta('property', 'og:image:alt', value.imageUrl ? value.imageAlt : null);
  meta('property', 'og:image:width', value.imageUrl ? '1200' : null);
  meta('property', 'og:image:height', value.imageUrl ? '630' : null);
  meta('property', 'og:image:type', value.imageUrl ? 'image/png' : null);
  meta('name', 'twitter:card', value.imageUrl ? 'summary_large_image' : 'summary');
  meta('name', 'twitter:title', value.title);
  meta('name', 'twitter:description', value.description);
  meta('name', 'twitter:image', value.imageUrl);
  meta('name', 'twitter:image:alt', value.imageUrl ? value.imageAlt : null);
  let canonical = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
  if (value.canonicalUrl) {
    if (!canonical) { canonical = document.createElement('link'); canonical.rel = 'canonical'; document.head.append(canonical); }
    canonical.href = value.canonicalUrl;
  } else canonical?.remove();
  document.head.querySelectorAll('script[type="application/ld+json"]').forEach((element) => element.remove());
  if (value.structuredData) {
    const script = document.createElement('script');
    script.type = 'application/ld+json';
    script.textContent = JSON.stringify(value.structuredData).replace(/</g, String.raw`\u003c`);
    document.head.append(script);
  }
}

const PRIVATE_PATH = /^\/(?:login|admin|dashboard|profile|error)(?:\/|$)/;
const EMPTY_METADATA: SeoMetadata = {
  title: 'Batch Downloader', description: '', canonicalUrl: '', imageUrl: null,
  imageAlt: '', robots: 'noindex, follow', type: 'website', structuredData: null,
};

/** Conserva el HTML inicial y actualiza el head al navegar sin recargar. */
export function PageMetadata() {
  const { pathname, search } = useLocation();
  const lastPath = useRef<string | null>(null);
  useEffect(() => {
    const path = `${pathname}${search}`;
    const initialServerHead = lastPath.current === null
      && document.head.querySelector('meta[property="og:url"]') !== null;
    if (!initialServerHead && lastPath.current !== path) applyMetadata(EMPTY_METADATA);
    lastPath.current = path;
    // Los enlaces de acceso pueden contener tokens: nunca se envían al resolver SEO.
    if (PRIVATE_PATH.test(pathname)) { applyMetadata(EMPTY_METADATA); return; }
    const controller = new AbortController();
    void apiFetch(`/api/v1/seo/metadata?${new URLSearchParams({ path })}`, {
      signal: controller.signal, timeoutMs: 5_000,
    }).then(async (response) => {
      if (!response.ok && response.status !== 404) return;
      const value = await response.json() as SeoMetadata;
      if (!controller.signal.aborted && typeof value.title === 'string') applyMetadata(value);
    }).catch(() => { /* El contenido de la página sigue disponible si falla su metadato. */ });
    return () => controller.abort();
  }, [pathname, search]);
  return null;
}
