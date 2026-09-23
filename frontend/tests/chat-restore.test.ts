import { afterEach, expect, it, vi } from 'vitest';
import { createLiveChatDriver } from '../src/lib/chat-live';
import { publishDialogue } from '../src/lib/dialogue-state';
import { queryClient } from '../src/lib/query';
import type { ChatDriver } from '../src/components/chat/model';

vi.mock('../src/lib/api', async () => {
  const { createSession } = await import('../src/lib/session');
  return { auth: createSession() };
});
vi.mock('../src/client', () => ({
  listConversations: vi.fn(async () => ({ data: { items: [{ id: 'chat' }] } })),
  listMessages: vi.fn(async () => ({ data: { items: [{ id: 'answer', role: 'assistant', text: 'Ответ целиком', runId: 'run' }] } })),
  getDialogue: vi.fn(async () => ({ data: { version: '1' } })),
  getRun: vi.fn(async () => ({ data: { id: 'run', text: 'Ответ целиком', status: 'completed', epoch: '1', sequence: '3' } })),
  cancelRun: vi.fn(), createConversation: vi.fn(), streamRun: vi.fn(), submitMessage: vi.fn(),
}));
vi.mock('../src/lib/stream', () => ({
  streamEvents: async function* () {
    yield { runId: 'run', schemaVersion: '1', payload: { kind: 'delta', text: 'Не дублировать текст' } };
    yield { runId: 'run', schemaVersion: '1', eventId: '1:2', payload: { kind: 'sources', sources: [{ id: 'doc', title: 'Доставка' }] } };
    yield { runId: 'run', schemaVersion: '1', payload: { kind: 'terminal' } };
  },
}));
let driver: ChatDriver | undefined;
afterEach(() => { driver?.dispose(); queryClient.clear(); vi.unstubAllGlobals(); });

it('restores sources without duplicating text, clears completed pending send and receives parameter edits', async () => {
  const values = new Map([['hackalem.pending.chat', JSON.stringify({ key: 'send', body: { text: 'Доставка?' }, runId: 'run' })]]);
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) });
  vi.stubGlobal('window', new EventTarget());
  driver = createLiveChatDriver();
  await vi.waitFor(() => expect(driver!.getSnapshot().conversations[0]?.events).toHaveLength(1));
  expect(driver.getSnapshot().conversations[0].messages[0].text).toBe('Ответ целиком');
  expect(driver.getSnapshot().conversations[0].reply?.phase).toBe('completed');
  expect(values.has('hackalem.pending.chat')).toBe(false);
  publishDialogue('chat', { version: '2', budget: { amount: '2500', currency: 'KZT' } });
  expect(driver.getSnapshot().conversations[0].context).toContainEqual({ label: 'Бюджет', value: '2500 KZT' });
});
