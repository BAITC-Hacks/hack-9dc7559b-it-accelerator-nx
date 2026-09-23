import { client } from '@/client/client.gen';
import { bindSessionCache, queryClient } from './query';
import { createSession, TOKEN_KEY } from './session';
import { configureTransport } from './transport';

/**
 * Единственное место, где живёт адрес API. Хардкодить localhost где-то ещё — нельзя.
 * VITE_API_URL — переменная времени СБОРКИ (см. frontend/AGENTS.md).
 */
export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

function browserStorage() {
  try { return window.localStorage; } catch { return undefined; }
}

export const auth = createSession(browserStorage());
// Prevent one visitor from seeing cached data belonging to the previous one.
bindSessionCache(auth, queryClient);
export const http = configureTransport(client, API_URL, auth);

window.addEventListener('storage', (event) => {
  if (event.storageArea === browserStorage() && (event.key === TOKEN_KEY || event.key === null)) {
    auth.sync(event.newValue);
  }
});
