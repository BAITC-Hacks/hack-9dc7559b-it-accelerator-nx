import axios, { CanceledError, isAxiosError } from 'axios';
import type { Client, Config } from '../client/client';
import type { ServerSentEventsOptions } from '../client/core/serverSentEvents.gen';
import { HttpResponseError } from './retry';
import type { Session } from './session';

/** Configure the existing generated client; never patch generated code. */
export function configureTransport(client: Client, baseURL: string, session: Session) {
  const http = axios.create({ baseURL, timeout: 15_000 });
  const snapshots = new WeakMap<object, ReturnType<Session['snapshot']>>();
  const getToken = () => session.get() ?? undefined;
  const apiOrigin = new URL(baseURL, globalThis.location?.origin).origin;

  const assertApiOrigin = (url: string) => {
    if (new URL(url, baseURL).origin !== apiOrigin) {
      throw new Error('API transport cannot send session credentials to another origin');
    }
  };

  http.interceptors.request.use((config) => {
    assertApiOrigin(axios.getUri(config));
    const snapshot = session.snapshot();
    snapshots.set(config, snapshot);
    const token = getToken();
    if (token) config.headers.set('Authorization', `Bearer ${token}`);
    else config.headers.delete('Authorization');
    config.signal = config.signal instanceof AbortSignal
      ? AbortSignal.any([config.signal, snapshot.signal]) : snapshot.signal;
    return config;
  });
  http.interceptors.response.use(
    (response) => {
      if (snapshots.get(response.config)?.signal.aborted) throw new CanceledError('Session changed');
      return response;
    },
    (error: unknown) => {
      if (isAxiosError(error) && error.config) {
        const snapshot = snapshots.get(error.config);
        if (snapshot && error.response?.status === 401) session.expire(snapshot.signal);
      }
      return Promise.reject(error);
    },
  );

  // Axios interceptors do not run for generated SSE (which calls fetch).
  const streamFetch: typeof fetch = async (input, init) => {
    const request = new Request(input, init);
    assertApiOrigin(request.url);
    const snapshot = session.snapshot();
    const headers = new Headers(request.headers);
    const token = getToken();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    else headers.delete('Authorization');
    const signal = AbortSignal.any([request.signal, snapshot.signal]);
    signal.throwIfAborted();
    const response = await globalThis.fetch(new Request(request, {
      headers, signal, redirect: 'error',
    }));
    if (!response.ok) {
      const error = new HttpResponseError(response);
      await response.body?.cancel();
      if (response.status === 401) session.expire(snapshot.signal);
      throw error;
    }
    return response;
  };

  // The installed axios generator forwards these SSE runtime options, but its
  // Config type omits them. Use its generated SSE type for this extension.
  const config: Config & Pick<ServerSentEventsOptions, 'fetch' | 'sseMaxRetryAttempts'> = {
    baseURL, axios: http, auth: getToken, fetch: streamFetch,
    // streamEvents owns reconnect. Disable the generator's unbounded retry loop.
    sseMaxRetryAttempts: 1,
  };
  client.setConfig(config);
  return http;
}
