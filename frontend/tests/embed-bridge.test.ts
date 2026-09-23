import { describe, expect, it } from 'vitest';
import {
  acceptParentMessage,
  cartPageUrl,
  hasForbiddenCredentials,
  isAllowedOrigin,
  parseAllowedOrigins,
  parseIncomingMessage,
} from '../src/widget/bridge';

const allowed = parseAllowedOrigins('http://localhost:5180,http://localhost:5173');
const parent = {} as MessageEventSource;

function message(partial: Partial<MessageEvent> & { data: unknown; origin: string }): MessageEvent {
  return {
    data: partial.data,
    origin: partial.origin,
    source: partial.source ?? parent,
  } as MessageEvent;
}

describe('embed bridge allowlist', () => {
  it('parses comma-separated origins', () => {
    expect(parseAllowedOrigins(' http://a ,http://b ')).toEqual(['http://a', 'http://b']);
    expect(isAllowedOrigin('http://localhost:5180', allowed)).toBe(true);
    expect(isAllowedOrigin('https://evil.example', allowed)).toBe(false);
  });

  it('accepts hello only from allowlisted parent source', () => {
    const ok = acceptParentMessage(
      message({
        origin: 'http://localhost:5180',
        source: parent,
        data: { type: 'hackalem:hello', audience: 'widget', nonce: '1' },
      }),
      allowed,
      parent,
    );
    expect(ok).toEqual({ type: 'hackalem:hello', audience: 'widget', nonce: '1' });
  });

  it('rejects wrong origin even with matching source', () => {
    expect(
      acceptParentMessage(
        message({
          origin: 'https://evil.example',
          source: parent,
          data: { type: 'hackalem:hello', audience: 'widget' },
        }),
        allowed,
        parent,
      ),
    ).toBeNull();
  });

  it('rejects wrong source even with allowlisted origin', () => {
    const forged = {} as MessageEventSource;
    expect(
      acceptParentMessage(
        message({
          origin: 'http://localhost:5180',
          source: forged,
          data: { type: 'hackalem:hello', audience: 'widget' },
        }),
        allowed,
        parent,
      ),
    ).toBeNull();
  });

  it('rejects credential-bearing payloads and does not treat them as session', () => {
    const payload = { type: 'hackalem:hello', audience: 'widget', token: 'steal-me', cartId: 'c1' };
    expect(hasForbiddenCredentials(payload)).toBe(true);
    expect(parseIncomingMessage(payload)).toBeNull();
    expect(
      acceptParentMessage(
        message({ origin: 'http://localhost:5180', source: parent, data: payload }),
        allowed,
        parent,
      ),
    ).toBeNull();
  });

  it('rejects unknown message shapes that could assign owner', () => {
    expect(parseIncomingMessage({ type: 'hackalem:ready', audience: 'host' })).toBeNull();
    expect(parseIncomingMessage({ type: 'set-owner', principal: 'x' })).toBeNull();
  });

  it('builds cart URL without query credentials', () => {
    expect(cartPageUrl('http://localhost:5173')).toBe('http://localhost:5173/cart');
    expect(cartPageUrl('http://localhost:5173/')).toBe('http://localhost:5173/cart');
  });
});
