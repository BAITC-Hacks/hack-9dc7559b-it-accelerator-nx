import { HttpResponse } from 'msw';
import type { StreamEvent } from '../client/core/serverSentEvents.gen';

/** Generated transport envelope; T must come from generated domain DTOs. */
export function sseResponse<T>(events: readonly StreamEvent<T>[]) {
  const body = events.map((event) => [
    ...(event.id === undefined ? [] : [`id: ${event.id}`]),
    ...(event.event === undefined ? [] : [`event: ${event.event}`]),
    ...(event.retry === undefined ? [] : [`retry: ${event.retry}`]),
    `data: ${JSON.stringify(event.data)}`,
    '', '',
  ].join('\n')).join('');
  return new HttpResponse(body, {
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
  });
}
