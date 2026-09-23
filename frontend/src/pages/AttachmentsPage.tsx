import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createConversation, listConversations } from '../client/sdk.gen';
import { AttachmentWorkspace } from '../components/attachments/AttachmentPanel';
import { useAttachments } from '../hooks/useAttachments';
import { attachmentError } from '../lib/attachments-live';

export default function AttachmentsPage() {
  const { driver, view } = useAttachments();
  const cache = useQueryClient();
  const [selected, setSelected] = useState<string | null>(null);
  const [attachmentId, setAttachmentId] = useState('');
  const mock = import.meta.env.DEV && import.meta.env.MODE === 'mock';
  const conversations = useQuery({ queryKey: ['conversations'], enabled: !mock,
    queryFn: async () => (await listConversations({ throwOnError: true })).data });
  const create = useMutation({ mutationFn: async () => (await createConversation({ throwOnError: true })).data,
    onSuccess: (data) => { if (data.id) setSelected(data.id); void cache.invalidateQueries({ queryKey: ['conversations'] }); } });
  const items = conversations.data?.items ?? [];
  const ids = [...new Set([...items.flatMap((item) => item.id ? [item.id] : []), ...view.jobs.map((job) => job.conversation), ...(selected ? [selected] : []), ...(mock ? ['attachment-demo'] : [])])];
  const conversation = selected ?? ids[0] ?? null;
  return <main className="commerce-page attachments-page"><header><Link to="/">← Вернуться в чат</Link><span className="commerce-eyebrow">Документы и фотографии</span></header>
    <h1>Проверка файлов</h1><p>Загрузите спецификацию, проверьте распознанные позиции и сформируйте предложение.</p>
    <div className="attachment-conversation"><label>Диалог<select value={conversation ?? ''} onChange={(event) => setSelected(event.target.value || null)}>
      <option value="">Выберите диалог</option>{ids.map((id, index) => <option key={id} value={id}>Диалог {index + 1} · {id.slice(0, 8)}</option>)}</select></label>
      {!mock && <button type="button" className="commerce-secondary" disabled={create.isPending} onClick={() => create.mutate()}>{create.isPending ? 'Создаём…' : 'Новый диалог'}</button>}
    </div>
    {conversations.isPending && !mock && <p role="status">Загружаем диалоги…</p>}
    {conversations.isError && <p role="alert">{attachmentError(conversations.error)} <button type="button" onClick={() => void conversations.refetch()}>Повторить</button></p>}
    {create.isError && <p role="alert">{attachmentError(create.error)}</p>}
    <AttachmentWorkspace conversation={conversation} enabled />
    {driver.open && <details className="attachment-disclosure"><summary>Открыть ранее загруженный файл по номеру</summary><p>После очистки браузера можно повторно открыть файл из вашей текущей сессии по его номеру.</p>
      <div className="attachment-search"><label>Номер файла<input value={attachmentId} onChange={(event) => setAttachmentId(event.target.value)} /></label><button type="button" className="commerce-secondary" disabled={view.busy || !attachmentId.trim()} onClick={() => void driver.open?.(attachmentId.trim()).then(() => {
        const job = driver.getSnapshot().jobs.find((item) => item.key === attachmentId.trim()); if (job) setSelected(job.conversation);
      })}>Открыть файл</button></div></details>}
  </main>;
}
