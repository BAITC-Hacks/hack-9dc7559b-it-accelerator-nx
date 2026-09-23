import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export async function startDevelopmentMocks() {
  if (!import.meta.env.DEV || import.meta.env.MODE !== 'mock') {
    throw new Error('Browser mocks are available only in dev:mock');
  }
  const worker = setupWorker(...handlers);
  await worker.start({
    serviceWorker: { url: '/__mocks__/mockServiceWorker.js', options: { scope: '/' } },
    onUnhandledRequest(request, print) {
      const path = new URL(request.url).pathname;
      // Missing business fixtures must not fall through to the live backend.
      // Vite modules, HMR and static assets are allowed to load normally.
      if (path.startsWith('/api/') || path.startsWith('/auth/')) print.error();
    },
  });
  return worker;
}
