// The live adapter will use these with TanStack Query + generated endpoints.
// scope is an opaque local session generation, NEVER the bearer token.
export const chatKeys = {
  all: (scope: string) => ['chat', scope] as const,
  conversations: (scope: string) => ['chat', scope, 'conversations'] as const,
  history: (scope: string, conversation: string) => ['chat', scope, 'history', conversation] as const,
  run: (scope: string, run: string) => ['chat', scope, 'run', run] as const,
};
