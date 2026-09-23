import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { source } from '../client';
import { useCommerce } from '../hooks/useCommerce';
import { SafeMarkdown } from '../components/sources/SafeMarkdown';
import { ServiceError } from './service-ui';

function LiveSource({ id, versionId }: { id: string; versionId: string }) {
  const document = useQuery({
    queryKey: ['knowledge-source', id, versionId],
    queryFn: async ({ signal }) => (await source({ path: { id, versionId }, signal, throwOnError: true })).data,
    enabled: Boolean(id && versionId), staleTime: 0, refetchOnWindowFocus: true,
  });
  // Download rechecks the current ACL instead of serving an old cached document.
  const download = useMutation({
    mutationFn: async () => {
      const response = await source({ path: { id, versionId }, throwOnError: true });
      const url = URL.createObjectURL(new Blob([response.data], { type: 'text/plain;charset=utf-8' }));
      const anchor = globalThis.document.createElement('a');
      anchor.href = url; anchor.download = `source-${versionId}.txt`; anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 1_000);
    },
  });
  if (!versionId) return <><h1>Нужна версия документа</h1><p>Откройте источник из результатов поиска: ссылка содержит ID документа и его неизменяемой версии.</p><Link to="/knowledge">Найти источник</Link></>;
  return <>
    <h1>Текст источника</h1>
    <dl className="service-ids"><dt>Документ</dt><dd>{id}</dd><dt>Версия</dt><dd>{versionId}</dd></dl>
    {document.isPending && <p role="status">Проверяем доступ и загружаем документ…</p>}
    <ServiceError error={document.error ?? download.error} />
    {document.isError && <button type="button" onClick={() => void document.refetch()}>Повторить загрузку</button>}
    {document.data !== undefined && !document.isError && <article className="service-card">
      <div className="service-actions"><span className="service-badge">Неизменяемая версия</span><button type="button" disabled={download.isPending} onClick={() => download.mutate()}>{download.isPending ? 'Скачиваем…' : 'Скачать TXT'}</button></div>
      <pre className="service-document-text">{document.data || 'Документ пуст.'}</pre>
    </article>}
  </>;
}

function DemoSource({ sourceKey }: { sourceKey: string }) {
  const { view } = useCommerce();
  const document = view.sources.find((item) => item.key === sourceKey);
  return document ? <article className="service-card"><span className="service-badge">Учебный документ · {document.version}</span><h1>{document.title}</h1><SafeMarkdown text={document.content} sources={view.sources} /></article> : <p>Учебный источник не найден.</p>;
}

export default function SourcePage() {
  const { key = '' } = useParams<{ key: string }>();
  const [params] = useSearchParams();
  const demo = import.meta.env.DEV && import.meta.env.MODE === 'mock';
  return <main className="service-page"><header><Link to="/">← Вернуться в чат</Link><Link to="/knowledge">База знаний</Link></header>
    {demo ? <DemoSource sourceKey={key} /> : <LiveSource id={key} versionId={params.get('version') ?? ''} />}
  </main>;
}
