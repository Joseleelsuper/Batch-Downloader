import es from '@batch-locales/es.json';

export type TranslationKey = keyof typeof es;

export function t(key: TranslationKey | string, params?: Record<string, string | number>): string {
  const text = (es as Record<string, string>)[key] ?? key;
  if (!params) return text;
  return text.replace(/\{(\w+)}/g, (_, name: string) => String(params[name] ?? `{${name}}`));
}
