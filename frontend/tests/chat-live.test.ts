import { describe, expect, it, vi } from 'vitest';
import type { ConversationView } from '../src/components/chat/model';
import { applyLiveEvent, messageView } from '../src/lib/chat-live';

vi.mock('../src/lib/api', async () => {
  const { createSession } = await import('../src/lib/session');
  return { auth: createSession() };
});

const chat = (): ConversationView => ({ key: 'conversation', title: '', updatedAt: '', draft: '', context: [], visibleCount: 30, loadingEarlier: false,
  messages: [{ key: 'answer', author: 'assistant', text: 'Первая часть', createdAt: '' }],
  reply: { key: 'run', messageKey: 'answer', submissionKey: 'submission', prompt: '', phase: 'streaming', generation: 0, lastChunk: 0, recoveryCount: 0, faultShown: false, notice: null, retryAt: null },
});

describe('live server events', () => {
  it('deduplicates replay using decimal string counters beyond safe JS integers', () => {
    const current = chat();
    const event = { runId: 'run', epoch: '2', seq: '9007199254740993', payload: { kind: 'delta' as const, text: ' дубль' } };
    expect(applyLiveEvent(current, event, { epoch: '2', sequence: '9007199254740993' })).toBe(current);
    expect(applyLiveEvent(current, { ...event, seq: '9007199254740994' }, { epoch: '2', sequence: '9007199254740993' }).messages[0].text).toBe('Первая часть дубль');
  });
  it('replaces partial text with authoritative replay-unavailable snapshot', () => {
    const result = applyLiveEvent(chat(), { runId: 'run', payload: { kind: 'terminal', replayUnavailable: true, snapshot: { text: 'Полный ответ', status: 'completed' } } });
    expect(result.messages[0].text).toBe('Полный ответ');
    expect(result.reply?.phase).toBe('completed');
  });
  it('isolates events from other runs and older worker generations', () => {
    const current = chat();
    expect(applyLiveEvent(current, { runId: 'other', payload: { kind: 'delta', text: 'чужой' } })).toBe(current);
    expect(applyLiveEvent(current, { runId: 'run', epoch: '1', seq: '50', payload: { kind: 'delta', text: 'старый' } }, { epoch: '2', sequence: '3' })).toBe(current);
  });
  it('retains typed sources for clickable citations and maps server message roles', () => {
    const event = { runId: 'run', eventId: '2:4', payload: { kind: 'sources' as const, sources: [{ id: 'document', version: 'version', title: 'Доставка' }] } };
    const result = applyLiveEvent(chat(), event);
    expect(result.events).toEqual([event]);
    expect(messageView({ id: 'message', role: 'user', text: 'Вопрос' }).author).toBe('customer');
  });
});
