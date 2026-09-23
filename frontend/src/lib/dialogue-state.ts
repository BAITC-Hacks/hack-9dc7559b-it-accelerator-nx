import type { DialogueState } from '../client';
import type { ContextField } from '../components/chat/model';
import { queryClient } from './query';

export const dialogueQueryKey = (id: string) => ['dialogue', id] as const;

/** A slow read must not replace a newer successful edit. Wire versions are int64 strings. */
export function publishDialogue(id: string, state: DialogueState): DialogueState {
  return queryClient.setQueryData<DialogueState>(dialogueQueryKey(id), previous =>
    previous?.version && state.version && BigInt(previous.version) > BigInt(state.version) ? previous : state)!;
}

export function subscribeDialogue(listener: (id: string, state: DialogueState) => void) {
  return queryClient.getQueryCache().subscribe(event => {
    const [kind, id] = event.query.queryKey;
    if (event.type === 'updated' && event.action.type === 'success' && kind === 'dialogue' && typeof id === 'string') {
      const state = event.query.state.data as DialogueState | undefined;
      if (state) listener(id, state);
    }
  });
}

export function dialogueContext(data: DialogueState): ContextField[] {
  const budget = data.budget && typeof data.budget === 'object' && 'amount' in data.budget ? data.budget : null;
  const quantity = data.quantity && typeof data.quantity === 'object' && 'value' in data.quantity ? data.quantity : null;
  return [
    ...(typeof data.category === 'string' ? [{ label: 'Категория', value: data.category }] : []),
    ...(budget ? [{ label: 'Бюджет', value: `${budget.amount} ${'currency' in budget ? budget.currency : ''}`.trim() }] : []),
    ...(quantity ? [{ label: 'Количество', value: `${quantity.value} ${'unit' in quantity ? quantity.unit : ''}`.trim() }] : []),
    ...Object.entries(data.hardConstraints ?? {}).map(([label, value]) => ({ label, value })),
    ...(data.selectedArticles?.length ? [{ label: 'Выбрано', value: data.selectedArticles.join(', ') }] : []),
    ...(typeof data.attachmentId === 'string' ? [{ label: 'Спецификация', value: `Проверенный файл · версия ${data.attachmentVersion ?? '—'}` }] : []),
  ];
}
