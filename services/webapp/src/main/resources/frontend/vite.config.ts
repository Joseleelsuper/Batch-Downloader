import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { execFileSync } from 'node:child_process';
import { statSync } from 'node:fs';
import { resolve } from 'node:path';

const repositoryRoot = resolve(import.meta.dirname, '../../../../../..');
const legalMessagesPath = 'services/translation-service/locales/es/legal.json';
const localesRoot = resolve(import.meta.dirname, '../../../../../translation-service/locales');

function legalLastUpdated(): string {
  const absolutePath = resolve(localesRoot, 'es/legal.json');
  try {
    const pendingChanges = execFileSync(
      'git',
      ['-C', repositoryRoot, 'status', '--porcelain', '--', legalMessagesPath],
      { encoding: 'utf8' },
    ).trim();
    if (pendingChanges) return statSync(absolutePath).mtime.toISOString();

    const committedAt = execFileSync(
      'git',
      ['-C', repositoryRoot, 'log', '-1', '--format=%cI', '--', legalMessagesPath],
      { encoding: 'utf8' },
    ).trim();
    if (committedAt) return committedAt;
  } catch {
    // Los artefactos de producción pueden compilarse sin el directorio .git.
  }
  return statSync(absolutePath).mtime.toISOString();
}

export default defineConfig({
  plugins: [react()],
  define: {
    __LEGAL_LAST_UPDATED__: JSON.stringify(legalLastUpdated()),
  },
  build: {
    manifest: true,
  },
  resolve: {
    alias: {
      '@batch-locales': localesRoot,
    },
  },
  server: {
    port: 5173,
    fs: {
      allow: [
        resolve(import.meta.dirname),
        resolve(import.meta.dirname, '../../../../../translation-service/locales'),
      ],
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    coverage: {
      provider: 'v8',
      thresholds: {
        statements: 65,
        branches: 78,
        functions: 55,
      },
    },
  },
});
