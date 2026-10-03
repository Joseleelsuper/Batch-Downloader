import { test } from 'vitest';
import assert from 'node:assert/strict';
import { extractHead } from './build-seo-head.mjs';

test('preserves the actual Vite assets without duplicating the title or document', () => {
  const assets = '<link rel="stylesheet" href="/assets/main-12345678.css"><script type="module" src="/assets/main-abcdefgh.js"></script>';
  assert.equal(extractHead(`<html><head><title>Batch Downloader</title>${assets}</head><body></body></html>`), assets + '\n');
  assert.throws(() => extractHead('<head></head>'));
  assert.throws(() => extractHead('<head><title>a</title></head><head><title>b</title></head>'));
});
