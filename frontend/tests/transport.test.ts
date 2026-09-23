import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { QueryClient } from '@tanstack/react-query';
import { createClient } from '../src/client/client';
import { ping } from '../src/client';
import type { PingResponses } from '../src/client';
import { createSession, TOKEN_KEY } from '../src/lib/session';
import { configureTransport } from '../src/lib/transport';
import { retryDelayMs, HttpResponseError } from '../src/lib/retry';
import { streamEvents } from '../src/lib/stream';
import { bindSessionCache } from '../src/lib/query';
import { pingFixture } from '../src/mocks/fixtures';
import { sseResponse } from '../src/mocks/sse';
import { server } from './setup';

const baseURL = 'https://api.test';
// Transport-only stream of the existing Ping DTO; not a made-up ChatEvent API.
const streamURL = '/__transport_test__/events';

function setup() {
  const session = createSession();
  session.set('visitor-a');
  const client = createClient();
  configureTransport(client, baseURL, session);
  return { session, client };
}

function sse(id: string) {
  return sseResponse([{ id, data: pingFixture }]);
}

afterEach(() => vi.useRealTimers());

describe('generated REST and SSE transport', () => {
  it('sends the same Bearer token over axios and fetch, without an endpoint interceptor', async () => {
    const { session, client } = setup();
    const tokens: (string | null)[] = [];
    server.use(
      http.get(`${baseURL}/api/ping`, ({ request }) => {
        tokens.push(request.headers.get('Authorization'));
        return HttpResponse.json(pingFixture);
      }),
      http.get(`${baseURL}${streamURL}`, ({ request }) => {
        tokens.push(request.headers.get('Authorization'));
        return sse('1');
      }),
    );
    await ping({ client, throwOnError: true });
    const response = await client.sse.get<PingResponses>({ url: streamURL });
    for await (const event of response.stream) expect(event).toEqual(pingFixture);
    session.set('visitor-b');
    await ping({ client, throwOnError: true });
    const next = await client.sse.get<PingResponses>({ url: streamURL });
    for await (const event of next.stream) expect(event).toEqual(pingFixture);
    expect(tokens).toEqual(['Bearer visitor-a', 'Bearer visitor-a', 'Bearer visitor-b', 'Bearer visitor-b']);
  });

  it('expires a session on REST 401 without retrying', async () => {
    const { session, client } = setup();
    const responder = vi.fn(() => new HttpResponse(null, { status: 401 }));
    server.use(http.get(`${baseURL}/api/ping`, responder));
    await expect(ping({ client, throwOnError: true })).rejects.toMatchObject({ response: { status: 401 } });
    expect(session.get()).toBeNull();
    expect(responder).toHaveBeenCalledTimes(1);
  });

  it('stops SSE on 401 and exposes the error instead of silently completing', async () => {
    const { session, client } = setup();
    const responder = vi.fn(() => new HttpResponse(null, { status: 401 }));
    server.use(http.get(`${baseURL}${streamURL}`, responder));
    const stream = streamEvents(
      (options) => client.sse.get<PingResponses>({ url: streamURL, ...options }),
      { session, isTerminal: () => false },
    );
    await expect(stream.next()).rejects.toMatchObject({ status: 401 });
    expect(session.get()).toBeNull();
    expect(responder).toHaveBeenCalledTimes(1);
  });

  it('reconnects after EOF with Last-Event-ID and never treats EOF as success', async () => {
    vi.useFakeTimers();
    const { session, client } = setup();
    const cursors: (string | null)[] = [];
    server.use(http.get(`${baseURL}${streamURL}`, ({ request }) => {
      cursors.push(request.headers.get('Last-Event-ID'));
      return sse(String(cursors.length));
    }));
    let received = 0;
    const stream = streamEvents(
      (options) => client.sse.get<PingResponses>({ url: streamURL, ...options }),
      { session, isTerminal: () => ++received === 2 },
    );
    expect((await stream.next()).value).toEqual(pingFixture);
    const second = stream.next();
    await vi.runAllTimersAsync();
    expect((await second).value).toEqual(pingFixture);
    expect((await stream.next()).done).toBe(true);
    expect(cursors).toEqual([null, '1']);
  });

  it.each([429, 503])('bounds %s retries and honors Retry-After', async (status) => {
    vi.useFakeTimers();
    const { session, client } = setup();
    const requestTimes: number[] = [];
    server.use(http.get(`${baseURL}${streamURL}`, () => {
      requestTimes.push(Date.now());
      return new HttpResponse(null, { status, headers: { 'Retry-After': '2' } });
    }));
    const stream = streamEvents(
      (options) => client.sse.get<PingResponses>({ url: streamURL, ...options }),
      { session, isTerminal: () => false },
    );
    const assertion = expect(stream.next()).rejects.toMatchObject({ status });
    await vi.runAllTimersAsync();
    await assertion;
    expect(requestTimes).toHaveLength(3);
    expect(requestTimes[1] - requestTimes[0]).toBeGreaterThanOrEqual(2000);
    expect(requestTimes[2] - requestTimes[1]).toBeGreaterThanOrEqual(2000);
  });

  it('does not replay a mutation on 503', async () => {
    const { client } = setup();
    const responder = vi.fn(() => new HttpResponse(null, { status: 503 }));
    server.use(http.post(`${baseURL}/__transport_test__/mutation`, responder));
    await expect(client.post({ url: '/__transport_test__/mutation', throwOnError: true })).rejects.toBeDefined();
    expect(responder).toHaveBeenCalledTimes(1);
  });

  it('rejects cross-origin credential forwarding', async () => {
    const { client } = setup();
    await expect(client.get({ baseURL: 'https://untrusted.test', url: '/resource', throwOnError: true })).rejects.toThrow('another origin');
    const errors: unknown[] = [];
    const { stream } = await client.sse.get({
      baseURL: 'https://untrusted.test', url: '/resource', onSseError: (error) => errors.push(error),
    });
    for await (const event of stream) expect(event).toBeUndefined();
    expect(errors).toHaveLength(1);
    expect(String(errors[0])).toContain('another origin');
  });
});

