import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getDialogue, getResultSet, updateDialogue } from '../../client';
import type { UpdateDialogue } from '../../client';
import { apiError } from '../../lib/api-errors';

export function DialogueEditor({ conversation }: { conversation: string }) {
  const cache = useQueryClient();
  const [draft, setDraft] = useState('');
  const [resultId, setResultId] = useState('');
  const state = useQuery({ queryKey: ['dialogue', conversation], queryFn: async ({ signal }) => (await getDialogue({ path: { id: conversation }, signal, throwOnError: true })).data });
  const result = useQuery({ queryKey: ['dialogue-result', conversation, resultId], enabled: !!resultId, retry: false, queryFn: async ({ signal }) => (await getResultSet({ path: { id: conversation, resultId }, signal, throwOnError: true })).data });
  const change = useMutation({ mutationFn: async () => {
    const parsed: unknown = JSON.parse(draft);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Введите объект параметров.');
    return (await updateDialogue({ path: { id: conversation }, body: { ...parsed as UpdateDialogue, expectedVersion: state.data?.version }, throwOnError: true })).data;
  }, onSuccess: (data) => { cache.setQueryData(['dialogue', conversation], data); window.dispatchEvent(new CustomEvent('hackalem:conversation', { detail: { id: conversation } })); }, onError: () => { void cache.invalidateQueries({ queryKey: ['dialogue', conversation] }); } });
  return <details className="dialogue-editor"><summary>Изменить параметры подбора</summary>
    {state.error && <p role="alert">{apiError(state.error)}</p>}
    <p>Категория, бюджет, количество и ограничения сохраняются для следующих сообщений.</p>
    <form onSubmit={(event) => { event.preventDefault(); change.mutate(); }}>
      <label>Параметры (JSON)<textarea rows={8} value={draft} placeholder={'{"category":"Автоматические выключатели","hardConstraints":{"poles":"1"},"quantity":{"value":"20","unit":"pcs","step":"1"}}'} onChange={(event) => setDraft(event.target.value)} required /></label>
      <button disabled={!state.data || change.isPending}>Сохранить параметры</button>
      {change.error && <p role="alert">{apiError(change.error)}</p>}{change.isSuccess && <p role="status">Параметры сохранены.</p>}
    </form>
    <details><summary>Текущий контекст</summary><pre className="run-details">{JSON.stringify(state.data, null, 2)}</pre></details>
    {typeof state.data?.lastResultSetId === 'string' && <button onClick={() => setResultId(state.data!.lastResultSetId as string)}>Показать последний подбор</button>}
    {result.error && <p role="alert">{apiError(result.error)}</p>}
    {result.data && <details open><summary>Подбор</summary><pre className="run-details">{JSON.stringify(result.data, null, 2)}</pre></details>}
  </details>;
}
