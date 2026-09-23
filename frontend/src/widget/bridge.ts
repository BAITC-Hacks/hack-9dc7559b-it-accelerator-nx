/** Parent↔iframe protocol. Identity is never taken from parent messages or URL. */

export type EmbedOutgoing =
  | { type: 'hackalem:ready'; audience: 'host' }
  | { type: 'hackalem:close'; audience: 'host' }
  | { type: 'hackalem:open-cart'; audience: 'host'; url: string };

export type EmbedIncoming =
  | { type: 'hackalem:hello'; audience: 'widget'; nonce?: string }
  | { type: 'hackalem:close'; audience: 'widget' };

const FORBIDDEN = new Set([
  'token',
  'cartid',
  'principal',
  'authorization',
  'jwt',
  'password',
  'secret',
  'bearer',
  'owner',
]);

export const DEFAULT_EMBED_ORIGINS = 'http://localhost:5180,http://localhost:5173';

export function parseAllowedOrigins(raw: string | undefined): string[] {
  return (raw ?? DEFAULT_EMBED_ORIGINS)
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

export function isAllowedOrigin(origin: string, allowed: readonly string[]): boolean {
  return allowed.includes(origin);
}

export function hasForbiddenCredentials(data: unknown): boolean {
  if (!data || typeof data !== 'object') return false;
  return Object.keys(data as Record<string, unknown>).some((key) => FORBIDDEN.has(key.toLowerCase()));
}

export function parseIncomingMessage(data: unknown): EmbedIncoming | null {
  if (!data || typeof data !== 'object' || hasForbiddenCredentials(data)) return null;
  const msg = data as Record<string, unknown>;
  if (msg.type === 'hackalem:hello' && msg.audience === 'widget') {
    return {
      type: 'hackalem:hello',
      audience: 'widget',
      nonce: typeof msg.nonce === 'string' ? msg.nonce : undefined,
    };
  }
  if (msg.type === 'hackalem:close' && msg.audience === 'widget') {
    return { type: 'hackalem:close', audience: 'widget' };
  }
  return null;
}

/** Parent messages must match allowlist origin and the real parent window. */
export function acceptParentMessage(
  event: MessageEvent,
  allowedOrigins: readonly string[],
  expectedSource: MessageEventSource | null,
): EmbedIncoming | null {
  if (expectedSource != null && event.source !== expectedSource) return null;
  if (!isAllowedOrigin(event.origin, allowedOrigins)) return null;
  return parseIncomingMessage(event.data);
}

export function cartPageUrl(origin = typeof window !== 'undefined' ? window.location.origin : ''): string {
  return `${origin.replace(/\/$/, '')}/cart`;
}
