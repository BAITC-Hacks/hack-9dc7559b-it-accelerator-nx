import { isAxiosError, isCancel } from 'axios';

export class HttpResponseError extends Error {
  readonly status: number;
  readonly retryAfter: string | null;

  constructor(response: Response) {
    super(`HTTP ${response.status}`);
    this.name = 'HttpResponseError';
    this.status = response.status;
    this.retryAfter = response.headers.get('Retry-After');
  }
}

export class StreamInterruptedError extends Error {
  constructor() {
    super('Поток прерван до завершающего события. Требуется восстановить состояние run.');
    this.name = 'StreamInterruptedError';
  }
}

const MAX_WAIT_MS = 30_000;
const MAX_RETRIES = 2;

/** Transport policy, pending ratification with D1's contract. No mutation retries. */
export function retryDelayMs(error: unknown, failureCount: number, now = Date.now()): number | null {
  if (failureCount >= MAX_RETRIES || isCancel(error) ||
      (error instanceof Error && error.name === 'AbortError')) return null;

  let status: number | undefined;
  let retryAfter: unknown;
  if (error instanceof HttpResponseError) {
    status = error.status;
    retryAfter = error.retryAfter;
  } else if (isAxiosError(error)) {
    status = error.response?.status;
    retryAfter = error.response?.headers['retry-after'];
    if (!status && !['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(error.code ?? '')) return null;
  } else if (!(error instanceof TypeError || error instanceof StreamInterruptedError)) {
    return null;
  }

  if (status !== undefined && ![429, 502, 503, 504].includes(status)) return null;
  let minimum = 0;
  if (typeof retryAfter === 'string' && retryAfter.trim()) {
    const value = retryAfter.trim();
    const deadline = /^\d+$/.test(value) ? now + Number(value) * 1000 : Date.parse(value);
    if (Number.isFinite(deadline)) minimum = Math.max(0, deadline - now);
    // Do not retry earlier than the server permits to fit a client-side cap.
    if (minimum > MAX_WAIT_MS) return null;
  }
  const backoff = 500 * 2 ** failureCount;
  return Math.min(MAX_WAIT_MS, Math.max(minimum, backoff) + Math.random() * 250);
}

export function waitForRetry(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(signal.reason); return; }
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal.addEventListener('abort', onAbort, { once: true });
  });
}
