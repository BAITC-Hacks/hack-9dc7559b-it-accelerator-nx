/** Client-side lifecycle only; identity is issued and checked by the backend. */
export const TOKEN_KEY = 'hackalem.token';

type TokenStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export function createSession(storage?: TokenStorage) {
  let token: string | null = null;
  try {
    token = storage?.getItem(TOKEN_KEY) ?? null;
  } catch {
    // Third-party iframe storage can be unavailable. Keep this session in memory.
  }
  let controller = new AbortController();
  const listeners = new Set<() => void>();

  function change(next: string | null, persist: boolean) {
    if (persist) {
      try {
        if (next === null) storage?.removeItem(TOKEN_KEY);
        else storage?.setItem(TOKEN_KEY, next);
      } catch {
        // In-memory auth still works if the browser denies storage access.
      }
    }
    if (next === token) return;
    const previous = controller;
    token = next;
    controller = new AbortController();
    previous.abort(new DOMException('Session changed', 'AbortError'));
    listeners.forEach((listener) => listener());
  }

  return {
    get: () => token,
    snapshot: () => ({ token, signal: controller.signal }),
    set: (value: string) => change(value, true),
    clear: () => change(null, true),
    sync: (value: string | null) => change(value, false),
    /** A late 401 from an old request must not clear a newer session. */
    expire: (signal: AbortSignal) => {
      if (signal === controller.signal) change(null, true);
    },
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
  };
}

export type Session = ReturnType<typeof createSession>;
