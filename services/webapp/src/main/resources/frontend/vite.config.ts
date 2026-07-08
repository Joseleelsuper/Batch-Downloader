import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@batch-locales': resolve(__dirname, '../../../../../translation-service/locales'),
    },
  },
  server: {
    port: 5173,
    fs: {
      allow: [
        resolve(__dirname),
        resolve(__dirname, '../../../../../translation-service/locales'),
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
  },
});
