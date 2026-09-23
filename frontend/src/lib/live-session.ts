import { logout, refresh, visitor } from '../client';
import type { SessionToken } from '../client';
import { auth } from './api';
import { apiError } from './api-errors';

type SessionView = { ready: boolean; busy: boolean; error: string | null; identity: SessionToken | null };
let view: SessionView = { ready: false, busy: true, error: null, identity: null };
const listeners = new Set<() => void>();
let pending: Promise<void> | undefined;
let started = false;
function publish(next: SessionView) { view = next; listeners.forEach((listener) => listener()); }

auth.subscribe(() => {
  if (!auth.get()) publish({ ready: false, busy: false, error: 'Сессия завершена. Начните новую или используйте выданный токен.', identity: null });
});

export const liveSession = {
  getSnapshot: () => view,
  subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
};

export function connectSession(token?: string): Promise<void> {
  if (pending) return pending;
  started = true;
  publish({ ...view, busy: true, error: null });
  if (token) auth.set(token.trim());
  pending = (async () => {
    try {
      const response = auth.get() ? await refresh({ throwOnError: true }) : await visitor({ throwOnError: true });
      if (!response.data.accessToken) throw new Error('Сервер не вернул токен сессии.');
      auth.set(response.data.accessToken);
      publish({ ready: true, busy: false, error: null, identity: response.data });
    } catch (error) {
      publish({ ready: false, busy: false, error: apiError(error), identity: null });
    } finally { pending = undefined; }
  })();
  return pending;
}

export function ensureSession() { if (!started) void connectSession(); }

export async function disconnectSession() {
  publish({ ...view, busy: true, error: null });
  try { await logout({ throwOnError: true }); auth.clear(); }
  catch (error) { publish({ ...view, busy: false, error: apiError(error) }); }
}
