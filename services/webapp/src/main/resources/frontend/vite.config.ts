import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react()],
  build: {
    manifest: true,
  },
  resolve: {
    alias: {
      '@batch-locales': resolve(import.meta.dirname, '../../../../../translation-service/locales'),
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
