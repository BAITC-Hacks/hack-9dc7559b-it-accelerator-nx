import { http, HttpResponse } from 'msw';
import { pingFixture } from './fixtures';

// Imported only by tests or the explicitly selected dev:mock entry.
export const handlers = [
  http.get('*/api/ping', () => HttpResponse.json(pingFixture)),
];