describe('session lifecycle', () => {
  it('aborts old requests and clears queries and mutation cache on a switch', async () => {
    const session = createSession();
    const cache = new QueryClient();
    bindSessionCache(session, cache);
    session.set('a');
    const previous = session.snapshot();
    cache.setQueryData(['private'], 'a-secret');
    cache.getMutationCache().build(cache, { mutationKey: ['confirm'] });
    session.set('b');
    expect(previous.signal.aborted).toBe(true);
    expect(cache.getQueryCache().getAll()).toHaveLength(0);
    expect(cache.getMutationCache().getAll()).toHaveLength(0);
    session.expire(previous.signal);
    expect(session.get()).toBe('b');
  });

  it('works when iframe storage is denied and syncs logout from another tab', () => {
    const denied = () => { throw new DOMException('Denied', 'SecurityError'); };
    const session = createSession({ getItem: denied, setItem: denied, removeItem: denied });
    session.set('a');
    expect(session.get()).toBe('a');
    session.clear();
    expect(session.get()).toBeNull();
    const storage = { getItem: () => 'a', setItem: vi.fn(), removeItem: vi.fn() };
    const persisted = createSession(storage);
    const previous = persisted.snapshot();
    persisted.sync(null);
    expect(previous.signal.aborted).toBe(true);
    expect(storage.removeItem).not.toHaveBeenCalled();
    persisted.set('b');
    expect(storage.setItem).toHaveBeenCalledWith(TOKEN_KEY, 'b');
  });
});

describe('retry policy', () => {
  it('respects a server delay longer than the retry budget by stopping', () => {
    const error = new HttpResponseError(new Response(null, { status: 429, headers: { 'Retry-After': '120' } }));
    expect(retryDelayMs(error, 0)).toBeNull();
  });

  it('supports HTTP dates and rejects auth, conflict, validation and exhausted retries', () => {
    const now = Date.now();
    const response = new Response(null, { status: 503, headers: { 'Retry-After': new Date(now + 5000).toUTCString() } });
    expect(retryDelayMs(new HttpResponseError(response), 0, now)).toBeGreaterThanOrEqual(4000);
    expect(retryDelayMs(new HttpResponseError(response), 2, now)).toBeNull();
    for (const status of [400, 401, 403, 404, 409, 413, 415, 422]) {
      expect(retryDelayMs(new HttpResponseError(new Response(null, { status })), 0)).toBeNull();
    }
  });
});
