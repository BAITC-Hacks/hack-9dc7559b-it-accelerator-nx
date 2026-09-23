import type { ServerSentEventsOptions } from '../client/core/serverSentEvents.gen';
import { retryDelayMs, StreamInterruptedError, waitForRetry } from './retry';
import type { Session } from './session';

type StreamOptions = Pick<ServerSentEventsOptions,
  'onSseError' | 'onSseEvent' | 'sseMaxRetryAttempts'> & {
    signal: AbortSignal;
    headers: Record<string, string>;
  };

/** T is inferred from a generated endpoint, not a handwritten event union. */
export async function* streamEvents<T>(
  open: (options: StreamOptions) => Promise<{ stream: AsyncIterable<T> }>,
  options: {
    session: Session;
    signal?: AbortSignal;
    isTerminal: (event: T) => boolean;
    lastEventId?: string;
  },
): AsyncGenerator<T> {
  const sessionSignal = options.session.snapshot().signal;
  const lifecycleSignal = options.signal
    ? AbortSignal.any([options.signal, sessionSignal]) : sessionSignal;
  let lastEventId = options.lastEventId;
  for (let attempt = 0; ; attempt++) {
    lifecycleSignal.throwIfAborted();
    const controller = new AbortController();
    const signal = AbortSignal.any([lifecycleSignal, controller.signal]);
    let failure: unknown = new StreamInterruptedError();
    let pendingEventId: string | undefined;
    try {
      const { stream } = await open({
        signal,
        headers: lastEventId ? { 'Last-Event-ID': lastEventId } : {},
        sseMaxRetryAttempts: 1,
        onSseError: (error) => { failure = error; },
        onSseEvent: (event) => { pendingEventId = event.id; },
      });
      for await (const event of stream) {
        lifecycleSignal.throwIfAborted();
        lastEventId = pendingEventId ?? lastEventId;
        const terminal = options.isTerminal(event);
        if (terminal) controller.abort();
        yield event;
        if (terminal) return;
      }
    } catch (error) {
      failure = error;
    } finally {
      // Also close the HTTP body when the caller stops consuming the generator.
      controller.abort();
    }
    const delay = retryDelayMs(failure, attempt);
    if (delay === null) throw failure;
    await waitForRetry(delay, lifecycleSignal);
  }
}
