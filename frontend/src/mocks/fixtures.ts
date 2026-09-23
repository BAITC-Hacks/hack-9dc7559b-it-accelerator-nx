import type { PingResponse } from '../client';

// Only the published, generated DTO is used. Product/cart/attachment fixtures
// must be added after FOUND-01 supplies their springdoc schemas.
export const pingFixture = {
  app: 'hackalem-backend',
  status: 'ok',
  time: '2026-09-23T10:00:00Z',
} satisfies PingResponse;
