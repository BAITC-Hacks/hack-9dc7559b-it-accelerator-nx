import { afterEach, describe, expect, it, vi } from 'vitest';
import { dialogueContext, dialogueQueryKey, publishDialogue, subscribeDialogue } from '../src/lib/dialogue-state';
import { queryClient } from '../src/lib/query';

afterEach(() => queryClient.clear());

describe('shared dialogue state', () => {
  it('does not overwrite an edit with a late read, including int64 versions', () => {
    publishDialogue('chat', { version: '9007199254740994', category: 'cables' });
    expect(publishDialogue('chat', { version: '9007199254740993', category: 'breakers' }).category).toBe('cables');
    expect(queryClient.getQueryData(dialogueQueryKey('chat'))).toMatchObject({ category: 'cables' });
    expect(publishDialogue('other', { version: '1' }).version).toBe('1');
  });
  it('shares edits between panel, chat and attachment consumers', () => {
    const listener = vi.fn(); const unsubscribe = subscribeDialogue(listener);
    publishDialogue('chat', { version: '2', budget: { amount: '10000', currency: 'KZT' } });
    expect(listener).toHaveBeenCalledWith('chat', expect.objectContaining({ version: '2' }));
    unsubscribe(); listener.mockClear(); publishDialogue('chat', { version: '3' });
    expect(listener).not.toHaveBeenCalled();
  });
  it('includes budget and quantity in the context shown by the chat', () => {
    expect(dialogueContext({ budget: { amount: '5000', currency: 'KZT' }, quantity: { value: '2.5', unit: 'm' } }))
      .toEqual([{ label: 'Бюджет', value: '5000 KZT' }, { label: 'Количество', value: '2.5 m' }]);
  });
});
