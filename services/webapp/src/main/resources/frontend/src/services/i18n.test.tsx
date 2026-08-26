import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import {
  I18nProvider,
  t,
  useTranslation,
  type TranslationKey,
} from './i18n';

const STORAGE_KEY = 'batch-downloader.locale.es.v1';

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, String(value)),
  };
}

function Message({ translationKey }: Readonly<{ translationKey: TranslationKey }>) {
  const translate = useTranslation();
  return <span>{translate(translationKey)}</span>;
}

describe('I18nProvider', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    const storage = memoryStorage();
    Object.defineProperty(window, 'localStorage', { configurable: true, value: storage });
    vi.stubGlobal('localStorage', storage);
  });

  it('usa el caché en el primer render y revalida con su ETag', async () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
      etag: '"locale-1"',
      messages: { 'nav.home': 'Inicio guardado' },
    }));
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 304 }));

    render(
      <I18nProvider fetcher={fetcher}>
        <Message translationKey="nav.home" />
      </I18nProvider>,
    );

    expect(screen.getByText('Inicio guardado')).toBeInTheDocument();
    await waitFor(() => expect(fetcher).toHaveBeenCalledOnce());
    expect(fetcher.mock.calls[0]?.[1]?.headers).toEqual({ 'If-None-Match': '"locale-1"' });
  });

  it('actualiza el árbol y el caché con un catálogo remoto válido', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({ 'nav.home': 'Portada remota' }),
      { status: 200, headers: { 'Content-Type': 'application/json', ETag: '"locale-2"' } },
    ));

    render(
      <I18nProvider fetcher={fetcher}>
        <Message translationKey="nav.home" />
      </I18nProvider>,
    );

    expect(screen.getByText('Inicio')).toBeInTheDocument();
    expect(await screen.findByText('Portada remota')).toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? '{}')).toEqual({
      etag: '"locale-2"',
      messages: { 'nav.home': 'Portada remota' },
    });
  });

  it('conserva el fallback empaquetado ante respuestas inválidas', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({ 'nav.home': 42 }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ));

    render(
      <I18nProvider fetcher={fetcher}>
        <Message translationKey="nav.home" />
      </I18nProvider>,
    );

    expect(screen.getByText('Inicio')).toBeInTheDocument();
    await waitFor(() => expect(fetcher).toHaveBeenCalledOnce());
    expect(screen.getByText('Inicio')).toBeInTheDocument();
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('mantiene la función estática aislada del estado del provider', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({ 'nav.home': 'Portada remota' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ));

    render(
      <I18nProvider fetcher={fetcher}>
        <Message translationKey="nav.home" />
      </I18nProvider>,
    );

    expect(await screen.findByText('Portada remota')).toBeInTheDocument();
    expect(t('nav.home')).toBe('Inicio');
  });
});
