import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { getModelUsage } from '../../client';
import type { ConversationView } from './model';
import { apiError } from '../../lib/api-errors';

export function RunDetails({ chat }: { chat: ConversationView }) {
  const [showUsage, setShowUsage] = useState(false);
  const run = chat.reply?.key ?? '';
  const usage = useQuery({ queryKey: ['usage', run], enabled: showUsage && !!run, queryFn: async ({ signal }) => (await getModelUsage({ path: { id: run }, signal, throwOnError: true })).data });
  return <div className="run-details">
    {chat.events?.map((event) => {
      const payload = event.payload;
      if (payload?.kind === 'sources') return <section key={event.eventId}><h3>Источники ответа</h3>{payload.sources?.map((source) => <p key={`${source.id}:${source.version}`}><Link to={`/sources/${encodeURIComponent(source.id ?? '')}?version=${encodeURIComponent(source.version ?? '')}`}>{source.title ?? 'Источник'}</Link></p>)}</section>;
      if (payload?.kind === 'alternatives') return <details key={event.eventId}><summary>Предложенные аналоги</summary>{payload.alternatives?.map((item) => <p key={item.id}>{item.kind}: {item.lines?.map((line) => `${line.article} × ${line.addQuantity}`).join(', ')}. {item.differences?.join('; ')}</p>)}</details>;
      if (payload?.kind === 'review') return <p key={event.eventId}>Проверенные позиции спецификации: {payload.review?.items?.length ?? 0}. <Link to="/attachments">Открыть файлы</Link></p>;
      return null;
    })}
    <details onToggle={(event) => setShowUsage(event.currentTarget.open)}><summary>Расход модели и идентификатор ответа</summary><p><code>{run}</code></p>
      {usage.isFetching && <p>Загружаем…</p>}{usage.error && <p role="alert">{apiError(usage.error)}</p>}
      {usage.data && <pre>{JSON.stringify(usage.data, null, 2)}</pre>}
    </details>
  </div>;
}
