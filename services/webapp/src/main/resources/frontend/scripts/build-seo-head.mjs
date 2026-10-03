import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

/** Extrae recursos del mismo build que sirve Nginx, sin duplicar sus hashes. */
export function extractHead(html) {
  const heads = [...html.matchAll(/<head\b[^>]*>([\s\S]*?)<\/head>/gi)];
  if (heads.length !== 1) throw new Error('Expected exactly one HTML head');
  const titles = [...heads[0][1].matchAll(/<title\b[^>]*>[\s\S]*?<\/title>/gi)];
  if (titles.length !== 1) throw new Error('Expected exactly one fallback title');
  return heads[0][1].replace(titles[0][0], '')
    .replace(/<meta\b[^>]*(?:charset\s*=|name\s*=\s*["']viewport["'])[^>]*>/gi, '').trim() + '\n';
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const head = extractHead(await readFile(new URL('../dist/index.html', import.meta.url), 'utf8'));
  await mkdir(new URL('../dist/_seo/', import.meta.url), { recursive: true });
  await writeFile(new URL('../dist/_seo/head.html', import.meta.url), head);
}
