import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath, URL } from 'node:url';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';

export default defineConfig(({ command, mode }) => ({
  plugins: [react(), tailwindcss(), ...(command === 'serve' && mode === 'mock' ? [{
    name: 'development-mock-worker',
    configureServer(server: import('vite').ViteDevServer) {
      server.middlewares.use('/__mocks__/mockServiceWorker.js', (_request, response, next) => {
        const require = createRequire(import.meta.url);
        readFile(require.resolve('msw/mockServiceWorker.js')).then((source) => {
          response.setHeader('Content-Type', 'application/javascript');
          response.setHeader('Cache-Control', 'no-store');
          response.setHeader('Service-Worker-Allowed', '/');
          response.end(source);
        }).catch(next);
      });
    },
  }] : [])],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // host: true — чтобы dev-сервер отвечал наружу (в т.ч. из контейнера)
    host: true,
    port: 5173,
  },
}));
