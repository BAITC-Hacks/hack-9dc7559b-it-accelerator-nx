import { QueryClient } from '@tanstack/react-query';
import { retryDelayMs } from './retry';
import type { Session } from './session';

export function bindSessionCache(session: Session, cache: QueryClient) {
  return session.subscribe(() => {
    void cache.cancelQueries();
    cache.clear();
  });
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => retryDelayMs(error, failureCount) !== null,
      retryDelay: (failureCount, error) => retryDelayMs(error, failureCount) ?? 0,
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
    // Especially important for confirm: retries require the same operation ID
    // and server reconciliation, not a generic HTTP retry mechanism.
    mutations: { retry: false },
  },
});
